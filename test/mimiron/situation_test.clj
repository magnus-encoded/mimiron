(ns mimiron.situation-test
  "Modes that follow the game link's situation: the group sets the default channel, the
  auction house sets auction mode. Mimiron's own state only, never an action."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [mimiron.router :as r]))

(defn- say [state & texts]
  (reduce (fn [s t] (first (r/speech s {} t))) state texts))

(defn- seen [state & situations]
  (reduce r/situation state situations))

(def ^:private solo {:group :solo :auction false})
(def ^:private party {:group :party :auction false})
(def ^:private raid {:group :raid :auction false})
(def ^:private bg {:group :battleground :auction false})
(def ^:private solo-ah {:group :solo :auction true})

(deftest a-chosen-channel-never-moves-with-the-group
  (let [chatting (seen (say r/initial "chat with guild") solo)]
    (doseq [sits [[party] [party raid] [bg] [party solo] [party bg]]]
      (is (= "guild" (:channel (apply seen chatting sits))) (str sits)))))

(deftest a-party-joined-before-a-battleground-is-kept
  (let [s (seen (say r/initial "chat with party") solo party bg)]
    (is (= "party" (:channel s)) "owner ruling on #12 Q1: only a manual switch moves it")
    (is (= "battleground" (:channel (say s "chat with battleground"))))))

(deftest a-whisper-and-switching-survive-a-group-change
  (let [s (seen (first (r/speech r/initial {} "whisper ana" identity ["Ana"])) solo party)]
    (is (= "whisper" (:channel s)))
    (is (= "ana" (str/lower-case (:target s)))))
  (is (true? (:switching (seen (say r/initial "chat with guild" "switch channel") solo party)))))

(deftest with-no-channel-chosen-a-group-sets-the-default
  (is (= "party" (:channel (seen r/initial solo party))) "entering a party")
  (is (= "raid" (:channel (seen r/initial solo raid))))
  (is (= "battleground" (:channel (seen r/initial solo bg))))
  (is (= "party" (:channel (seen (say r/initial "chat with guild" "stop chat") solo party)))))

(deftest with-no-channel-chosen-staying-solo-leaves-chat-off
  (is (nil? (:channel (seen r/initial solo solo))))
  (is (nil? (:channel (seen r/initial party))) "the first situation seen is only recorded"))

(deftest a-draft-keeps-its-box
  (let [s (seen (say r/initial "chat with guild" "hello") solo)
        [s2 actions] (r/speech (seen s party) {} "there")]
    (is (:draft s2))
    (is (= [{:type "text" :text " there"}] actions) "extends the open box, sends nothing")))

(deftest the-auction-house-is-its-own-mode
  (let [s (seen (say r/initial "chat with guild") solo)
        at-ah (seen s solo-ah)]
    (testing "entering shows on the powerline"
      (is (:auction at-ah))
      (is (= "Auction" (r/status at-ah))))
    (testing "the vocabulary is a stub: dictation types nothing"
      (is (= [] (second (r/speech at-ah {} "hello there"))))
      (is (empty? r/auction-vocabulary)))
    (testing "closing restores the previous mode"
      (is (= "Guild" (r/status (seen at-ah solo)))))
    (testing "a group change never moves the channel underneath"
      (is (= "Guild" (r/status (seen at-ah {:group :party :auction true} party)))))
    (testing "the auction house open at the first sight still counts"
      (is (= "Auction" (r/status (seen r/initial solo-ah)))))))

(deftest picking-a-channel-at-the-auction-house-leaves-auction-mode
  (let [at-ah (seen (say r/initial "chat with guild") solo solo-ah)
        s (say at-ah "chat with party")]
    (is (= "Party" (r/status s)))
    (is (= "Party" (r/status (seen s solo-ah))) "the pick stays while the house stays open")
    (is (= [{:type "key" :key "enter"} {:type "text" :text "/p hi"}]
           (second (r/speech s {} "hi"))))
    (is (= "Party" (r/status (seen s solo))) "closing it changes nothing more")))

(deftest stop-chat-keeps-auction-mode
  (let [s (say (seen (say r/initial "chat with guild") solo solo-ah) "stop chat")]
    (is (= "Auction" (r/status s)))
    (is (nil? (r/status (seen s solo))))))

(deftest a-situation-is-never-an-action
  (let [s (say r/initial "chat with guild" "hello")]
    (doseq [sit [solo party raid bg solo-ah]]
      (is (= (dissoc s :situation :channel :auction :target :switching)
             (dissoc (r/situation s sit) :situation :channel :auction :target :switching))
          "only mode keys move; the draft and pause state stay"))))
