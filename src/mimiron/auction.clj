(ns mimiron.auction
  "Auction mode's voice vocabulary as data, and a parser from an utterance to a phrase.
  Parsing only: nothing here sends game input, and nothing is wired into the router yet.
  Each phrase says what would reach the game per deliberate act and whether it needs an
  owner ruling (docs/notes/auction-voice.md, docs/notes/fair-play.md)."
  (:require [clojure.string :as str]))

;; A phrase: its id, the trigger words that start it (matched longest first), the slot read
;; from the words after the trigger, what Mimiron would do, exactly what reaches the game per
;; press, what it needs beyond Mimiron's own state, and an owner ruling it waits on (nil when
;; none). :addon-acts means the add-on would act inside WoW's UI, beyond state sync.
;; :pad is the way to do the same with WoW's own gamepad navigation today; :alt a route that
;; skips the add-on but needs tehuti.
(def phrases
  [{:id :help :triggers ["auction help" "what can i say"] :slot :none
    :does "Show the auction phrases on the powerline as a notice."
    :game "Nothing."
    :needs #{:mimiron} :ruling nil}
   {:id :leave :triggers ["leave auction" "auction off"] :slot :none
    :does "Leave auction mode for the channel underneath; the house stays open."
    :game "Nothing."
    :needs #{:mimiron} :ruling nil}
   {:id :search :triggers ["search for" "search" "look up" "lookup"] :slot :words
    :does "Draft /mimiron ah search <words>; A sends it; the add-on fills the search box and starts the search."
    :game "Utterance: Enter, then the command as text (box left open). A: Enter. Then the add-on calls SearchBox:SetText and StartSearch."
    :needs #{:mimiron :addon} :ruling :addon-acts
    :pad "Move to the search box, A, then the tehuti on-screen keyboard."
    :alt "Game-link flag 16 (edit box focused; needs tehuti screen_probe): the words as one text action into the focused box, no chat line. Needs its own ruling: text without the open-chat key."}
   {:id :sort :triggers ["sort by"] :slot :column
    :does "Draft /mimiron ah sort <column> [reversed]; A sends it; the add-on sorts the results."
    :game "Utterance: Enter plus text. A: Enter. Then the add-on calls the list's SetSortOrder."
    :needs #{:mimiron :addon} :ruling :addon-acts
    :pad "Move to the column header, A (a header click sorts; again reverses)."}
   {:id :pick :triggers ["pick" "select"] :slot :ordinal
    :does "Draft /mimiron ah pick <n>; A sends it; the add-on selects the nth visible result."
    :game "Utterance: Enter plus text. A: Enter. Then the add-on selects row n."
    :needs #{:mimiron :addon} :ruling :addon-acts
    :pad "D-pad to the row, A."}
   {:id :quantity :triggers ["quantity"] :slot :count
    :does "Draft /mimiron ah quantity <n>; A sends it; the add-on sets the quantity box."
    :game "Utterance: Enter plus text. A: Enter. Then the add-on calls InputBox:SetNumber."
    :needs #{:mimiron :addon} :ruling :addon-acts
    :pad "Move to the quantity box, A, then the on-screen keyboard."
    :alt "Game-link flag 16 (edit box focused; needs tehuti screen_probe): the digits as one text action into the focused box, no chat line. Needs its own ruling: text without the open-chat key."}
   {:id :max :triggers ["max quantity"] :slot :none
    :does "Draft /mimiron ah max; A sends it; the add-on presses the Max button (sell only)."
    :game "Utterance: Enter plus text. A: Enter. Then the add-on clicks MaxButton."
    :needs #{:mimiron :addon} :ruling :addon-acts
    :pad "Move to Max, A."}
   {:id :price :triggers ["set price"] :slot :money
    :does "Draft /mimiron ah price <copper>; A sends it; the add-on sets the sell price."
    :game "Utterance: Enter plus text. A: Enter. Then the add-on sets the price input's amount."
    :needs #{:mimiron :addon} :ruling :addon-acts
    :pad "Move to the gold box, A, then the on-screen keyboard."
    :alt "Game-link flag 16 (edit box focused; needs tehuti screen_probe): the digits as one text action into the focused box, no chat line. Needs its own ruling: text without the open-chat key."}
   {:id :duration :triggers ["duration"] :slot :hours
    :does "Draft /mimiron ah duration <12|24|48>; A sends it; the add-on picks the duration."
    :game "Utterance: Enter plus text. A: Enter. Then the add-on sets the duration dropdown."
    :needs #{:mimiron :addon} :ruling :addon-acts
    :pad "Move to the duration dropdown, A, pick."}
   {:id :buy :triggers ["buy now" "purchase"] :slot :none
    :does "Draft /mimiron ah buy; A sends it; the add-on presses Buy. Recommended: decline, keep A on WoW's own Buy button."
    :game "Utterance: Enter plus text. A: Enter. Then the add-on clicks BuyButton (a purchase)."
    :needs #{:mimiron :addon} :ruling :addon-acts
    :pad "Move to Buy Now, A, then confirm with A."}
   {:id :bid :triggers ["place bid"] :slot :none
    :does "Draft /mimiron ah bid; A sends it; the add-on presses Bid. Recommended: decline."
    :game "Utterance: Enter plus text. A: Enter. Then the add-on clicks the bid button (C_AuctionHouse.PlaceBid)."
    :needs #{:mimiron :addon} :ruling :addon-acts
    :pad "Move to Bid, A."}
   {:id :post :triggers ["post auction" "post item"] :slot :none
    :does "Draft /mimiron ah post; A sends it; the add-on presses Post. Recommended: decline."
    :game "Utterance: Enter plus text. A: Enter. Then the add-on clicks PostButton (PostItem or PostCommodity)."
    :needs #{:mimiron :addon} :ruling :addon-acts
    :pad "Move to Post, A."}])

(def triggers
  "Every trigger, longest first, with the phrase it starts."
  (->> (for [p phrases t (:triggers p)] [t p])
       (sort-by (comp - count first))
       vec))

(defn normalize
  "Lower case, punctuation to spaces, single spaces (as mimiron.router/normalize)."
  [text]
  (->> (str/split (str/replace (str/lower-case (or text "")) #"[^a-z0-9' ]" " ") #"\s+")
       (remove str/blank?)
       (str/join " ")))

;; Numbers as speech-to-text writes them: digits ("25", "1,000" arrives as "1 000") or words.
(def ^:private units
  (zipmap ["zero" "one" "two" "three" "four" "five" "six" "seven" "eight" "nine" "ten"
           "eleven" "twelve" "thirteen" "fourteen" "fifteen" "sixteen" "seventeen" "eighteen"
           "nineteen"]
          (range)))
(def ^:private tens
  {"twenty" 20 "thirty" 30 "forty" 40 "fifty" 50 "sixty" 60 "seventy" 70 "eighty" 80 "ninety" 90})

(defn- digits [words]
  ;; "1 000" and "12 500": groups after the first must be three digits.
  (when (and (seq words) (re-matches #"\d+" (first words))
             (every? #(re-matches #"\d{3}" %) (rest words)))
    (parse-long (apply str words))))

(defn- spelled [words]
  (when (seq words)
    (loop [[w & more :as ws] words total 0 current nil]
      (cond
        (empty? ws) (when current (+ total current))
        (= "and" w) (recur more total current)
        ;; "a hundred", "a thousand"; a bare "a" is no number.
        (= "a" w) (when (#{"hundred" "thousand"} (first more)) (recur more total (or current 1)))
        (units w) (recur more total (+ (or current 0) (units w)))
        (tens w) (recur more total (+ (or current 0) (tens w)))
        (= "hundred" w) (recur more total (* (or current 1) 100))
        (= "thousand" w) (recur more (+ total (* (or current 1) 1000)) 0)
        :else nil))))

(defn number
  "The whole number in `words` (a seq of normalized words), or nil."
  [words]
  (or (digits words) (spelled words)))

(def ^:private ordinals
  (zipmap ["first" "second" "third" "fourth" "fifth" "sixth" "seventh" "eighth" "ninth"
           "tenth" "eleventh" "twelfth" "top"]
          [1 2 3 4 5 6 7 8 9 10 11 12 1]))

(defn- words-of [rest-text] (if (str/blank? rest-text) [] (str/split rest-text #" ")))

(defn- ordinal [words]
  ;; "pick 3", "pick three", "pick the third one", "pick number 2", "pick 3rd"
  (let [ws (cond->> words (#{"the" "number"} (first words)) rest)
        ws (cond->> ws (#{"number"} (first ws)) rest)
        ;; "the third one", but "pick one" is the first.
        ws (if (and (next ws) (#{"one" "result" "item"} (last ws))) (butlast ws) ws)
        n (or (when (= 1 (count ws))
                (or (ordinals (first ws))
                    (some-> (re-matches #"(\d+)(?:st|nd|rd|th)" (first ws)) second parse-long)))
              (number ws))]
    (when (and n (pos? n)) n)))

(def ^:private columns
  {"price" :price "unit price" :price "name" :name "level" :level "bid" :bid
   "buyout" :buyout "buy out" :buyout "time" :time-remaining "time left" :time-remaining
   "time remaining" :time-remaining})
(def ^:private reversals #{"reversed" "reverse" "descending" "backwards"})

(defn- column [words]
  (let [reversed? (boolean (reversals (last words)))
        ws (if reversed? (butlast words) words)
        c (columns (str/join " " ws))]
    (when c {:column c :reversed reversed?})))

(def ^:private coins
  {"gold" :gold "golds" :gold "g" :gold "silver" :silver "silvers" :silver "s" :silver
   "copper" :copper "coppers" :copper "c" :copper})

(defn- money
  "\"12 gold 50 silver\", \"fifty silver\", a bare number as gold. Total in copper, or nil."
  [words]
  (loop [[w & more :as ws] words pending [] amounts {}]
    (cond
      (empty? ws)
      (let [amounts (if (seq pending)
                      (when-not (:gold amounts) (assoc amounts :gold (number pending)))
                      amounts)
            {:keys [gold silver copper] :or {gold 0 silver 0 copper 0}} amounts]
        (when (and (seq amounts) (every? some? (vals amounts))
                   (< silver 100) (< copper 100))
          (let [total (+ (* gold 10000) (* silver 100) copper)]
            (when (pos? total) total))))
      (coins w)
      (let [n (number pending)]
        (when (and n (not (contains? amounts (coins w))))
          (recur more [] (assoc amounts (coins w) n))))
      :else (recur more (conj pending w) amounts))))

(def ^:private durations {12 12 24 24 48 48})
(def ^:private duration-words {"short" 12 "medium" 24 "long" 48})

(defn- hours [words]
  (let [ws (if (#{"hours" "hour" "h"} (last words)) (butlast words) words)]
    (or (when (= 1 (count ws)) (duration-words (first ws)))
        (durations (number ws)))))

;; The search box holds 64 bytes (AuctionHouseSearchBoxTemplate).
(def ^:private max-search 63)
;; The quantity box takes 8 digits (AuctionHouseQuantityInputEditBoxTemplate).
(def ^:private max-quantity 99999999)

(defn- read-slot [slot words]
  (case slot
    :none (when (empty? words) {})
    :words (let [s (str/join " " words)]
             (when (<= 1 (count s) max-search) {:words s}))
    :column (column words)
    :ordinal (some->> (ordinal words) (hash-map :n))
    :count (let [n (number words)] (when (and n (<= 1 n max-quantity)) {:n n}))
    :money (some->> (money words) (hash-map :copper))
    :hours (some->> (hours words) (hash-map :hours))))

(defn parse
  "The auction phrase an utterance says, as {:id ... slot values}, or nil. Only the longest
  trigger the utterance starts with is tried, so \"search for\" never searches for \"for\"."
  [text]
  (let [said (normalize text)]
    (when-let [[t p] (some (fn [[t _ :as e]]
                             (when (or (= said t) (str/starts-with? said (str t " "))) e))
                           triggers)]
      (when-let [slots (read-slot (:slot p) (words-of (str/trim (subs said (count t)))))]
        (assoc slots :id (:id p))))))

(defn needs-ruling
  "The ids of phrases that wait on an owner ruling."
  []
  (set (map :id (filter :ruling phrases))))
