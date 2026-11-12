(ns mimiron.auction-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [mimiron.auction :as a]
            [mimiron.router :as r]
            [mimiron.sounds :as sounds]))

(deftest phrases-without-a-slot
  (doseq [[said id] [["auction help" :help] ["What can I say?" :help]
                     ["leave auction" :leave] ["Auction off." :leave]
                     ["max quantity" :max] ["buy now" :buy] ["Purchase." :buy]
                     ["place bid" :bid] ["post auction" :post] ["post item" :post]]]
    (is (= {:id id} (a/parse said)) said))
  (testing "extra words after a bare phrase are not that phrase"
    (is (nil? (a/parse "buy now please")))
    (is (nil? (a/parse "place bid twice")))))

(deftest search-takes-the-words-after-the-trigger
  (is (= {:id :search :words "linen cloth"} (a/parse "Search linen cloth")))
  (is (= {:id :search :words "linen cloth"} (a/parse "search for linen cloth")))
  (is (= {:id :search :words "peacebloom"} (a/parse "look up peacebloom")))
  (is (= {:id :search :words "peacebloom"} (a/parse "lookup peacebloom")))
  (is (= {:id :search :words "dragon's blood"} (a/parse "search for Dragon's Blood!")))
  (testing "nothing to search for"
    (is (nil? (a/parse "search")))
    (is (nil? (a/parse "search for"))))
  (testing "longer than the search box holds"
    (is (nil? (a/parse (str "search " (str/join " " (repeat 16 "word"))))))
    (is (some? (a/parse (str "search " (str/join " " (repeat 12 "word"))))))))

(deftest sort-takes-a-column-and-an-optional-reversal
  (is (= {:id :sort :column :price :reversed false} (a/parse "sort by price")))
  (is (= {:id :sort :column :price :reversed false} (a/parse "sort by unit price")))
  (is (= {:id :sort :column :buyout :reversed true} (a/parse "sort by buyout reversed")))
  (is (= {:id :sort :column :buyout :reversed false} (a/parse "sort by buy out")))
  (is (= {:id :sort :column :time-remaining :reversed true} (a/parse "sort by time left descending")))
  (is (= {:id :sort :column :name :reversed false} (a/parse "Sort by name.")))
  (is (nil? (a/parse "sort by colour")))
  (is (nil? (a/parse "sort by"))))

(deftest pick-takes-a-position
  (doseq [[said n] [["pick 3" 3] ["pick three" 3] ["pick the third" 3] ["pick the third one" 3]
                    ["select number 2" 2] ["pick the number 2" 2] ["pick 3rd" 3] ["pick first" 1]
                    ["pick one" 1] ["pick top" 1] ["select twelve" 12] ["pick 21st" 21]]]
    (is (= {:id :pick :n n} (a/parse said)) said))
  (is (nil? (a/parse "pick")))
  (is (nil? (a/parse "pick zero")))
  (is (nil? (a/parse "pick a")))
  (is (nil? (a/parse "pick the shiny one"))))

(deftest quantity-takes-a-number
  (doseq [[said n] [["quantity 20" 20] ["quantity twenty" 20] ["quantity twenty five" 25]
                    ["quantity two hundred" 200] ["quantity a hundred" 100]
                    ["quantity one thousand two hundred and fifty" 1250]
                    ["quantity 1,000" 1000] ["quantity 12 500" 12500]]]
    (is (= {:id :quantity :n n} (a/parse said)) said))
  (is (nil? (a/parse "quantity 0")))
  (is (nil? (a/parse "quantity")))
  (is (nil? (a/parse "quantity lots")))
  (is (nil? (a/parse "quantity 2 50")) "only thousands groups join")
  (is (nil? (a/parse "quantity 123456789")) "the box takes 8 digits"))

(deftest set-price-reads-gold-silver-copper
  (doseq [[said copper] [["set price 12 gold" 120000] ["set price 12" 120000]
                         ["set price twelve gold fifty silver" 125000]
                         ["set price 50 silver" 5000] ["set price 1 gold 2 silver 3 copper" 10203]
                         ["set price 3 copper" 3]]]
    (is (= {:id :price :copper copper} (a/parse said)) said))
  (is (nil? (a/parse "set price")))
  (is (nil? (a/parse "set price 150 silver")) "silver stays under 100")
  (is (nil? (a/parse "set price 12 gold 50")) "a trailing bare number is ambiguous")
  (is (nil? (a/parse "set price 0 gold")))
  (is (nil? (a/parse "set price gold")))
  (is (nil? (a/parse "set price 1 gold 2 gold"))))

(deftest duration-is-twelve-twenty-four-or-forty-eight-hours
  (doseq [[said h] [["duration 12 hours" 12] ["duration twenty four hours" 24] ["duration 48" 48]
                    ["duration short" 12] ["duration medium" 24] ["duration long" 48]]]
    (is (= {:id :duration :hours h} (a/parse said)) said))
  (is (nil? (a/parse "duration 36 hours")))
  (is (nil? (a/parse "duration"))))

(deftest other-speech-is-not-an-auction-phrase
  (doseq [said ["hello there" "chat with party" "open bags" "roll" "screenshot" "reply"
                "searching" "picked" "" nil]]
    (is (nil? (a/parse said)) (pr-str said))))

(deftest every-phrase-states-what-reaches-the-game
  (doseq [{:keys [id triggers slot does game needs ruling]} a/phrases]
    (is (seq triggers) id)
    (is (#{:none :words :column :ordinal :count :money :hours} slot) id)
    (is (and (seq does) (seq game)) id)
    (is (contains? needs :mimiron) id)
    (testing "a phrase that needs the add-on to act waits on an owner ruling"
      (is (= (contains? needs :addon) (= :addon-acts ruling)) id))))

(deftest only-mimiron-state-phrases-go-without-a-ruling
  (is (= #{:search :sort :pick :quantity :max :price :duration :buy :bid :post} (a/needs-ruling)))
  (is (= ["Nothing." "Nothing."]
         (map :game (remove :ruling a/phrases)))))

(deftest triggers-are-unique-and-sound-unlike-a-channel
  (let [all (map first a/triggers)
        channels (conj r/channels "whisper")
        by-channel (zipmap channels channels)]
    (is (apply distinct? all))
    (doseq [t all
            c channels]
      (is (<= 2 (sounds/distance (sounds/sound-key t) (sounds/sound-key c))) (str t " vs " c)))
    (doseq [t all]
      (is (nil? (sounds/closest by-channel t)) t))))

(deftest a-trigger-never-starts-a-channel-switch
  (doseq [t (map first a/triggers)]
    (is (nil? (:channel (first (r/speech r/initial {} t)))) t)))
