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

(deftest heard-variants-of-the-trigger-and-channel
  (doseq [[said channel] [["cat with yells" "yell"] ["cat with yell" "yell"]
                          ["cat in party" "party"] ["chat with yelp" "yell"] ["chat-with-yellow" "yell"]
                          ["Chat with yell." "yell"] ["chat with the say" "say"]
                          ["chat with a party" "party"]]]
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
    (is (= [{:type "key" :key "enter"} {:type "text" :text "/g one"}]
           (second (r/speech s {} "one"))))
    (is (= [{:type "key" :key "enter"} {:type "text" :text "/g two"}]
           (second (r/speech (-> s (say "one") (press :pad "south")) {} "two"))))))

(deftest speech-never-posts-without-a
  (testing "a second utterance extends the draft instead of pressing Enter"
    (let [s (say r/initial "chat with party" "background talk")]
      (is (= [{:type "text" :text " more talk"}] (second (r/speech s {} "more talk"))))))
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
    (is (= "reply" (:channel (press (say r/initial "chat with say" "switch channel") :pad "dpad_up"))))
    (is (= "yell" (:channel (-> s (press :pad "dpad_up") (press :pad "dpad_up") (press :pad "dpad_up") (press :pad "dpad_up")))))))

(deftest general-opens-chat-with-the-numbered-channel
  (doseq [said ["chat with general" "chat-with-general" "chat with general chat"]]
    (let [s (say r/initial said)]
      (is (= "general" (:channel s)) said)
      (is (= [{:type "key" :key "enter"} {:type "text" :text "/1 hi"}]
             (second (r/speech s {} "hi")))))))

(deftest yell-opens-chat-with-the-yell-command
  (let [s (say r/initial "chat with yell")]
    (is (= "yell" (:channel s)))
    (is (= [{:type "key" :key "enter"} {:type "text" :text "/y look out"}]
           (second (r/speech s {} "look out"))))))

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
    (testing "Escape only closes the box, so B empties it first"
      (is (= [{:type "key" :key "a" :mods ["ctrl"]} {:type "key" :key "backspace"}
              {:type "key" :key "escape"}]
             (second (r/button s :pad "east" true)))))
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
    (is (= [{:type "text" :text " again"}] (second (r/speech s {} "again"))))))

(deftest mode-changes-leave-the-open-box-alone
  (let [s (say r/initial "chat with party" "hi")]
    (doseq [text ["stop chat" "chat with guild" "pause chat" "resume chat"
                  "switch channel"]]
      (let [[next actions] (r/speech s {} text)]
        (is (= [] actions))
        (is (:draft next))))
    ;; A channel change takes effect after the player sends or cancels this line.
    (is (= [{:type "text" :text " yo"}]
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
               "chat with banana" "send" "enter" "reply" "whisper arthas" "whisper nobody" "roll" "guild names on" "mimiron probe"]]
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
    :speech (r/speech state {} (first args) identity ["Arthas"])
    :button (apply r/button state args)
    :focus [(r/focus state (first args)) []]))

(def ^:private enter-key {:type "key" :key "enter"})

