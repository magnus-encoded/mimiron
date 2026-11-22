(ns mimiron.auction-mode-test
  "Auction mode's phrases through the router (issues #6, #7): a field phrase drafts one
  `/mimiron ah` line that only A sends; help and leave are Mimiron's own state. Fair-play
  exception 3 (docs/notes/fair-play.md)."
  (:require [clojure.test :refer [deftest is testing]]
            [mimiron.router :as r]))

(def ^:private enter {:type "key" :key "enter"})

(defn- say [state & texts]
  (reduce (fn [s t] (first (r/speech s {} t))) state texts))

(def ^:private solo {:group :solo :auction false})
(def ^:private solo-ah {:group :solo :auction true})

;; Chatting in guild, then the house opened.
(def ^:private at-ah (r/situation (r/situation (say r/initial "chat with guild") solo) solo-ah))

(deftest one-utterance-and-one-a-give-one-command
  (doseq [[said line] [["search for linen cloth" "/mimiron ah search linen cloth"]
                       ["sort by price" "/mimiron ah sort price"]
                       ["pick three" "/mimiron ah pick 3"]
                       ["quantity 20" "/mimiron ah quantity 20"]
                       ["max quantity" "/mimiron ah max"]
                       ["set price 12 gold" "/mimiron ah price 120000"]
                       ["listing time 24 hours" "/mimiron ah duration 24"]]]
    (let [[s actions] (r/speech at-ah {} said)]
      (is (= [enter {:type "text" :text line}] actions) said)
      (is (:draft s) "the box stays open for A or B")
      (is (:auction s) "still auction mode")
      (is (= ["south" "east"] (r/overrides s)))
      (is (= [enter] (second (r/button s :pad "south" true))) "A sends it")
      (is (not (:draft (first (r/button s :pad "south" true))))))))

(deftest b-cancels-a-drafted-field-phrase
  (let [s (say at-ah "quantity 20")
        [s2 actions] (r/button s :pad "east" true)]
    (is (not (:draft s2)))
    (is (not-any? #{enter} actions) "nothing is sent")))

(deftest a-field-phrase-never-joins-an-open-draft
  (let [s (say at-ah "search for linen cloth")]
    (doseq [said ["quantity 20" "search peacebloom" "max quantity" "auction help"]]
      (let [[s2 actions] (r/speech s {} said)]
        (is (= [] actions) said)
        (is (:draft s2) said)))))

(deftest field-phrases-need-auction-mode
  (testing "in a channel they are dictated chat, as any words"
    (is (= [enter {:type "text" :text "/g search for linen cloth"}]
           (second (r/speech (say r/initial "chat with guild") {} "search for linen cloth")))))
  (testing "with chat off they do nothing"
    (is (= [] (second (r/speech r/initial {} "quantity 20"))))))

(deftest voice-never-buys-bids-or-posts-at-the-house
  (doseq [said ["buy now" "purchase" "place bid" "post auction" "post item" "buy" "post"]]
    (let [[s actions] (r/speech at-ah {} said)]
      (is (= [] actions) said)
      (is (not (:draft s)) said))))

(deftest leave-auction-returns-to-the-channel-underneath
  (doseq [said ["leave auction" "Auction off."]]
    (let [[s actions] (r/speech at-ah {} said)]
      (is (= [] actions) "no game input")
      (is (not (:auction s)))
      (is (= "Guild" (r/status s)))
      (is (= "Guild" (r/status (r/situation s solo-ah))) "the house is still open: no re-entry")
      (is (= [enter {:type "text" :text "/g hello"}] (second (r/speech s {} "hello"))))
      (is (= "Guild" (r/status (r/situation s solo))) "closing it later changes nothing more")
      (is (= "Auction" (r/status (r/situation (r/situation s solo) solo-ah)))
          "the next visit enters auction mode again")))
  (testing "a draft keeps its box"
    (let [s (say at-ah "quantity 20" "leave auction")]
      (is (:draft s))
      (is (not (:auction s)))))
  (testing "outside auction mode it is a chat line"
    (is (= [enter {:type "text" :text "/g leave auction"}]
           (second (r/speech (say r/initial "chat with guild") {} "leave auction"))))))

(deftest help-shows-the-phrases-until-the-next-utterance
  (doseq [said ["auction help" "What can I say?"]]
    (let [[s actions] (r/speech at-ah {} said)]
      (is (= [] actions) "no game input")
      (is (= "Auction: search, sort by, pick, quantity, max quantity, set price, listing time, leave auction"
             (r/status s)))
      (is (= "Auction" (r/status (say s "hello there"))) "any next utterance clears it")
      (is (= "Guild" (r/status (r/situation s solo))) "closing the house clears it")
      (is (= "Auction" (r/status (r/situation (r/situation s solo) solo-ah)))
          "it does not come back on the next visit"))))

;; Every auction phrase, the denied verbs and A/B, explored to a fixed point from the house.
(def ^:private auction-events
  (concat
   (for [t ["search for linen cloth" "sort by price reversed" "pick 2" "quantity 20"
            "max quantity" "set price 5 silver" "listing time short" "auction help"
            "leave auction" "buy now" "place bid" "post auction" "hello" "chat with party"
            "stop chat" "roll"]]
     [:speech t])
   (for [via [:pad :request] button ["south" "east"] pressed? [true false]]
     [:button via button pressed?])))

(defn- step [state [kind & args]]
  (case kind
    :speech (r/speech state {} (first args))
    :button (apply r/button state args)))

(deftest speech-at-the-house-opens-the-box-at-most-and-never-sends
  (loop [seen #{} pending #{at-ah}]
    (when (seq pending)
      (let [transitions (for [state pending event auction-events]
                          [state event (step state event)])]
        (doseq [[state [kind :as event] [_ actions]] transitions
                :when (= :speech kind)]
          (is (not-any? #{enter} (if (and (not (:draft state)) (= enter (first actions)))
                                   (rest actions)
                                   actions))
              (str "a send from " state " via " event))
          (is (<= (count actions) 2) (str event)))
        (let [visited (into seen pending)]
          (recur visited (reduce disj (set (map #(first (nth % 2)) transitions)) visited)))))))
