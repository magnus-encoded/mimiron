(ns mimiron.router-test
  (:require [clojure.test :refer [deftest is testing]]
            [mimiron.router :as r]))

(defn- say [state & texts]
  (reduce (fn [s t] (first (r/speech s {} t))) state texts))

(defn- press [state via name]
  (first (r/button state via name true)))

(deftest mishearings-pick-the-intended-channel
  (doseq [[said channel] [["Chat with party." "party"] ["chat with potty" "party"]
                          ["chat with guilt" "guild"] ["Chat with rate" "raid"]
                          ["chat with battle ground" "battleground"] ["chat with sigh" "say"]]]
    (is (= channel (:channel (say r/initial said))) said)))

(deftest unknown-channel-keeps-mode-off
  (is (nil? (:channel (say r/initial "chat with banana")))))

(deftest dictation-without-mode-is-left-alone
  (is (= [] (second (r/speech r/initial {} "hello there")))))

(deftest channel-is-sticky-and-opens-chat-first
  (let [s (say r/initial "chat with guild")]
    (doseq [t ["one" "two"]]
      (is (= [{:type "key" :key "enter"} {:type "text" :text (str "/g " t)}]
             (second (r/speech s {} t)))))))

(deftest stop-chat-ends-mode
  (is (= [] (second (r/speech (say r/initial "chat with say" "stop chat") {} "hello")))))

(deftest status-follows-state
  (let [s (say r/initial "chat with party")]
    (is (nil? (r/status r/initial)))
    (is (= "Party" (r/status s)))
    (is (= "Party (paused)" (r/status (press s :pad "select"))))
    (is (= "Party ↕" (r/status (say s "switch channel"))))
    (is (every? #(<= (count %) 32)
                (for [c r/channels p [true false] w [true false]]
                  (r/status {:channel c :paused p :switching w}))))))

(deftest dpad-steps-channels-and-wraps
  (let [s (say r/initial "chat with party" "switch channel")]
    (is (= "guild" (:channel (press s :pad "dpad_down"))))
    (is (= "say" (:channel (press s :pad "dpad_up"))))
    (is (= "battleground" (:channel (-> s (press :pad "dpad_up") (press :pad "dpad_up")))))))

(deftest overrides-follow-state
  (let [chat (say r/initial "chat with party")]
    (is (= [] (r/overrides r/initial)))
    (is (= ["select"] (r/overrides chat)))
    (testing "the D-pad is held back only while switching"
      (is (= #{"select" "dpad_up" "dpad_down" "east" "start"}
             (set (r/overrides (say chat "switch channel")))))
      (is (= ["select"] (r/overrides (-> chat (say "switch channel") (press :pad "east"))))))
    (testing "A and B are held back only while a line is drafted"
      (is (= #{"select" "south" "east"} (set (r/overrides (say chat "hi"))))))))

(deftest dpad-does-nothing-outside-switching
  (let [s (say r/initial "chat with party")]
    (is (= s (press s :pad "dpad_down")))))

(deftest switch-without-chat-mode-is-ignored
  (is (false? (:switching (say r/initial "switch channel")))))

(deftest b-cancels-a-draft-with-escape
  (let [s (say r/initial "chat with party" "hi")]
    (is (= [{:type "key" :key "escape"}] (second (r/button s :pad "east" true))))
    (is (= [] (second (r/button (say r/initial "chat with party") :pad "east" true))))))

(deftest a-sends-the-draft
  (let [s (say r/initial "chat with party" "hi")]
    (testing "a pad override presses Enter itself"
      (is (= [{:type "key" :key "enter"}] (second (r/button s :pad "south" true)))))
    (testing "a button request leaves Enter to the core and ends the draft on release"
      (is (= [] (second (r/button s :request "south" true))))
      (is (false? (:draft (first (r/button s :request "south" false))))))))

(deftest pause-stops-dictation-and-resumes
  (let [s (press (say r/initial "chat with party") :pad "select")]
    (is (= [] (second (r/speech s {} "hi"))))
    (is (seq (second (r/speech (say s "resume chat") {} "hi"))))))

(deftest leaving-the-game-drops-switching-and-draft
  (let [s (r/focus (say r/initial "chat with party" "hi" "switch channel") false)]
    (is (false? (:switching s)))
    (is (false? (:draft s)))
    (is (= "party" (:channel s)))))

(deftest voice-actions-use-bindings
  (is (= [{:type "key" :key "b"}] (second (r/speech r/initial {} "open bags"))))
  (is (= [{:type "key" :key "m"}] (second (r/speech r/initial {} "Open the map."))))
  (is (= [{:type "key" :key "j" :mods ["ctrl"]}]
         (second (r/speech r/initial {"B" "NONE" "CTRL-J" "OPENALLBAGS"} "open bags")))))
