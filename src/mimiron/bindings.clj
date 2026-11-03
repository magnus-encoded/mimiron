(ns mimiron.bindings
  "Reads WoW key bindings so voice actions press whatever key the user bound."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def default-keys
  {"TOGGLESPELLBOOK" "P"
   "OPENALLBAGS" "B"
   "TOGGLEWORLDMAP" "M"})

(def ^:private key-names
  {"SPACE" "space" "ESCAPE" "escape" "ENTER" "enter" "TAB" "tab"
   "BACKSPACE" "backspace" "HOME" "home" "END" "end" "PAGEUP" "page_up"
   "PAGEDOWN" "page_down" "INSERT" "insert" "DELETE" "delete"
   "UP" "up" "DOWN" "down" "LEFT" "left" "RIGHT" "right"
   "-" "minus" "=" "equal" "[" "left_bracket" "]" "right_bracket"
   ";" "semicolon" "'" "apostrophe" "`" "grave" "," "comma"
   "." "period" "/" "slash" "\\" "backslash"})

(def ^:private modifiers {"CTRL" "ctrl" "ALT" "alt" "SHIFT" "shift"})

(defn parse
  "{key action} from bindings-cache.wtf lines like `bind SHIFT-1 ACTION`, in file order."
  [text]
  (into (array-map)
        (keep (fn [line]
                (let [[word k action] (str/split (str/trim line) #"\s+" 3)]
                  (when (and (= "bind" word) action) [k (str/trim action)]))))
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

(defn load-bindings
  "Bindings from the newest Account/*/bindings-cache.wtf, or {} when none. Never writes."
  [wtf-root]
  (let [accounts (.listFiles (io/file wtf-root "Account"))
        caches (->> accounts
                    (map #(io/file % "bindings-cache.wtf"))
                    (filter #(.isFile ^java.io.File %))
                    (sort-by #(- (.lastModified ^java.io.File %))))]
    (or (some #(try (parse (slurp %)) (catch java.io.IOException _ nil)) caches)
        {})))
