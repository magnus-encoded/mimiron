(ns mimiron.router
  "The chat router as pure functions: state in, state and actions out."
  (:require [clojure.string :as str]
            [mimiron.bindings :as bindings]))

(def channels ["say" "party" "guild" "raid" "battleground" "yell" "general"])

(def ^:private slash
  {"say" "/s" "party" "/p" "guild" "/g" "raid" "/raid" "battleground" "/bg" "yell" "/y"
   "general" "/1"})

;; Likely speech-to-text mishearings, mapped to the channel meant.
(def ^:private channel-words
  {"say" "say" "sigh" "say"
   "party" "party" "potty" "party" "parity" "party" "pardee" "party"
   "guild" "guild" "guilt" "guild" "gild" "guild"
   "yell" "yell" "yelled" "yell" "yelp" "yell" "yellow" "yell"
   "raid" "raid" "rate" "raid" "red" "raid"
   "battleground" "battleground" "battle ground" "battleground"
   "battlegrounds" "battleground" "bg" "battleground"
   "general" "general" "generals" "general" "general chat" "general"
   "jeneral" "general"})

;; "cat with" is how the recogniser has heard "chat with".
(def ^:private chat-prefixes ["chat with " "chat to " "chat in " "cat with " "cat to " "cat in " "chat "])
(def ^:private switch-phrases #{"switch channel" "switch channels" "change channel" "which channel"})
(def ^:private stop-phrases #{"stop chat" "stop chatting" "end chat" "chat off" "chat mode off"})
(def ^:private pause-phrases #{"pause chat" "pause dictation"})
(def ^:private resume-phrases #{"resume chat" "resume dictation"})

(def ^:private voice-actions
  {"screenshot" "SCREENSHOT" "take a screenshot" "SCREENSHOT"
   "take screenshot" "SCREENSHOT"
   "open spells" "TOGGLESPELLBOOK" "open spell book" "TOGGLESPELLBOOK"
   "open spellbook" "TOGGLESPELLBOOK"
   "open bags" "OPENALLBAGS" "open bag" "OPENALLBAGS"
   "open map" "TOGGLEWORLDMAP" "open the map" "TOGGLEWORLDMAP"})

(def initial {:channel nil :paused false :switching false :draft false})

(def ^:private enter {:type "key" :key "enter"})
(def ^:private escape {:type "key" :key "escape"})
;; Escape alone only closes the box: WoW keeps the text. B must empty it first.
(def ^:private cancel-draft
  [{:type "key" :key "a" :mods ["ctrl"]} {:type "key" :key "backspace"} escape])

(defn normalize [text]
  (->> (str/split (str/replace (str/lower-case text) #"[^a-z0-9' ]" " ") #"\s+")
       (remove str/blank?)
       (str/join " ")))

(defn- channel-for
  "A channel word, tolerating a spoken plural or possessive (\"guilds\", \"guild's\")."
  [word]
  (or (channel-words word)
      (channel-words (str/replace word #"'?s$" ""))
      ({"parties" "party"} word)))

(defn- chosen-channel [said]
  (some #(when (str/starts-with? said %)
           ;; "chat with the say": drop a heard article before the channel.
           (channel-for (str/replace (subs said (count %)) #"^(the|a) " "")))
        chat-prefixes))

(defn- speech-key [key]
  ;; No binding may turn speech into a send, even when chat was opened manually.
  (when-not (#{"enter" "escape"} (:key key)) key))

(defn speech
  "Handles one utterance. Returns [state actions]. `shape` restyles a yelled
  line (same words) before it is typed; by default the line is typed as heard."
  ([state bound text] (speech state bound text identity))
  ([{:keys [channel paused] :as state} bound text shape]
  (let [said (normalize text)
        line (fn [] (let [t (str/trim text)] (if (= "yell" channel) (shape t) t)))]
    (cond
      (and channel (switch-phrases said)) [(assoc state :switching true) []]
      ;; Mode changes cannot safely close a box: it may now belong to the player.
      (stop-phrases said) [(assoc initial :draft (:draft state)) []]
      (and channel (pause-phrases said)) [(assoc state :paused true) []]
      (and channel (resume-phrases said)) [(assoc state :paused false) []]
      (chosen-channel said) [(assoc state :channel (chosen-channel said)
                                    :paused false :switching false) []]
      ;; Screenshot can fire with a draft: PrintScreen types no character.
      ;; Other voice actions would type letters into the open chat box.
      (voice-actions said) [state (if-let [k (and (or (not (:draft state))
                                                        (= "SCREENSHOT" (voice-actions said)))
                                                  (speech-key (bindings/key-for bound (voice-actions said))))]
                                    [k] [])]
      ;; Enter opens the box on every keyboard layout; "/" is layout-dependent.
      ;; With a draft the box is already open, so extending it sends no key:
      ;; Enter would send the draft.
      (and channel (not paused) (seq said))
      [(assoc state :draft true)
       (if (:draft state)
         [{:type "text" :text (str " " (line))}]
         [enter {:type "text" :text (str (slash channel) " " (line))}])]
      :else [state []]))))

(defn- step [channel delta]
  (let [i (.indexOf ^java.util.List channels channel)]
    (channels (mod (+ i delta) (count channels)))))

(defn button
  "Handles one button press or release. `via` is :pad for a virtual-pad override,
  where the plugin presses Enter itself, or :request for a button request.
  The core sends Enter on request-side South; release clears its draft. Returns [state actions]."
  [{:keys [channel switching draft] :as state} via name pressed?]
  (cond
    (and switching pressed? (#{"dpad_up" "dpad_down"} name))
    [(update state :channel step (if (= name "dpad_up") -1 1)) []]

    (and switching pressed? (#{"east" "start"} name)) [(assoc state :switching false) []]
    (and draft pressed? (= name "east")) [(assoc state :draft false) cancel-draft]
    (and draft pressed? (= name "south") (= via :pad)) [(assoc state :draft false :switching false) [enter]]
    (and draft (not pressed?) (= name "south") (= via :request)) [(assoc state :draft false :switching false) []]
    :else [state []]))

(defn focus
  "Leaving the game ends switching. A draft stays: WoW keeps its chat box open,
  so the next utterance must extend it, not press Enter on it."
  [state in-game?]
  (if in-game? state (assoc state :switching false)))

(defn status
  "The powerline text: the channel, marked while paused or switching."
  [{:keys [channel paused switching]}]
  (when channel
    (str (str/capitalize channel) (cond switching " ↕" paused " (paused)"))))

(defn overrides
  "The pad buttons to keep from the game in this state."
  [{:keys [switching draft]}]
  (vec (distinct (concat (when switching ["dpad_up" "dpad_down" "east" "start"])
                         (when draft ["south" "east"])))))
