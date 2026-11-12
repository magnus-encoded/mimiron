(ns mimiron.roster
  "Opt-in guild member names from the add-on's SavedVariables, to prime dictation.
  Read only when the player turned `/mimiron guildnames on`; held in memory,
  never logged, never written. Also the party or raid names that overflowed the
  game link strip (mimiron.link). Refreshes only when WoW writes the file (/reload or logout)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn parse
  "Names from Mimiron.lua text, or [] unless the guildnames toggle is on."
  [text]
  (if (re-find #"\[\"guildnames\"\]\s*=\s*true" text)
    (if-let [[_ block] (re-find #"(?s)\[\"roster\"\]\s*=\s*\{(.*?)\}" text)]
      (vec (map second (re-seq #"\"([^\"\\]+)\"" block)))
      [])
    []))

(defn parse-link-names
  "Party or raid names the add-on saved because they didn't fit the game link strip."
  [text]
  (if-let [[_ block] (re-find #"(?s)\[\"linknames\"\]\s*=\s*\{(.*?)\}" text)]
    (vec (map second (re-seq #"\"([^\"\\]+)\"" block)))
    []))

(defn- newest-saved-text [wtf-root]
  (let [files (->> (when wtf-root (.listFiles (io/file wtf-root "Account")))
                   (map #(io/file % "SavedVariables" "Mimiron.lua"))
                   (filter #(.isFile ^java.io.File %))
                   (sort-by (juxt #(- (.lastModified ^java.io.File %))
                                  #(.getPath ^java.io.File %))))]
    (when-let [f (first files)] (slurp f))))

(defn load-saved
  "{:guild names (opt-in) :link names} from the newest Mimiron.lua; empty when absent."
  [wtf-root]
  (try
    (let [text (or (newest-saved-text wtf-root) "")]
      {:guild (parse text) :link (parse-link-names text)})
    (catch java.io.IOException _ {:guild [] :link []})))

(defn load-roster
  "Names from the newest account's Mimiron.lua under the WTF root; [] when absent or off."
  [wtf-root]
  (try
    (let [files (->> (when wtf-root (.listFiles (io/file wtf-root "Account")))
                     (map #(io/file % "SavedVariables" "Mimiron.lua"))
                     (filter #(.isFile ^java.io.File %))
                     (sort-by (juxt #(- (.lastModified ^java.io.File %))
                                    #(.getPath ^java.io.File %))))]
      (if-let [f (first files)] (parse (slurp f)) []))
    (catch java.io.IOException _ [])))

(defn- spoken
  "A name as speech-to-text would spell it: lower case, no accents, no spaces or marks."
  [s]
  (-> (java.text.Normalizer/normalize (str s) java.text.Normalizer$Form/NFD)
      (str/replace #"\p{M}" "")
      str/lower-case
      (str/replace #"[^a-z0-9]" "")))

(defn match-name
  "The known name (as WoW spells it) that a heard name means, or nil.
  Matches whole names, with or without a realm suffix; never partial ones."
  [names heard]
  (let [h (spoken heard)]
    (when (seq h)
      (some #(when (or (= h (spoken %)) (= h (spoken (first (str/split % #"-" 2))))) %)
            names))))

(defn hotwords
  "At most `limit` names for tehuti's per-plugin dictation hotwords."
  [names limit]
  (vec (take limit (distinct names))))
