(ns mimiron.router-test
  (:require [clojure.test :refer [deftest is testing]]
            [mimiron.router :as r]
            [mimiron.bindings :as b]))

(defn- say [state & texts]
  (reduce (fn [s t] (first (r/speech s {} t))) state texts))

(defn- press [state via name]
  (first (r/button state via name true)))

(deftest mishearings-pick-the-intended-channel
  (doseq [[said channel] [["Chat with party." "party"] ["chat with potty" "party"]
                          ["chat with guilt" "guild"] ["Chat with rate" "raid"]
                          ["chat with battle ground" "battleground"] ["chat with sigh" "say"]]]
    (is (= channel (:channel (say r/initial said))) said)))

(deftest plurals-and-possessives-pick-the-intended-channel
  (doseq [[said channel] [["chat with guilds" "guild"] ["Chat with says." "say"]
                          ["chat with raids" "raid"] ["chat with parties" "party"]
                          ["chat with guild's" "guild"] ["chat with party's" "party"]]]
    (is (= channel (:channel (say r/initial said))) said)))

(deftest plural-does-not-leave-chat-mode-on-while-in-a-channel
  (let [s (say r/initial "chat with say")]
    (is (= "guild" (:channel (say s "chat with guilds"))))
    (is (empty? (second (r/speech s {} "chat with guilds"))))))

(deftest unknown-channel-keeps-mode-off
  (is (nil? (:channel (say r/initial "chat with banana")))))

(deftest dictation-without-mode-is-left-alone
  (is (= [] (second (r/speech r/initial {} "hello there")))))

(deftest channel-is-sticky-and-opens-chat-first
  (let [s (say r/initial "chat with guild")]
    (is (= [{:type "key" :key "slash"} {:type "text" :text "g one"}]
           (second (r/speech s {} "one"))))
    (is (= [{:type "key" :key "slash"} {:type "text" :text "g two"}]
           (second (r/speech (-> s (say "one") (press :pad "south")) {} "two"))))))

