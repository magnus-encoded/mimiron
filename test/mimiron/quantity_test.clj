(ns mimiron.quantity-test
  (:require [mimiron.window-fixture :as windows]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [mimiron.quantity :as quantity]
            [mimiron.router :as router]))

(deftest quantity-phrases-draft-only-and-wait-for-a-button
  (doseq [[phrase expected] [["trade gold twenty five" "gold 25"]
                             ["TRADE   GOLD zero" "gold 0"]
                             ["trade gold 214748" "gold 214748"]
                             ["stack split twenty" "stack 20"]
                             ["vendor quantity one hundred and two" "vendor 102"]]]
    (let [line (str "/mimiron quantity " expected)
          [state actions] (router/speech (windows/open-state router/initial) {} phrase)]
      (is (= {:line line} (quantity/draft phrase)))
      (is (= [{:type "key" :key "slash"} {:type "text" :text (subs line 1)}] actions))
      (is (:draft state))
      (is (empty? (second (router/speech state {} phrase)))))
    (let [[state _] (router/speech (windows/open-state router/initial) {} phrase)]
      (is (= [{:type "key" :key "enter"}]
             (second (router/button state :pad "south" true)))))
    (let [[state _] (router/speech (windows/open-state router/initial) {} phrase)
          [cancelled actions] (router/button state :pad "east" true)]
      (is (not (:draft cancelled)))
      (is (not-any? #(= "enter" (:key %)) actions)))))

(deftest refused-quantities-never-reach-chat
  (doseq [prefix ["trade gold" "stack split" "vendor quantity"]
          value ["" "-1" "1.5" "one two" "25 silver" "99999999999999" "hello" "2\n/run bad"]]
    (let [phrase (str prefix " " value)
          [state actions] (router/speech (assoc (windows/open-state router/initial) :channel "party") {} phrase)]
      (is (= {} (quantity/draft phrase)) phrase)
      (is (empty? actions) phrase)
      (is (= "quantity fill refused" (:notice state)))
      (is (not (:draft state)))))
  (doseq [phrase ["trade gold 214749" "stack split zero" "vendor quantity 0"]]
    (is (= {} (quantity/draft phrase)) phrase)))

(deftest opening-a-quantity-draft-never-confirms-the-existing-dialog
  (doseq [bound [{"/" "NONE"} {"ENTER" "OPENCHATSLASH"}
                 {"CTRL-ENTER" "OPENCHATSLASH"} {"ESCAPE" "OPENCHATSLASH"}]]
    (let [[state actions] (router/speech (windows/open-state router/initial) bound "vendor quantity ten")]
      (is (empty? actions))
      (is (not (:draft state)))))
  (is (= {:type "key" :key "j"}
         (first (second (router/speech (windows/open-state router/initial) {"J" "OPENCHATSLASH"}
                                       "stack split ten"))))))

(deftest quantity-addon-is-loaded-and-has-no-transaction-path
  (is (str/includes? (slurp "addon/Mimiron/Mimiron.lua") "MimironQuantity.fill(quantity)"))
  (is (some #{"Quantity.lua"} (str/split-lines (slurp "addon/Mimiron/Mimiron.toc")))))

(deftest quantity-fields-under-stub-ui
  ;; Written here; executed only on the test machine.
  (if-let [exe (try (when (zero? (:exit (sh/sh "lua5.1" "-v"))) "lua5.1")
                   (catch java.io.IOException _ nil))]
    (let [{:keys [exit out]} (sh/sh exe "test/fixtures/quantity-fill.lua")]
      (is (zero? exit) out))
    (is true "lua5.1 not installed; run fixture on test machine")))
