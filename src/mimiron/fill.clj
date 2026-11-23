(ns mimiron.fill
  "Strict numeric voice fills. Invalid numeric phrases never become game input."
  (:require [clojure.string :as str]))

(def ^:private small
  (zipmap (str/split "zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen" #" ") (range)))
(def ^:private tens
  (zipmap (str/split "twenty thirty forty fifty sixty seventy eighty ninety" #" ") (range 20 100 10)))
(def ^:private cap 2147483647)

(defn- atom-number [s]
  (or (small s) (tens s)
      (when (and s (re-matches #"\d+" s))
        ;; Retain numeric recognition beyond the parser's bound, so an enormous
        ;; amount is refused rather than reclassified as free text.
        (if (> (count s) 10) (inc cap) (parse-long s)))))

(defn- under-hundred [ws]
  (case (count ws)
    1 (let [n (atom-number (first ws))] (when (and n (< n 100)) n))
    2 (let [[a b] ws n (atom-number b)]
        (when (and (tens a) n (< 0 n 10)) (+ (tens a) n)))
    nil))

(defn- group-number [ws]
  (let [[a b & more] ws]
    (if (= b "hundred")
      (let [n (if (= a "a") 1 (atom-number a))
            had-and? (= "and" (first more))
            more (if had-and? (rest more) more)
            tail (if (seq more) (under-hundred more) 0)]
        (when (and n (< 0 n 10) (some? tail) (or (not had-and?) (seq more))) (+ (* n 100) tail)))
      (or (under-hundred ws)
          (when (= 1 (count ws)) (atom-number a))))))

(defn- number-value [ws]
  (let [[before after] (split-with #(not= "thousand" %) ws)
        n (group-number before)]
    (if (seq after)
      (let [tail (rest after)
            had-and? (= "and" (first tail))
            tail (if had-and? (rest tail) tail)
            m (if (seq tail) (group-number tail) 0)]
        (when (and n (< 0 n 1000) m (< m 1000) (or (not had-and?) (seq tail))) (+ (* n 1000) m)))
      n)))

(defn prepare
  "Canonicalise a whole number/money phrase; preserve other text verbatim.
  A parsed amount beyond the supported bound is refused."
  [text]
  (let [ws (str/split (str/lower-case (str/trim text)) #"[\s-]+")
        coin? #{"gold" "silver" "copper"}
        plain? (not (re-find #"[^a-z0-9\s-]|^\s*-" (str/lower-case text)))
        amounts (when (and plain? (some coin? ws))
                  (loop [remaining ws amounts {}]
                    (if (empty? remaining)
                      amounts
                      (let [[part tail] (split-with (complement coin?) remaining)
                            coin (first tail) n (number-value part)]
                        (when (and coin n (not (contains? amounts coin)))
                          (recur (rest tail) (assoc amounts coin n)))))))
        n (when (and plain? (not (some coin? ws))) (number-value ws))]
    (cond
      amounts (let [g (get amounts "gold" 0) s (get amounts "silver" 0)
                    c (get amounts "copper" 0)]
                (let [total (+ (* g 10000) (* s 100) c)]
                  (when (<= total cap)
                    (str (quot total 10000) " gold " (mod (quot total 100) 100)
                         " silver " (mod total 100) " copper"))))
      n (when (<= 0 n cap) (str n))
      :else text)))
