(ns speech-eval.score
  "Scores transcripts with Mimiron's own matcher (mimiron.router/speech).
  Run from the repo root:
    clojure -M -i tools/speech-eval/score.clj -m speech-eval.score transcripts.jsonl"
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [mimiron.router :as router]
            [mimiron.sounds :as sounds]))

(def vocabularies
  (json/read-value (slurp "tools/speech-eval/vocabularies.json") json/keyword-keys-object-mapper))

(defn fair-channel
  "A matcher that knows `vocabulary`, for comparing vocabularies on equal terms: the
  phrase heard exactly, else the phrase that sounds closest (mimiron.sounds). Mimiron's
  router only knows `current`."
  [vocabulary heard]
  (let [phrases (into {} (map (fn [[c p]] [(router/normalize p) (name c)]))
                      (:phrases (vocabularies (keyword vocabulary))))
        h (router/normalize heard)]
    (or (phrases h) (sounds/closest phrases h))))

(defn- start-state
  "Chatting in some other channel: the case where a miss is typed into chat."
  [channel]
  (assoc router/initial :channel (if (= "say" channel) "party" "say")))

(defn outcome
  "What Mimiron does with `heard` while chatting: :hit, :wrong (another channel),
  :dictated (typed into the chat box as a line) or :ignored."
  [channel heard]
  (let [before (start-state channel)
        [after actions] (router/speech before {} heard)]
    (cond
      (= channel (:channel after)) :hit
      (not= (:channel before) (:channel after)) :wrong
      (seq actions) :dictated
      :else :ignored)))

(defn negative-outcome
  "A dictated chat line: :ok when it stays a line, :switched when it changes mode."
  [heard]
  (let [before (assoc router/initial :channel "say")
        [after _] (router/speech before {} heard)]
    (if (= (dissoc before :draft) (dissoc after :draft)) :ok :switched)))

(defn- pct [n d] (if (zero? d) "-" (format "%.0f%%" (* 100.0 (/ n d)))))

(defn- exact? [{:keys [text heard]}] (= (router/normalize text) (router/normalize heard)))

(defn- accent? [{:keys [voice]}] (str/starts-with? voice "accent-"))

(defn- table [header rows]
  (str "| " (str/join " | " header) " |\n|" (str/join "|" (repeat (count header) "---")) "|\n"
       (str/join "\n" (map #(str "| " (str/join " | " %) " |") rows)) "\n"))

(defn- summary [rows]
  (let [n (count rows)
        by (frequencies (map :outcome rows))
        native (remove accent? rows)]
    [(str n) (pct (count (filter exact? rows)) n)
     (pct (count (filter #(= (:channel %) (fair-channel (:vocabulary %) (:heard %))) rows)) n)
     (pct (:hit by 0) n)
     (pct (count (filter #(= :hit (:outcome %)) native)) (count native))
     (pct (:wrong by 0) n) (pct (:dictated by 0) n)]))

(defn report [rows]
  (let [switch (->> rows (filter #(= "switch" (:kind %)))
                    (map #(assoc % :outcome (outcome (:channel %) (:heard %)))))
        negative (->> rows (filter #(= "negative" (:kind %)))
                      (map #(assoc % :outcome (negative-outcome (:heard %)))))
        order (distinct (map :vocabulary switch))]
    (str
     "## Switch phrases: catch rate by vocabulary and priming\n\n"
     "exact = transcript equals the phrase; fair = a matcher that knows this vocabulary "
     "(exact or closest by sound) picks the intended channel; caught = Mimiron switched to the intended channel "
     "(all voices, then native-English voices only); wrong = switched elsewhere; "
     "typed = the phrase would be drafted as a chat line.\n\n"
     (table ["vocabulary" "priming" "clips" "exact" "fair" "caught" "caught (native)" "wrong" "typed"]
            (for [v order
                  [p rs] (sort-by key (group-by :priming (filter #(= v (:vocabulary %)) switch)))]
              (into [v p] (summary rs))))
     "\n## Caught by channel (priming: none / own)\n\n"
     (table (into ["vocabulary"] router/channels)
            (for [v order]
              (into [v]
                    (for [c router/channels]
                      (str/join " / "
                                (for [p ["none" "own"]]
                                  (let [rs (filter #(and (= v (:vocabulary %)) (= c (:channel %))
                                                         (= p (:priming %)))
                                                   switch)]
                                    (pct (count (filter #(= :hit (:outcome %)) rs)) (count rs)))))))))
     "\n## Caught by condition (priming: none / own)\n\n"
     (let [conditions (concat (for [n ["clean" "pink10" "babble10"]] [n #(= n (:noise %))])
                              (for [s [0.85 1.1]] [(str "speed " s) #(= s (:speed %))])
                              [["native voices" (complement accent?)] ["accent voices" accent?]])]
       (table (into ["vocabulary"] (map first conditions))
              (for [v order]
                (into [v]
                      (for [[_ f] conditions]
                        (str/join " / "
                                  (for [p ["none" "own"]]
                                    (let [rs (filter #(and (= v (:vocabulary %)) (= p (:priming %)) (f %))
                                                     switch)]
                                      (pct (count (filter #(= :hit (:outcome %)) rs)) (count rs))))))))))
     "\n## Dictated chat lines that changed mode (should be none)\n\n"
     (table ["priming" "lines" "switched"]
            (for [[p rs] (sort-by key (group-by :priming negative))]
              [p (str (count rs)) (str (count (filter #(= :switched (:outcome %)) rs)))]))
     "\n## Dictated chat lines a vocabulary's fair matcher would take as a switch\n\n"
     (table ["vocabulary" "lines" "switched"]
            (for [v order]
              [v (str (count negative))
               (str (count (filter #(fair-channel v (:heard %)) negative)))]))
     (let [bad (filter #(= :switched (:outcome %)) negative)]
       (when (seq bad)
         (str "\nSwitched lines (synthetic text, heard as):\n\n"
              (str/join "\n" (map #(str "- " (:text %) " -> " (pr-str (:heard %))) (distinct bad)))
              "\n")))
     "\n## Typed-text phrase, as spoken text (no speech engine)\n\n"
     (table ["vocabulary" "caught from the exact text"]
            (for [v order]
              (let [rs (distinct (map (juxt :channel :text) (filter #(= v (:vocabulary %)) switch)))]
                [v (pct (count (filter (fn [[c t]] (= :hit (outcome c t))) rs)) (count rs))]))))))

(defn misses
  "Heard forms Mimiron did not catch, most frequent first (synthetic speech only)."
  [rows vocabulary]
  (->> rows
       (filter #(and (= "switch" (:kind %)) (= vocabulary (:vocabulary %))))
       (remove #(= :hit (outcome (:channel %) (:heard %))))
       (map #(router/normalize (:heard %)))
       frequencies (sort-by (comp - val))))

(defn read-rows [path]
  (map #(json/read-value % json/keyword-keys-object-mapper)
       (remove str/blank? (str/split-lines (slurp path)))))

(defn -main [path & [misses-for]]
  (let [rows (read-rows path)]
    (if misses-for
      (doseq [[heard n] (misses rows misses-for)] (println n (pr-str heard)))
      (print (report rows)))
    (flush)))
