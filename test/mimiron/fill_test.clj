(ns mimiron.fill-test
  (:require [mimiron.window-fixture :as windows]
            [clojure.test :refer [deftest is]]
            [mimiron.fill :as fill]
            [mimiron.router :as router]))

(deftest spoken-numbers
  (doseq [[said expected] [["zero" "0"] ["twenty five" "25"]
                          ["25" "25"] ["twenty 5" "25"]
                          ["2 hundred thirty 4" "234"]
                          ["two hundred fourteen thousand seven hundred forty eight" "214748"]
                          ["214 thousand 748" "214748"]]]
    (is (= expected (fill/prepare said)) said)))

(deftest money-orders-and-cap
  (doseq [parts [["25 gold" "thirty silver" "4 copper"]
                ["twenty five gold" "30 silver" "four copper"]]
          order [[0 1 2] [0 2 1] [1 0 2] [1 2 0] [2 0 1] [2 1 0]]]
    (is (= "25 gold 30 silver 4 copper"
           (fill/prepare (clojure.string/join " " (map parts order))))))
  (is (= "214748 gold 36 silver 47 copper"
         (fill/prepare "214748 gold 36 silver 47 copper"))))

(deftest out-of-range-parsed-money-is-refused
  (is (nil? (fill/prepare "214748 gold 36 silver 48 copper"))))

(deftest parsed-fill-still-needs-a
  (doseq [[said expected] [["twenty five" "25"]
                          ["30 silver 25 gold" "25 gold 30 silver 0 copper"]]]
    (let [[draft actions] (router/speech (windows/open-state router/initial) {} (str "type " said))]
      (is (= [{:type "key" :key "enter"}
              {:type "text" :text (str "/mimiron fill " expected)}] actions))
      (is (= [{:type "key" :key "enter"}]
             (second (router/button draft :pad "south" true)))))))

(deftest ordinary-utterances-stay-verbatim
  (doseq [words ["a letter to bob" "send me gold please" "5 apples"
                 "twenty nonsense" "25 gold garbage" "1.5" "-1" "one two" "gold" "one hundred hundred"]]
    (is (= words (fill/prepare words)))
    (is (= (str "/mimiron fill " words)
           (:text (second (second (router/speech (windows/open-state router/initial) {} (str "type " words)))))))))

(deftest fill-command-length-includes-prefix
  (doseq [prefix ["type " "mail subject " "mail body "]
          :let [command-prefix (if (= prefix "type ") "/mimiron fill "
                                   (str "/mimiron " prefix))
                room (- 255 (count command-prefix))
                fits (apply str (repeat room "x"))
                [state actions] (router/speech (windows/open-state router/initial) {} (str prefix fits))]]
    (is (:draft state))
    (is (= 255 (count (:text (second actions)))))
    (let [[state actions] (router/speech (windows/open-state router/initial) {} (str prefix fits "x"))]
      (is (empty? actions))
      (is (= "too long" (:notice state)))
      (is (not (:draft state)))))
  (let [[state actions] (router/speech (windows/open-state router/initial) {} (str "type " (apply str (repeat 130 "Æ"))))]
    (is (empty? actions))
    (is (= "too long" (:notice state)))))

(deftest money-overflow-is-carried
  (doseq [[said expected] [["150 copper" "0 gold 1 silver 50 copper"]
                          ["100 silver" "1 gold 0 silver 0 copper"]
                          ["2 gold 150 silver 250 copper" "3 gold 52 silver 50 copper"]
                          ["2147483647 copper" "214748 gold 36 silver 47 copper"]]]
    (is (= expected (fill/prepare said))))
  (doseq [said ["214749 gold" "2147483647 silver" "214748 gold 3648 copper"]]
    (is (nil? (fill/prepare said)))))

(deftest over-cap-single-coin-amounts-are-refused
  (doseq [amount ["99999999999 gold" "99999999999 silver" "99999999999 copper"
                  "2147483648 copper" "214749 gold"]]
    (is (nil? (fill/prepare amount)))
    (doseq [state [(assoc router/initial :channel "party")
                  (assoc router/initial :channel "party" :draft true)]]
      (let [[after actions] (router/speech state {} (str "type " amount))]
        (is (empty? actions))
        (is (= "text fill refused" (:notice after)))
        (is (= (:draft state) (:draft after)))))))

(deftest fills-cannot-append-to-an-existing-chat-draft
  (doseq [phrase ["type hello" "type" "type hello\n/run nope"
                  "mail gold 214749" "mail subject hello"
                  "trade gold 5" "stack split 0" "vendor quantity 5"]]
    (let [state (assoc (windows/open-state router/initial) :channel "party" :draft true)
          [after actions] (router/speech state {} phrase)]
      (is (empty? actions) phrase)
      (is (:draft after))
      (is (re-find #"refused" (:notice after))))))

(deftest unrelated-phrases-still-reach-chat
  (doseq [phrase ["mail send" "trade tomorrow" "stack of apples" "vendor please" "types of fish"]]
    (let [[_ actions] (router/speech (assoc (windows/open-state router/initial) :channel "party") {} phrase)]
      (is (= (str "/p " phrase) (:text (second actions)))))))
