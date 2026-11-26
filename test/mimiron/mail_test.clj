(ns mimiron.mail-test
  (:require [mimiron.window-fixture :as windows]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [mimiron.mail :as mail]
            [mimiron.router :as router]))

(deftest each-mail-field-drafts-and-waits-for-a-button
  (doseq [[phrase expected] [["mail recipient Thrall-Realm" "recipient Thrall-Realm"]
                             ["Mail subject Two gold for you!" "subject Two gold for you!"]
                             ["mail body One more thing: thank you." "body One more thing: thank you."]
                             ["mail gold twenty five" "gold 25"]
                             ["mail gold 25 gold" "gold 25"]
                             ["mail gold twenty five gold" "gold 25"]
                             ["mail gold zero" "gold 0"]
                             ["mail gold 214748" "gold 214748"]]]
    (let [line (str "/mimiron mail " expected)
          [state actions] (router/speech (windows/open-state router/initial) {} phrase)]
      (is (= {:line line} (mail/draft phrase)))
      (is (= [{:type "key" :key "enter"} {:type "text" :text line}] actions))
      (is (:draft state))
      (is (= #{"south" "east"} (set (router/overrides state))))
      ;; A second fill cannot press Enter on the first draft.
      (is (empty? (second (router/speech state {} phrase))))
      (is (= [{:type "key" :key "enter"}]
             (second (router/button state :pad "south" true))))
      (let [[cancelled actions] (router/button state :pad "east" true)]
        (is (not (:draft cancelled)))
        (is (not-any? #(= "enter" (:key %)) actions))))))

(deftest refused-mail-fills-never-reach-chat
  (doseq [phrase ["mail recipient" "mail subject " "mail body" "mail gold"
                  "mail gold -1" "mail gold 1.5" "mail gold 214749"
                  "mail gold one two" "mail gold 25 silver"
                  "mail body hello\n/run bad" "mail subject hello\r/send"]]
    (let [[state actions] (router/speech (assoc (windows/open-state router/initial) :channel "party") {} phrase)]
      (is (= {} (mail/draft phrase)) phrase)
      (is (empty? actions) phrase)
      (is (= "mail fill refused" (:notice state)))
      (is (not (:draft state))))))

(deftest no-mail-send-voice-command
  (is (nil? (mail/draft "mail send")))
  (is (empty? (second (router/speech (windows/open-state router/initial) {} "mail send")))))

(deftest addon-only-fills-and-does-not-send-or-store-content
  (let [lua (slurp "addon/Mimiron/Mail.lua")]
    (doseq [forbidden ["SendMail(" ":Click(" "StaticPopup" "MimironDB" "print("]]
      (is (not (str/includes? lua forbidden)) forbidden)))
  (is (str/includes? (slurp "addon/Mimiron/Mimiron.lua") "MimironMail.fill(mail)"))
  (is (some #{"Mail.lua"} (str/split-lines (slurp "addon/Mimiron/Mimiron.toc")))))

(deftest mail-fields-under-stub-ui
  ;; Execute only on the test machine, alongside the existing add-on fixtures.
  (if-let [exe (try (when (zero? (:exit (sh/sh "lua5.1" "-v"))) "lua5.1")
                   (catch java.io.IOException _ nil))]
    (let [{:keys [exit out]} (sh/sh exe "test/fixtures/mail-fill.lua")]
      (is (zero? exit) out))
    (is true "lua5.1 not installed; run fixture on test machine")))
