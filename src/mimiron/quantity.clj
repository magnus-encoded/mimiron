(ns mimiron.quantity
  "Trade and stack-count drafts. Confirmation remains on the player's button."
  (:require [clojure.string :as str]
            [mimiron.fill :as fill]))

(defn draft [text]
  (when-let [[_ field words]
             (re-matches #"(?is)^\s*(trade\s+gold|stack\s+split|vendor\s+quantity)(?:\s+(.*))?$" text)]
    (let [field (str/replace (str/lower-case field) #"\s+" " ")
          target ({"trade gold" "gold" "stack split" "stack" "vendor quantity" "vendor"} field)
          value (some-> words str/trim fill/prepare)
          n (when (and value (re-matches #"\d{1,10}" value)) (parse-long value))]
      (if (and n (not (re-find #"[\r\n]" text))
               (<= (if (= target "gold") 0 1) n
                   (if (= target "gold") 214748 2147483647)))
        {:line (str "/mimiron quantity " target " " n)}
        {}))))
