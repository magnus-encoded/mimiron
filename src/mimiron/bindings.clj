(ns mimiron.bindings
  "Reads WoW key bindings so voice actions press whatever key the user bound."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def default-keys
  {"SCREENSHOT" "PRINTSCREEN"
   "OPENCHATSLASH" "/"
   "TOGGLESPELLBOOK" "P"
   "OPENALLBAGS" "B"
   "TOGGLEWORLDMAP" "M"
   ;; Retail's default keys for the same actions [A]: WoW Forever's client defaults aren't in the
   ;; UI source, and an empty bindings-cache.wtf (no rebinds) doesn't list them. A key the player
   ;; binds in the file wins. Currency, raid and group finder have no default here: they work once
   ;; the player binds them.
   "TOGGLECHARACTER0" "C"
   "TOGGLECHARACTER2" "U"
   "TOGGLECHARACTER4" "H"
   "TOGGLETALENTS" "N"
   "TOGGLEPROFESSIONBOOK" "K"
   "TOGGLEQUESTLOG" "L"
   "TOGGLEGUILDTAB" "J"
   "TOGGLESOCIAL" "O"
   "TOGGLECOLLECTIONS" "SHIFT-P"})

(def ^:private key-names
  {"PRINTSCREEN" "print_screen"
   "SPACE" "space" "ESCAPE" "escape" "ENTER" "enter" "TAB" "tab"
   "BACKSPACE" "backspace" "HOME" "home" "END" "end" "PAGEUP" "page_up"
   "PAGEDOWN" "page_down" "INSERT" "insert" "DELETE" "delete"
   "UP" "up" "DOWN" "down" "LEFT" "left" "RIGHT" "right"
   "-" "minus" "=" "equal" "[" "left_bracket" "]" "right_bracket"
   ";" "semicolon" "'" "apostrophe" "`" "grave" "," "comma"
   "." "period" "/" "slash" "\\" "backslash"})

(def ^:private modifiers {"CTRL" "ctrl" "ALT" "alt" "SHIFT" "shift"})

(defn parse
  "{key action} from bindings-cache.wtf lines like `bind SHIFT-1 ACTION`, in file order. Only
  key-to-action lines: spell, macro, item and click bindings (which name the player's own
  things) are dropped with every other line."
  [text]
  (into (array-map)
        (keep (fn [line]
                (when-let [[_ k action] (re-matches #"bind\s+(\S+)\s+([A-Z][A-Z0-9_]*)"
                                                    (str/trim line))]
                  [k action])))
        (str/split-lines text)))

(defn key-action
  "A key action for a WoW key such as `CTRL-P`, or nil when Tehuti cannot press it."
  [wow-key]
  (loop [mods [] rest-key wow-key]
    (let [dash (str/index-of rest-key "-" 1)]
      (if dash
        (when-let [mod (modifiers (subs rest-key 0 dash))]
          (recur (conj mods mod) (subs rest-key (inc dash))))
        (when-let [k (cond
                       (key-names rest-key) (key-names rest-key)
                       (re-matches #"[A-Z0-9]" rest-key) (str/lower-case rest-key)
                       (re-matches #"F([1-9]|1[0-2])" rest-key) (str/lower-case rest-key))]
          (cond-> {:type "key" :key k}
            (seq mods) (assoc :mods mods)))))))

(defn key-for
  "The first usable key for a WoW action: the user's bindings, then the default key
  unless the user rebound that key to something else."
  [bound action]
  (or (some (fn [[k a]] (when (= a action) (key-action k))) bound)
      (when-let [default (default-keys action)]
        (when-not (contains? bound default) (key-action default)))))

(defn wtf-root-from-launcher
  "The WTF directory a launch script's WINEPREFIX and working directory point to."
  [script home]
  (let [prefix (second (re-find #"WINEPREFIX=\"?([^\"\n]+)\"?" script))
        work (second (re-find #"(?m)cd \"?\$WINEPREFIX/([^\"\n]+?)\"?\s*(\|\||$)" script))]
    (when (and prefix work)
      (str (str/replace prefix "$HOME" home) "/" work "/WTF"))))

(defn default-wtf-root []
  (or (System/getenv "MIMIRON_WTF")
      (let [home (System/getProperty "user.home")
            launcher (io/file home ".local/bin/wow-forever")]
        (when (.exists launcher)
          (wtf-root-from-launcher (slurp launcher) home)))))

(def max-cache-bytes
  "A real bindings-cache.wtf is a few kilobytes; anything far bigger isn't one and isn't read."
  (* 1024 1024))

(defn- read-cache [^java.io.File file]
  (try
    (if (and file (.isFile file) (<= (.length file) max-cache-bytes))
      (parse (slurp file))
      {})
    (catch java.io.IOException _ {})))

(defn cache-file
  "The one binding file to read: `explicit` (an account or character cache) when given, else the
  newest account cache under WTF, else nil. Character identity cannot be inferred from mtimes,
  so a character cache is only ever read when named."
  [wtf-root explicit]
  (if explicit
    (io/file explicit)
    (->> (when wtf-root (.listFiles (io/file wtf-root "Account")))
         (map #(io/file % "bindings-cache.wtf"))
         (filter #(.isFile ^java.io.File %))
         (sort-by (juxt #(- (.lastModified ^java.io.File %)) #(.getPath ^java.io.File %)))
         first)))

(defn load-bindings
  "Reads one binding cache (see `cache-file`). Never merges binding sets or writes files."
  ([wtf-root] (load-bindings wtf-root nil))
  ([wtf-root cache-file-path] (read-cache (cache-file wtf-root cache-file-path))))

(defn- stamp [^java.io.File file]
  (when (and file (.isFile file)) [(.getPath file) (.lastModified file) (.length file)]))

(defn watcher
  "A function of no arguments giving the current bindings from the file `locate` returns. It
  reads the file again only when its path, modification time or size changed, so calling it on
  every focus and phrase costs one stat. Read only."
  [locate]
  (let [seen (atom nil)]
    (fn []
      (let [file (locate)
            s (stamp file)]
        (if (and @seen (= s (:stamp @seen)))
          (:bound @seen)
          (:bound (reset! seen {:stamp s :bound (if s (read-cache file) {})})))))))
