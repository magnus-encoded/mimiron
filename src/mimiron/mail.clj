(ns mimiron.mail
  "Mail field drafts only. A confirms the fill; WoW's Send button sends mail."
  (:require [clojure.string :as str]
            [mimiron.fill :as fill]))

(defn draft
  "Nil for other speech, otherwise a field command or an empty map on refusal.
  Text stays verbatim; gold is a bounded whole number of gold coins."
  [text]
  (when-let [[_ field value] (re-matches #"(?is)^\s*mail\s+(recipient|subject|body|gold)(?:\s+(.*))?$" text)]
    (let [field (str/lower-case field)
          value (some-> value str/trim)
          value (if (= field "gold")
                  (some-> value (str/replace #"(?i)\s+gold$" "") fill/prepare)
                  value)]
      (if (and (seq value) (not (re-find #"[\r\n]" text))
               (or (not= field "gold")
                   (and (re-matches #"\d{1,6}" value) (<= (parse-long value) 214748))))
        {:line (str "/mimiron mail " field " " value)}
        {}))))
