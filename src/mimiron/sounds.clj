(ns mimiron.sounds
  "A rough sound key for English words, so a heard word can be matched to the
  word meant (\"gil\" and \"guilt\" to guild, \"y'all\" to yell). Consonants that
  speech-to-text swaps share a letter; vowels drop out."
  (:require [clojure.string :as str]))

;; Applied in order to a lower-case word. A rule's output is upper case, so no
;; later rule rewrites it. Digraphs come before single letters.
(def ^:private rules
  [[#"'" ""] [#"^kn" "N"] [#"^wr" "R"] [#"ph" "F"] [#"gh" ""] [#"ck" "K"] [#"qu" "KW"]
   [#"tch|dg|ch|sh|j" "C"] [#"c(?=[eiy])" "S"] [#"g(?=[ey])" "C"]
   [#"th" "T"] [#"x" "KS"] [#"wh" "W"] [#"h" ""]
   [#"[cgkq]" "K"] [#"[dt]" "T"] [#"[bp]" "P"] [#"[fv]" "F"] [#"[sz]" "S"] [#"[mn]" "N"]
   [#"(?<=.)[aeiouy]" ""] [#"^[aeiou]+" ""] [#"(.)\1+" "$1"]])

(defn sound-key
  "The consonant skeleton of a word or phrase, e.g. \"guild\" -> \"KLT\", \"chat\" -> \"CT\"."
  [word]
  (str/upper-case
   (reduce (fn [s [re to]] (str/replace s re to))
           (str/replace (str/lower-case word) #"[^a-z']" "")
           rules)))

(defn distance
  "Edit distance between two keys."
  [^String a ^String b]
  (let [n (count b)]
    (last (reduce (fn [prev i]
                    (reduce (fn [row j]
                              (conj row (min (inc (peek row)) (inc (prev (inc j)))
                                             (+ (prev j) (if (= (.charAt a i) (.charAt b j)) 0 1)))))
                            [(inc i)] (range n)))
                  (vec (range (inc n))) (range (count a))))))

(defn closest
  "The target whose key is nearest to `heard`'s, or nil. A match must start with
  the same sound, be within 1 (2 for keys of six or more), and be strictly nearer
  than any other target. `targets` maps a spelling to its value."
  [targets heard]
  (let [h (sound-key heard)]
    (when (seq h)
      (let [scored (for [[spelling value] targets
                         :let [k (sound-key spelling)]
                         :when (and (seq k) (= (first h) (first k)))]
                     [(distance h k) value k])
            best (when (seq scored) (apply min (map first scored)))
            winners (distinct (map second (filter #(= best (first %)) scored)))]
        (when (and best (= 1 (count winners))
                   (some (fn [[d _ k]] (and (= d best) (<= d (if (>= (count k) 6) 2 1)))) scored))
          (first winners))))))