(deftest every-reachable-router-state-requires-a-to-send
  ;; Explore to a fixed point, rather than limiting the length of sequences.
  ;; Covers every reachable combination of channel, paused, switching and draft.
  (loop [seen #{} pending #{r/initial}]
    (when (seq pending)
      (let [transitions (for [state pending event safety-events]
                          [state event (safety-step state event)])]
        (doseq [[state event [_ actions]] transitions]
          ;; Speech may press Enter only to open the box: first action, no draft.
          (is (or (= [:button :pad "south" true] event)
                  (not-any? #{ {:type "key" :key "enter"}} (if (and (= :speech (first event))
                                                                   (not (:draft state))
                                                                   (= enter-key (first actions)))
                                                            (rest actions) actions)))
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


(deftest opening-chat-uses-enter-not-the-slash-binding
  ;; Enter opens the box on every layout; the slash key does not.
  (let [s (say r/initial "chat with party")]
    (doseq [bound [{} {"CTRL-J" "OPENCHATSLASH"} {"/" "NONE"}]]
      (is (= [{:type "key" :key "enter"} {:type "text" :text "/p hello"}]
             (second (r/speech s bound "hello")))))
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
    (is (= [{:type "key" :key "enter"} {:type "text" :text "/p two"}]
           (second (r/speech sent {} "two"))))))

(deftest draft-extends-with-text-only
  ;; With a draft the box is taken to be open: Enter would send it.
  (let [draft (say r/initial "chat with party" "one")]
    (doseq [state [draft (r/focus draft false)]]
      (is (= [{:type "text" :text " two"}] (second (r/speech state {} "two")))))))

(deftest screenshot-uses-one-key-and-preserves-state
  (doseq [phrase ["screenshot" "take a screenshot" "take screenshot"]
          state [r/initial (say r/initial "chat with party" "a draft")]
          [cache key] [["bind CTRL-F5 SCREENSHOT" {:type "key" :key "f5" :mods ["ctrl"]}]
                       ["" {:type "key" :key "print_screen"}]]]
    (is (= [state [key]] (r/speech state (b/parse cache) phrase)))))

(def ^:private names ["Arthas" "Thrall-Silvermoon"])

(defn- hear [state & texts]
  (reduce (fn [s t] (first (r/speech s {} t identity names))) state texts))

(deftest whisper-drafts-to-a-known-name
  (doseq [[said target] [["whisper arthas" "Arthas"] ["Whisper to Arthas." "Arthas"]
                         ["whisper thrall" "Thrall-Silvermoon"]]]
    (let [[s actions] (r/speech r/initial {} said identity names)]
      (is (= [] actions) said)
      (is (= (str "/w " target " on my way")
             (:text (second (second (r/speech s {} "on my way" identity names))))) said)))
  (is (= "Whisper Thrall" (r/status (hear r/initial "whisper thrall"))) "status drops the realm")
  (testing "the target is sticky, and another channel ends it"
    (let [s (-> r/initial (hear "whisper arthas" "one") (press :pad "south"))]
      (is (= [{:type "key" :key "enter"} {:type "text" :text "/w Arthas two"}]
             (second (r/speech s {} "two" identity names))))
      (is (= [{:type "key" :key "enter"} {:type "text" :text "/g two"}]
             (second (r/speech (hear s "chat with guild") {} "two" identity names)))))))

(deftest whisper-to-an-unknown-name-does-nothing
  (let [s (hear r/initial "chat with guild")]
    (doseq [said ["whisper nobody" "whisper" "whisper arthas hello"]]
      (is (= [s []] (r/speech s {} said identity names)) said))
    (is (= [s []] (r/speech s {} "whisper arthas")) "no names known")))

(deftest whisper-leaves-an-open-draft-alone
  (let [s (hear r/initial "chat with party" "hi")
        [next actions] (r/speech s {} "whisper arthas" identity names)]
    (is (= [] actions))
    (is (:draft next))
    (is (= [{:type "text" :text " yo"}] (second (r/speech next {} "yo" identity names))))))

(deftest reply-drafts-to-the-last-whisperer
  (doseq [said ["reply" "Reply." "chat with reply" "chat with replies"]]
    (let [s (say r/initial said)]
      (is (= "reply" (:channel s)) said)
      (is (= "Reply" (r/status s)))
      (is (= [{:type "key" :key "enter"} {:type "text" :text "/r on my way"}]
             (second (r/speech s {} "on my way")))))))

(deftest a-command-phrase-drafts-one-whole-command-for-a
  (doseq [[said command] [["roll" "/roll"] ["Roll the dice." "/roll"]
                          ["reload UI" "/reload"]
                          ["guild names on" "/mimiron guildnames on"]
                          ["Mimiron guild names off" "/mimiron guildnames off"]
                          ["game link off" "/mimiron link off"] ["link on" "/mimiron link on"]
                          ["Mimiron status" "/mimiron status"] ["mimiron probe" "/mimiron probe"]]
          state [r/initial (say r/initial "chat with guild") (say r/initial "chat with party" "pause chat")]]
    (let [[s actions] (r/speech state {} said)]
      (is (= [{:type "key" :key "enter"} {:type "text" :text command}] actions) said)
      (is (:draft s))
      (is (= (:channel state) (:channel s)) "the channel stays")
      (is (= [{:type "key" :key "enter"}] (second (r/button s :pad "south" true))) "A sends it")))
  (testing "nothing joins an open draft"
    (is (= [] (second (r/speech (say r/initial "chat with guild" "hi") {} "roll")))))
  (testing "no emotes, invites or free-form commands"
    (doseq [said ["dance" "invite arthas" "slash console gamepad" "wave"]]
      (is (= [] (second (r/speech r/initial {} said))) said))))

(deftest push-to-talk-setting-is-drafted-for-a
  (doseq [[said cmd] [["push to talk dictation" "/mimiron ptt dictation"]
                      ["mimiron push to talk voice" "/mimiron ptt voice"]
                      ["ptt voice" "/mimiron ptt voice"]]]
    (is (= [{:type "key" :key "enter"} {:type "text" :text cmd}]
           (second (r/speech r/initial {} said)))
        said)))

;; Heard forms from the synthetic-speech evaluation (docs/notes/mode-switch-speech.md):
;; the trigger mostly survives, the short channel word does not.
(deftest a-misheard-channel-word-is-matched-by-sound
  (doseq [[said channel] [["Chat with gil." "guild"] ["chat with gill" "guild"] ["chat with gold" "guild"]
                          ["chat with killed" "guild"] ["chat with y'all" "yell"] ["chat with yale" "yell"]
                          ["chat with yield" "yell"] ["chat with yellen" "yell"] ["chat with ray" "raid"]
                          ["chat with rage" "raid"] ["chat with same" "say"] ["chat with safe" "say"]
                          ["Chat with C." "say"] ["chat with sick" "say"] ["chat with battleround" "battleground"]
                          ["chat with part" "party"] ["chat with replu" "reply"]]]
    (is (= channel (:channel (say r/initial said))) said)))

(deftest a-misheard-trigger-still-switches
  (doseq [[said channel] [["Check with same." "say"] ["chat would say" "say"] ["chat was sick" "say"]
                          ["chap would say" "say"] ["shut with part" "party"] ["chart with gold" "guild"]
                          ["chet with general" "general"] ["short with general" "general"]
                          ["shat with battleground" "battleground"] ["cat with gill" "guild"]]]
    (is (= channel (:channel (say r/initial said))) said)))

(deftest a-chat-line-that-merely-sounds-close-stays-a-line
  (doseq [said ["that was safe" "chat with you" "chat with someone" "chat with us"
                "check with the healer" "chat later" "can we chat" "shout if you need help"
                "chat with raid back" "red dragon on the left" "chat with the tank" "chat with me"
                "talk to me roy" "check your bags" "shout to raid"]]
    (let [[after actions] (r/speech (assoc r/initial :channel "say") {} said)]
      (is (= "say" (:channel after)) said)
      (is (seq actions) (str said " is typed as a line")))))

(deftest the-mode-hotwords-are-the-trigger-and-every-channel
  (is (vector? r/mode-hotwords))
  (is (= "chat with" (first r/mode-hotwords)))
  (is (= (set r/channels) (set (rest r/mode-hotwords)))))