(deftest speech-never-posts-without-a
  (testing "a second utterance extends the draft instead of pressing Enter"
    (let [s (say r/initial "chat with party" "background talk")]
      (is (= [{:type "key" :key "slash"} {:type "text" :text " more talk"}] (second (r/speech s {} "more talk"))))))
  (testing "only A presses Enter on an open draft"
    (let [enter {:type "key" :key "enter"}
          s (say r/initial "chat with party" "one")]
      (is (not-any? #{enter} (mapcat #(second (r/speech (say s "two") {} %)) ["three" "open bags"])))
      (is (= [enter] (second (r/button s :pad "south" true)))))))

(deftest stop-chat-ends-mode
  (is (= [] (second (r/speech (say r/initial "chat with say" "stop chat") {} "hello")))))

(deftest status-follows-state
  (let [s (say r/initial "chat with party")]
    (is (nil? (r/status r/initial)))
    (is (= "Party" (r/status s)))
    (is (= "Party (paused)" (r/status (say s "pause chat"))))
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
    (is (= [] (r/overrides chat)) "Select stays free for the push-to-talk chord")
    (testing "the D-pad is held back only while switching"
      (is (= #{"dpad_up" "dpad_down" "east" "start"}
             (set (r/overrides (say chat "switch channel")))))
      (is (= [] (r/overrides (-> chat (say "switch channel") (press :pad "east"))))))
    (testing "A and B are held back only while a line is drafted"
      (is (= #{"south" "east"} (set (r/overrides (say chat "hi"))))))))

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
    (testing "a request-side release clears the draft sent by the core"
      (is (= [] (second (r/button s :request "south" true))))
      (is (false? (:draft (first (r/button s :request "south" false))))))))

(deftest pause-stops-dictation-and-resumes
  (let [s (say r/initial "chat with party" "pause chat")]
    (is (= [] (second (r/speech s {} "hi"))))
    (is (seq (second (r/speech (say s "resume chat") {} "hi"))))))

(deftest leaving-the-game-drops-switching-and-keeps-the-draft
  (let [s (r/focus (say r/initial "chat with party" "hi" "switch channel") false)]
    (is (false? (:switching s)))
    (is (true? (:draft s)))
    (is (= "party" (:channel s)))
    (is (= [{:type "key" :key "slash"} {:type "text" :text " again"}] (second (r/speech s {} "again"))))))

(deftest mode-changes-leave-the-open-box-alone
  (let [s (say r/initial "chat with party" "hi")]
    (doseq [text ["stop chat" "chat with guild" "pause chat" "resume chat"
                  "switch channel"]]
      (let [[next actions] (r/speech s {} text)]
        (is (= [] actions))
        (is (:draft next))))
    ;; A channel change takes effect after the player sends or cancels this line.
    (is (= [{:type "key" :key "slash"} {:type "text" :text " yo"}]
           (second (r/speech (say s "chat with guild") {} "yo"))))
    (is (= [] (second (r/speech s {} "open bags"))))))

(deftest voice-actions-use-bindings
  (is (= [{:type "key" :key "b"}] (second (r/speech r/initial {} "open bags"))))
  (is (= [{:type "key" :key "m"}] (second (r/speech r/initial {} "Open the map."))))
  (is (= [{:type "key" :key "j" :mods ["ctrl"]}]
         (second (r/speech r/initial {"B" "NONE" "CTRL-J" "OPENALLBAGS"} "open bags")))))

(def ^:private safety-events
  (concat
   (for [text ["hello" "more words" "" "!!!" "switch channel" "switch channels"
               "change channel" "which channel" "stop chat" "stop chatting" "end chat"
               "chat off" "chat mode off" "pause chat" "pause dictation"
               "resume chat" "resume dictation" "open spells" "open spell book"
               "open spellbook" "open bags" "open bag" "open map" "open the map"
               "chat with banana" "send" "enter"]]
     [:speech text])
   (for [channel r/channels prefix ["chat with " "chat to " "chat in " "chat "]]
     [:speech (str prefix channel)])
   (for [via [:pad :request]
         button ["south" "east" "start" "select" "north" "west"
                 "dpad_up" "dpad_down" "dpad_left" "dpad_right" "unknown"]
         pressed? [true false]]
     [:button via button pressed?])
   [[:focus true] [:focus false]]))

(defn- safety-step [state [kind & args]]
  (case kind
    :speech (r/speech state {} (first args))
    :button (apply r/button state args)
    :focus [(r/focus state (first args)) []]))

(deftest every-reachable-router-state-requires-a-to-send
  ;; Explore to a fixed point, rather than limiting the length of sequences.
  ;; Covers every reachable combination of channel, paused, switching and draft.
  (loop [seen #{} pending #{r/initial}]
    (when (seq pending)
      (let [transitions (for [state pending event safety-events]
                          [state event (safety-step state event)])]
        (doseq [[state event [_ actions]] transitions]
          (is (or (= [:button :pad "south" true] event)
                    (not-any? #{ {:type "key" :key "enter"}} actions))
                (str "unexpected send from " state " via " event)))
        (let [visited (into seen pending)
              next-states (set (map #(first (nth % 2)) transitions))]
          (recur visited (reduce disj next-states visited)))))))

(deftest manual-chat-is-untouched-while-chat-mode-is-off
  ;; The router has no observation of the manually opened box. In its initial
  ;; state, ordinary dictation and non-A buttons must leave that box alone.
  (doseq [event (concat [[:speech "hello"] [:speech "more words"]]
                       (for [via [:pad :request] button ["east" "start"] p [true false]]
                         [:button via button p])
                       [[:focus false] [:focus true]])]
    (is (= [] (second (safety-step r/initial event))) (str event))))


(deftest draft-opening-respects-bindings-and-never-sends
  (let [s (say r/initial "chat with party")]
    (is (= [{:type "key" :key "j" :mods ["ctrl"]}
            {:type "text" :text "p hello"}]
           (second (r/speech s {"CTRL-J" "OPENCHATSLASH"} "hello"))))
    (doseq [bound [{"/" "NONE"} {"ENTER" "OPENCHATSLASH"}
                   {"SHIFT-ENTER" "OPENCHATSLASH"}]]
      (is (= [s []] (r/speech s bound "hello"))))
    (doseq [action ["OPENALLBAGS" "TOGGLEWORLDMAP" "TOGGLESPELLBOOK"]
            key ["ENTER" "SHIFT-ENTER"]
            text ["open bags" "open map" "open spells"]]
      (is (not-any? #(= "enter" (:key %))
                    (second (r/speech r/initial {key action} text)))))))


(deftest speech-never-escapes-even-through-rebound-keys
  (doseq [channel [nil "party"]
          draft [false true]
          switching [false true]
          paused [false true]
          text ["open bags" "open map" "open spells" "hello" "stop chat"
                "chat with guild"]
          key ["ESCAPE" "SHIFT-ESCAPE"]
          action ["OPENALLBAGS" "TOGGLEWORLDMAP" "TOGGLESPELLBOOK" "OPENCHATSLASH"]]
    (let [state {:channel channel :draft draft :switching switching :paused paused}]
      (is (not-any? #(= "escape" (:key %))
                    (second (r/speech state {key action} text)))))))

(deftest dictation-after-core-sends-reopens-chat
  (let [draft (say r/initial "chat with party" "one" "switch channel")
        [sent actions] (r/button draft :request "south" false)]
    (is (= [] actions))
    (is (false? (:draft sent)))
    (is (false? (:switching sent)))
    (is (= [{:type "key" :key "slash"} {:type "text" :text "p two"}]
           (second (r/speech sent {} "two"))))))

(deftest stale-draft-always-opens-chat-before-text
  ;; A closed box and an open box look identical to the router.
  (let [stale (say r/initial "chat with party" "one")]
    (doseq [state [stale (r/focus stale false)]
            bound [{} {"CTRL-J" "OPENCHATSLASH"}]]
      (is (= [(if (empty? bound)
                {:type "key" :key "slash"}
                {:type "key" :key "j" :mods ["ctrl"]})
              {:type "text" :text " two"}]
             (second (r/speech state bound "two")))))
    (doseq [bound [{"/" "NONE"} {"ENTER" "OPENCHATSLASH"}
                  {"ESCAPE" "OPENCHATSLASH"}]]
      (is (= [stale []] (r/speech stale bound "two"))))))

(deftest screenshot-uses-one-key-and-preserves-state
  (doseq [phrase ["screenshot" "take a screenshot" "take screenshot"]
          state [r/initial (say r/initial "chat with party" "a draft")]
          [cache key] [["bind CTRL-F5 SCREENSHOT" {:type "key" :key "f5" :mods ["ctrl"]}]
                       ["" {:type "key" :key "print_screen"}]]]
    (is (= [state [key]] (r/speech state (b/parse cache) phrase)))))
