(ns mimiron.router
  "The chat router as pure functions: state in, state and actions out."
  (:require [clojure.string :as str]
            [mimiron.bindings :as bindings]
            [mimiron.roster :as roster]
            [mimiron.sounds :as sounds]))

(def channels ["say" "party" "guild" "raid" "battleground" "yell" "general" "reply"])

(def ^:private slash
  {"say" "/s" "party" "/p" "guild" "/g" "raid" "/raid" "battleground" "/bg" "yell" "/y"
   "general" "/1" "reply" "/r"})

;; Likely speech-to-text mishearings, mapped to the channel meant.
(def ^:private channel-words
  {"say" "say" "sigh" "say" "c" "say" "see" "say" "sea" "say"
   "party" "party" "potty" "party" "parity" "party" "pardee" "party"
   "guild" "guild" "guilt" "guild" "gild" "guild"
   "yell" "yell" "yelled" "yell" "yelp" "yell" "yellow" "yell"
   "raid" "raid" "rate" "raid" "red" "raid"
   "battleground" "battleground" "battle ground" "battleground"
   "battlegrounds" "battleground" "bg" "battleground"
   "general" "general" "generals" "general" "general chat" "general"
   "jeneral" "general"
   "reply" "reply" "replies" "reply" "replay" "reply"})

