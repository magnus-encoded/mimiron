(ns mimiron.router
  "The chat router as pure functions: state in, state and actions out."
  (:require [clojure.string :as str]
            [mimiron.bindings :as bindings]))

(def channels ["say" "party" "guild" "raid" "battleground"])

(def ^:private slash
  {"say" "/s" "party" "/p" "guild" "/g" "raid" "/raid" "battleground" "/bg"})

;; Likely speech-to-text mishearings, mapped to the channel meant.
(def ^:private channel-words
  {"say" "say" "sigh" "say"
   "party" "party" "potty" "party" "parity" "party" "pardee" "party"
   "guild" "guild" "guilt" "guild" "gild" "guild"
   "raid" "raid" "rate" "raid" "red" "raid"
   "battleground" "battleground" "battle ground" "battleground"
   "battlegrounds" "battleground" "bg" "battleground"})

(def ^:private chat-prefixes ["chat with " "chat to " "chat in " "chat "])
(def ^:private switch-phrases #{"switch channel" "switch channels" "change channel" "which channel"})
(def ^:private stop-phrases #{"stop chat" "stop chatting" "end chat" "chat off" "chat mode off"})
(def ^:private resume-phrases #{"resume chat" "resume dictation"})

(def ^:private voice-actions
  {"open spells" "TOGGLESPELLBOOK" "open spell book" "TOGGLESPELLBOOK"
   "open spellbook" "TOGGLESPELLBOOK"
   "open bags" "OPENALLBAGS" "open bag" "OPENALLBAGS"
   "open map" "TOGGLEWORLDMAP" "open the map" "TOGGLEWORLDMAP"})

(def initial {:channel nil :paused false :switching false :draft false})

(def ^:private enter {:type "key" :key "enter"})
(def ^:private escape {:type "key" :key "escape"})

(defn normalize [text]
  (->> (str/split (str/replace (str/lower-case text) #"[^a-z0-9' ]" " ") #"\s+")
       (remove str/blank?)
       (str/join " ")))

(defn- chosen-channel [said]
  (some #(when (str/starts-with? said %) (channel-words (subs said (count %))))
        chat-prefixes))

(defn speech
  "Handles one utterance. Returns [state actions]."
  [{:keys [channel paused] :as state} bound text]
  (let [said (normalize text)
        ;; An open draft is cancelled before the mode changes, so a later
        ;; Enter can never post it.
        cancel (if (:draft state) [escape] [])]
    (cond
      (and channel (switch-phrases said)) [(assoc state :switching true) []]
      (stop-phrases said) [initial cancel]
      (and channel (resume-phrases said)) [(assoc state :paused false) []]
      (chosen-channel said) [(assoc state :channel (chosen-channel said)
                                    :paused false :switching false :draft false) cancel]
      ;; Keys pressed into an open chat box would only type letters there.
      (voice-actions said) [state (if-let [k (and (not (:draft state))
                                                  (bindings/key-for bound (voice-actions said)))]
                                    [k] [])]
      ;; Speech only ever drafts. A second utterance extends the open draft:
      ;; another Enter there would post the first one without A.
      (and channel (not paused) (seq said))
      [(assoc state :draft true)
       (if (:draft state)
         [{:type "text" :text (str " " (str/trim text))}]
         [enter {:type "text" :text (str (slash channel) " " (str/trim text))}])]
      :else [state []])))

(defn- step [channel delta]
  (let [i (.indexOf ^java.util.List channels channel)]
    (channels (mod (+ i delta) (count channels)))))

(defn button
  "Handles one button press or release. `via` is :pad for a virtual-pad override,
  where the plugin presses Enter itself, or :request for a button request, where
  the core sends Enter on South. Returns [state actions]."
  [{:keys [channel switching draft] :as state} via name pressed?]
  (cond
    (and switching pressed? (#{"dpad_up" "dpad_down"} name))
    [(update state :channel step (if (= name "dpad_up") -1 1)) []]

    (and switching pressed? (#{"east" "start"} name)) [(assoc state :switching false) []]
    (and channel pressed? (= name "select")) [(update state :paused not) []]
    (and draft pressed? (= name "east")) [(assoc state :draft false) [escape]]
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
  [{:keys [channel switching draft]}]
  (vec (distinct (concat (when channel ["select"])
                         (when switching ["dpad_up" "dpad_down" "east" "start"])
                         (when draft ["south" "east"])))))