;; "cat with" is how the recogniser has heard "chat with".
(def ^:private chat-prefixes ["chat with " "chat to " "chat in " "cat with " "cat to " "cat in " "chat "])
(def ^:private switch-phrases #{"switch channel" "switch channels" "change channel" "which channel"})
(def ^:private stop-phrases #{"stop chat" "stop chatting" "end chat" "chat off" "chat mode off"})
(def ^:private pause-phrases #{"pause chat" "pause dictation"})
(def ^:private resume-phrases #{"resume chat" "resume dictation"})
;; Bare "reply" picks the reply channel (/r, the last whisperer), like "chat with reply".
(def ^:private reply-phrases #{"reply" "replay"})

(def ^:private voice-actions
  {"screenshot" "SCREENSHOT" "take a screenshot" "SCREENSHOT"
   "take screenshot" "SCREENSHOT"
   "open spells" "TOGGLESPELLBOOK" "open spell book" "TOGGLESPELLBOOK"
   "open spellbook" "TOGGLESPELLBOOK"
   "open bags" "OPENALLBAGS" "open bag" "OPENALLBAGS"
   "open map" "TOGGLEWORLDMAP" "open the map" "TOGGLEWORLDMAP"})

;; Whole commands a phrase drafts for A, one each. A fixed list: no emotes or
;; /invite (issue #2), nothing free-form. "mimiron" before a phrase is optional.
(def ^:private commands
  {"roll" "/roll" "roll the dice" "/roll" "roll dice" "/roll"
   "reload ui" "/reload" "reload the ui" "/reload" "reload interface" "/reload"
   "guild names on" "/mimiron guildnames on" "guildnames on" "/mimiron guildnames on"
   "guild names off" "/mimiron guildnames off" "guildnames off" "/mimiron guildnames off"
   "link on" "/mimiron link on" "game link on" "/mimiron link on"
   "link off" "/mimiron link off" "game link off" "/mimiron link off"
   "mimiron status" "/mimiron status" "mimiron probe" "/mimiron probe"
   ;; Where push-to-talk goes while voice chat is on (docs/notes/voice-chat-ptt.md).
   "push to talk voice" "/mimiron ptt voice" "ptt voice" "/mimiron ptt voice"
   "push to talk dictation" "/mimiron ptt dictation" "ptt dictation" "/mimiron ptt dictation"})

(defn- command-for [said]
  (or (commands said) (commands (str/replace said #"^mimiron " ""))))

;; :situation is the add-on's latest {:group :auction} (game link); :auction is auction mode.
(def initial {:channel nil :paused false :switching false :draft false
              :situation nil :auction false})

;; Auction mode's voice vocabulary: a stub. Each phrase will press at most one key and needs a
;; fair-play review and an owner ruling first (search, buy). Until then the mode only shows on
;; the powerline and keeps dictation out of the chat box.
(def auction-vocabulary {})

;; The channel each group makes the default.
(def ^:private situation-channels
  {:solo "say" :party "party" :raid "raid" :battleground "battleground"})

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

;; How the recogniser hears "chat" and "with" (docs/notes/mode-switch-speech.md). A heard
;; "that" or "check your" is not a trigger: a lead needs a connector and a channel after it.
(def ^:private chat-leads
  #{"chat" "chet" "chad" "chap" "chapp" "chart" "cat" "shat" "shut" "short" "check" "jaap"})
(def ^:private connectors #{"with" "wit" "was" "would" "will" "to" "in" "be" "we"})
;; Never a channel, however close it sounds ("chat with you" is a chat line).
(def ^:private not-channels
  #{"you" "ya" "yeah" "yes" "yo" "us" "me" "him" "her" "them" "someone" "somebody" "sis"})

;; Spoken words the alias map could not foresee are matched by sound (mimiron.sounds),
;; but only in the slot after a trigger, never a bare word.
(def ^:private by-sound (zipmap channels channels))

(def mode-hotwords
  "What tehuti primes dictation with in WoW: the trigger, then every channel."
  (into ["chat with"] channels))

(defn- sounds-like-a-switch
  "\"<chat-like> <connector> [the|a] <one or two words>\": the channel meant, by alias or sound."
  [said]
  (let [[lead connector & more] (str/split said #" ")
        slot (cond->> more (#{"the" "a"} (first more)) rest)]
    (when (and (chat-leads lead) (connectors connector) (<= 1 (count slot) 2))
      (let [heard (str/join " " slot)]
        (when-not (not-channels heard)
          (or (channel-for heard) (sounds/closest by-sound heard)))))))

(defn- chosen-channel [said]
  (or (some #(when (str/starts-with? said %)
               ;; "chat with the say": drop a heard article before the channel.
               (channel-for (str/replace (subs said (count %)) #"^(the|a) " "")))
            chat-prefixes)
      (sounds-like-a-switch said)))

(defn- whisper-target
  "For a \"whisper <name>\" utterance, the known name meant (nil when unknown), else ::none."
  [said names]
  (if-let [[_ heard] (re-matches #"whisper(?: to)?(?: (.*))?" said)]
    (roster/match-name names heard)
    ::none))

(defn- command [{:keys [channel target]}]
  (if (= "whisper" channel) (str "/w " target) (slash channel)))

(defn- speech-key [key]
  ;; No binding may turn speech into a send, even when chat was opened manually.
  (when-not (#{"enter" "escape"} (:key key)) key))

(defn speech
  "Handles one utterance. Returns [state actions]. `shape` restyles a yelled
  line (same words) before it is typed; by default the line is typed as heard.
  `names` (party and guild) are who \"whisper <name>\" may pick."
  ([state bound text] (speech state bound text identity))
  ([state bound text shape] (speech state bound text shape []))
  ([{:keys [channel paused] :as state} bound text shape names]
  (let [said (normalize text)
        target (whisper-target said names)
        line (fn [] (let [t (str/trim text)] (if (= "yell" channel) (shape t) t)))]
    (cond
      (and channel (switch-phrases said)) [(assoc state :switching true :auction false) []]
      ;; Mode changes cannot safely close a box: it may now belong to the player.
      ;; The situation and auction mode belong to the game, not to the chat mode.
      (stop-phrases said) [(merge initial (select-keys state [:draft :situation :auction])) []]
      (and channel (pause-phrases said)) [(assoc state :paused true) []]
      (and channel (resume-phrases said)) [(assoc state :paused false) []]
      ;; Any "whisper ..." is a mode change; an unknown name changes nothing.
      ;; Picking a channel also leaves auction mode: the player's pick wins until the next change.
      (nil? target) [state []]
      (not= ::none target) [(assoc state :channel "whisper" :target target
                                   :paused false :switching false :auction false) []]
      (reply-phrases said) [(assoc state :channel "reply" :paused false :switching false
                                   :auction false) []]
      (chosen-channel said) [(assoc state :channel (chosen-channel said)
                                    :paused false :switching false :auction false) []]
      (and (:auction state) (auction-vocabulary said)) [state []]
      ;; Screenshot can fire with a draft: PrintScreen types no character.
      ;; Other voice actions would type letters into the open chat box.
      (voice-actions said) [state (if-let [k (and (or (not (:draft state))
                                                        (= "SCREENSHOT" (voice-actions said)))
                                                  (speech-key (bindings/key-for bound (voice-actions said))))]
                                    [k] [])]
      ;; A command opens its own box and waits for A; it never joins an open draft.
      (command-for said) (if (:draft state)
                           [state []]
                           [(assoc state :draft true)
                            [enter {:type "text" :text (command-for said)}]])
      ;; Enter opens the box on every keyboard layout; "/" is layout-dependent.
      ;; With a draft the box is already open, so extending it sends no key:
      ;; Enter would send the draft. Auction mode types nothing into chat.
      (and channel (not paused) (not (:auction state)) (seq said))
      [(assoc state :draft true)
       (if (:draft state)
         [{:type "text" :text (str " " (line))}]
         [enter {:type "text" :text (str (command state) " " (line))}])]
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

(defn situation
  "Follows the game link's {:group :auction}. Joining or leaving a group makes its channel the
  default (say when solo), replacing the player's pick, but only while chat is on, and a draft
  keeps its box. The auction house opening enters auction mode; closing it leaves it, back to the
  channel underneath. Only changes count: the first situation seen is recorded. Mimiron's own
  state only, never an action."
  [state {:keys [group auction]}]
  (let [before (:situation state)
        regrouped? (and before group (not= group (:group before)))
        opened? (and auction (not (:auction before)))
        closed? (and before (not auction) (:auction before))]
    (cond-> (assoc state :situation {:group group :auction (boolean auction)})
      (and regrouped? (:channel state))
      (-> (assoc :channel (situation-channels group) :switching false) (dissoc :target))
      opened? (assoc :auction true)
      closed? (assoc :auction false))))

(defn status
  "The powerline text: auction mode, else the channel, marked while paused or switching."
  [{:keys [channel target paused switching auction]}]
  (cond
    auction "Auction"
    channel
    (str (str/capitalize channel)
         ;; The realm would crowd the powerline; the draft keeps it.
         (when (= "whisper" channel) (str " " (first (str/split target #"-" 2))))
         (cond switching " ↕" paused " (paused)"))))

(defn overrides
  "The pad buttons to keep from the game in this state."
  [{:keys [switching draft]}]
  (vec (distinct (concat (when switching ["dpad_up" "dpad_down" "east" "start"])
                         (when draft ["south" "east"])))))
