(ns mimiron.router
  "The chat router as pure functions: state in, state and actions out."
  (:require [clojure.string :as str]
            [mimiron.auction :as auction]
            [mimiron.bindings :as bindings]
            [mimiron.fill :as fill]
            [mimiron.mail :as mail]
            [mimiron.quantity :as quantity]
            [mimiron.roster :as roster]
            [mimiron.sounds :as sounds]))

(def channels
  "In D-pad order. The newer channels follow the older ones; the order is open for the owner."
  ["say" "party" "guild" "raid" "battleground" "yell" "general" "reply"
   "officer" "raid warning" "trade" "lfg" "instance"])

;; Officer, raid warning and instance are in WoW Forever's ChatTypeInfo (wow-ui-source, branch
;; forever, 1.60.1). Trade and LFG are numbered channels, assumed /2 and /4. A channel the player
;; can't use (officer without the rank, raid warning without lead or assist) still switches the
;; mode: WoW's own error answers the line.
(def ^:private slash
  {"say" "/s" "party" "/p" "guild" "/g" "raid" "/raid" "battleground" "/bg" "yell" "/y"
   "general" "/1" "reply" "/r" "officer" "/o" "raid warning" "/rw" "trade" "/2" "lfg" "/4"
   "instance" "/i"})

;; How a channel is spelt where it isn't plain lower case: on the powerline, and as a hotword
;; (Whisper writes the letters as "LFG").
(def ^:private spelt {"lfg" "LFG"})

;; Likely speech-to-text mishearings, mapped to the channel meant.
(def ^:private channel-words
  {"si" "say" "sei" "say"
   "gruppe" "party" "parti" "party" "laug" "guild"
   "say" "say" "sigh" "say" "c" "say" "see" "say" "sea" "say"
   "party" "party" "potty" "party" "parity" "party" "pardee" "party"
   "guild" "guild" "guilt" "guild" "gild" "guild"
   "yell" "yell" "yelled" "yell" "yelp" "yell" "yellow" "yell"
   "raid" "raid" "rate" "raid" "red" "raid"
   "battleground" "battleground" "battle ground" "battleground"
   "battlegrounds" "battleground" "bg" "battleground"
   "general" "general" "generals" "general" "general chat" "general"
   "jeneral" "general"
   "reply" "reply" "replies" "reply" "replay" "reply"
   "officer" "officer" "officer chat" "officer"
   "raid warning" "raid warning" "rw" "raid warning"
   "trade" "trade" "trade chat" "trade"
   "lfg" "lfg" "l f g" "lfg" "lfg chat" "lfg" "looking for group" "lfg"
   "looking for a group" "lfg"
   "instance" "instance" "instance chat" "instance"})

;; "cat with" is how the recogniser has heard "chat with".
(def ^:private chat-prefixes ["chat with " "chat to " "chat in " "cat with " "cat to " "cat in " "chat "])
(def ^:private switch-phrases #{"switch channel" "switch channels" "change channel" "which channel"})
(def ^:private stop-phrases #{"stop chat" "stop chatting" "end chat" "chat off" "chat mode off"})
(def ^:private pause-phrases #{"pause chat" "pause dictation"})
(def ^:private resume-phrases #{"resume chat" "resume dictation"})
;; Bare "reply" picks the reply channel (/r, the last whisperer), like "chat with reply".
(def ^:private reply-phrases #{"reply" "replay"})

(def voice-action-bindings
  "Phrase to the WoW binding action it presses, one key each. Panel actions are from WoW Forever's
  Bindings_Camelot.xml (wow-ui-source, branch forever); their default keys are in bindings.clj."
  {"screenshot" "SCREENSHOT" "take a screenshot" "SCREENSHOT"
   "take screenshot" "SCREENSHOT"
   "open spells" "TOGGLESPELLBOOK" "open spell book" "TOGGLESPELLBOOK"
   "open spellbook" "TOGGLESPELLBOOK"
   "open bags" "OPENALLBAGS" "open bag" "OPENALLBAGS"
   "open map" "TOGGLEWORLDMAP"
   "open character" "TOGGLECHARACTER0" "open character sheet" "TOGGLECHARACTER0"
   "open character pane" "TOGGLECHARACTER0" "open character panel" "TOGGLECHARACTER0"
   "open reputation" "TOGGLECHARACTER2" "open reputations" "TOGGLECHARACTER2"
   "open currency" "TOGGLECURRENCY" "open currencies" "TOGGLECURRENCY"
   "open honor" "TOGGLECHARACTER4" "open pvp" "TOGGLECHARACTER4" "open p v p" "TOGGLECHARACTER4"
   "open talents" "TOGGLETALENTS" "open talent" "TOGGLETALENTS" "open talent tree" "TOGGLETALENTS"
   "open professions" "TOGGLEPROFESSIONBOOK" "open profession book" "TOGGLEPROFESSIONBOOK"
   "open profession" "TOGGLEPROFESSIONBOOK"
   "open quest log" "TOGGLEQUESTLOG" "open quests" "TOGGLEQUESTLOG" "open quest" "TOGGLEQUESTLOG"
   "open guild" "TOGGLEGUILDTAB"
   "open friends" "TOGGLESOCIAL" "open friends list" "TOGGLESOCIAL" "open social" "TOGGLESOCIAL"
   "open raid" "TOGGLERAIDTAB"
   "open group finder" "TOGGLEGROUPFINDER" "open looking for group" "TOGGLEGROUPFINDER"
   "open lfg" "TOGGLEGROUPFINDER" "open l f g" "TOGGLEGROUPFINDER"
   "open collections" "TOGGLECOLLECTIONS" "open mounts" "TOGGLECOLLECTIONS"})

(defn- voice-action
  "The binding action a phrase presses; \"open the map\" and \"open my bags\" count too."
  [said]
  (voice-action-bindings (str/replace said #"^open (the|my) " "open ")))

;; Whole commands a phrase drafts for A, one each. A fixed list: no emotes or
;; /invite (issue #2), nothing free-form. "mimiron" before a phrase is optional.
(def ^:private commands
  {"roll" "/roll" "roll the dice" "/roll" "roll dice" "/roll"
   "reload ui" "/reload" "reload the ui" "/reload" "reload interface" "/reload"
   "guild names on" "/mimiron guildnames on" "guildnames on" "/mimiron guildnames on"
   "guild names off" "/mimiron guildnames off" "guildnames off" "/mimiron guildnames off"
   "link on" "/mimiron link on" "game link on" "/mimiron link on"
   "link off" "/mimiron link off" "game link off" "/mimiron link off"
   "mimiron status" "/mimiron status"
   ;; Where push-to-talk goes while voice chat is on (docs/notes/voice-chat-ptt.md).
   "push to talk voice" "/mimiron ptt voice" "ptt voice" "/mimiron ptt voice"
   "push to talk dictation" "/mimiron ptt dictation" "ptt dictation" "/mimiron ptt dictation"})

(defn- command-for [said]
  (or (commands said) (commands (str/replace said #"^mimiron " ""))))

;; :situation is the add-on's latest {:group :auction} (game link); :auction is auction mode;
;; :help shows auction mode's phrases on the powerline until the next utterance.
(def initial {:channel nil :paused false :switching false :draft false
              :situation nil :auction false :help false})

(defn- drafted
  "A whole command this utterance drafts for A, or nil: a fixed command phrase anywhere, or
  in auction mode a field phrase's `/mimiron ah` line (fair-play exception 3)."
  [said parsed]
  (or (command-for said) (auction/command parsed)))

;; The channel each group makes the default.
(def ^:private situation-channels
  {:solo "say" :party "party" :raid "raid" :battleground "battleground"})

(def ^:private enter {:type "key" :key "enter"})
(def ^:private escape {:type "key" :key "escape"})
;; Escape alone only closes the box: WoW keeps the text. B must empty it first.
(def ^:private cancel-draft
  [{:type "key" :key "a" :mods ["ctrl"]} {:type "key" :key "backspace"} escape])

(defn normalize [text]
  (->> (str/split (str/replace (str/lower-case (str/replace text #"^[\s\p{P}]+|[\s\p{P}]+$" "")) #"[^a-z0-9' ]" " ") #"\s+")
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
  #{"sjatt" "chatt" "chat" "chet" "chad" "chap" "chapp" "chart" "cat" "shat" "shut" "short" "check" "jaap"})
(def ^:private connectors #{"med" "with" "wit" "was" "would" "will" "to" "in" "be" "we"})
;; Never a channel, however close it sounds ("chat with you" is a chat line).
(def ^:private not-channels
  #{"you" "ya" "yeah" "yes" "yo" "us" "me" "him" "her" "them" "someone" "somebody" "sis"
    "trader" "deg" "dem" "meg" "oss" "ham" "henne" "han" "hun" "noen" "alle"
    "dere" "den" "det" "venner"})

;; Spoken words the alias map could not foresee are matched by sound (mimiron.sounds),
;; but only in the slot after a trigger, never a bare word, and only against channels of as
;; many words as were heard: two heard words ("out there") never make a short one ("trade").
(def ^:private by-sound
  (reduce (fn [m c] (assoc-in m [(count (str/split c #" ")) c] c)) {} channels))

(defn- closest-channel [slot]
  (sounds/closest (by-sound (count slot) {}) (str/join " " slot)))

(def mode-hotwords
  "English mode words, leaving half the hint budget for names."
  (into (mapv #(spelt % %) channels)
        ["switch channel" "chat in english" "chat in norwegian"]))

(def norwegian-mode-hotwords
  "Whole English channel commands while dictating Norwegian; at most sixteen hints."
  (into (mapv #(str "chat with " (spelt % %)) channels)
        ["switch channel" "chat in english" "chat in norwegian"]))

(defn- sounds-like-a-switch
  "\"<chat-like> <connector> [the|a] <one or two words>\": the channel meant, by alias or sound."
  [said]
  (let [[lead connector & more] (str/split said #" ")
        slot (cond->> more (#{"the" "a"} (first more)) rest)]
    (when (and (chat-leads lead) (connectors connector) (<= 1 (count slot) 2))
      (let [heard (str/join " " slot)]
        (when-not (not-channels heard)
          (or (channel-for heard) (closest-channel slot)))))))

(defn- chosen-channel [said]
  (or (some #(when (str/starts-with? said %)
               ;; "chat with the say": drop a heard article before the channel.
               (channel-for (str/replace (subs said (count %)) #"^(the|a) " "")))
            chat-prefixes)
      (sounds-like-a-switch said)))

;; Exact whole-phrase aliases keep ordinary dictation out of the command path.
(def ^:private language-phrases
  ;; normalize folds å to a space, so på is matched as "p" here.
  (into {}
        (for [lead ["chat" "chatt" "sjatt" "cat" "chet"]
              connector ["in" "i" "p" "pa"]
              [language words] [["no" ["norwegian" "norweigan" "norway" "norsk" "norskt" "bokm l"]]
                                ["en" ["english" "inglish" "engelsk" "englesk"]]]
              word words]
          [(str lead " " connector " " word) language])))

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
  ([state bound text shape names] (speech state bound text shape names false))
  ([state bound text shape names command-only?]
  (let [;; Help shows until the next utterance, whatever it is.
        {:keys [channel paused] :as state} (-> state (assoc :help false) (dissoc :notice))
        ;; Match punctuation around the prefix without rewriting field contents.
        ;; A channel command after "type" wins over filling a field. Restrict
        ;; the recogniser's extra "type" repair to that channel slot only.
        ;; Prefix punctuation ends at whitespace; punctuation after it is payload.
        raw-fill (re-matches #"(?is)^[\s\p{P}]*type(?:\p{P}*\s+(.*))?$" text)
        fill-command (some-> (second raw-fill) normalize
                             (str/replace #"^(chat (?:with|to|in) )type " "$1"))
        channel-command? (and raw-fill (seq fill-command) (or (chosen-channel fill-command)
                                          (reply-phrases fill-command)
                                          (not= ::none (whisper-target fill-command names))))
        fill (when-not channel-command? raw-fill)
        words (some-> (second fill) str/trim fill/prepare)
        said (if channel-command? fill-command (normalize text))
        mail-fill (when (true? (get-in state [:windows :mail])) (mail/draft text))
        quantity-window (cond (str/starts-with? said "trade") :trade
                              (str/starts-with? said "vendor") :merchant
                              :else :stack-split)
        quantity-fill (when (true? (get-in state [:windows quantity-window]))
                        (quantity/draft text))
        fill-line (or (:line quantity-fill) (:line mail-fill)
                      (when (and fill (seq words)) (str "/mimiron fill " words)))
        quantity-open (bindings/key-for bound "OPENCHATSLASH")
        ;; Auction phrases count only in auction mode; elsewhere the same words are chat.
        phrase (when (:auction state) (auction/parse said))
        target (whisper-target said names)
        line (fn [] (let [t (str/trim text)] (if (= "yell" channel) (shape t) t)))]
    (cond
      ;; WoW's command chat has a 255-byte budget, including the slash prefix.
      (and fill-line (> (alength (.getBytes ^String fill-line "UTF-8")) 255))
      [(assoc state :notice "too long") []]
      (and quantity-fill (:line quantity-fill) (not (:draft state))
           quantity-open (not (#{"enter" "escape"} (:key quantity-open))))
      [(assoc state :draft true)
       [quantity-open {:type "text" :text (subs (:line quantity-fill) 1)}]]
      (and (:line mail-fill) (not (:draft state))
           (true? (get-in state [:windows :mail])))
      [(assoc state :draft true) [enter {:type "text" :text (:line mail-fill)}]]
      (and fill (not (:draft state)) (seq words)
           (not (re-find #"[\r\n]" text)))
      [(assoc state :draft true)
       [enter {:type "text" :text (str "/mimiron fill " words)}]]
      ;; A recognized fill is consumed even when invalid, unsafe, or a draft is
      ;; already open. Never append its amount or command text to a chat draft.
      (or fill mail-fill quantity-fill)
      [(assoc state :notice (cond fill "text fill refused"
                                 mail-fill "mail fill refused"
                                 :else "quantity fill refused")) []]
      (language-phrases said)
      [(if channel
         (-> state
             (assoc-in [:language-overrides channel] (language-phrases said))
             (assoc :notice (str (spelt channel (str/capitalize channel)) ": "
                                 (if (= "no" (language-phrases said)) "Norwegian" "English"))))
         (assoc state :notice "Pick a channel first"))
       []]
      (and channel (switch-phrases said)) [(assoc state :switching true :auction false) []]
      ;; Mode changes cannot safely close a box: it may now belong to the player.
      ;; The situation and auction mode belong to the game, not to the chat mode.
      (stop-phrases said) [(merge initial (select-keys state [:draft :situation :auction :language-overrides])) []]
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
      ;; Mimiron's own state only: the house stays open, and only its next opening re-enters.
      (= :leave (:id phrase)) [(assoc state :auction false) []]
      (= :help (:id phrase)) [(assoc state :help true) []]
      ;; Screenshot can fire with a draft: PrintScreen types no character.
      ;; Other voice actions would type letters into the open chat box.
      (voice-action said)
      (let [k (and (or (not (:draft state)) (= "SCREENSHOT" (voice-action said)))
                   (speech-key (bindings/key-for bound (voice-action said))))]
        (cond
          k [state [k]]
          ;; An open draft blocks panels on purpose; only a missing key is worth telling.
          (:draft state) [state []]
          :else [(assoc state :notice (str "No key bound: " (str/replace said #"^open (the |my )?" "")))
                 []]))
      ;; A command opens its own box and waits for A; it never joins an open draft.
      (drafted said phrase) (if (:draft state)
                              [state []]
                              [(assoc state :draft true)
                               [enter {:type "text" :text (drafted said phrase)}]])
      ;; Enter opens the box on every keyboard layout; "/" is layout-dependent.
      ;; With a draft the box is already open, so extending it sends no key:
      ;; Enter would send the draft. Auction mode types nothing into chat.
      ;; Command normalization drops non-ASCII letters; dictation eligibility
      ;; must use the original text so those utterances reach validation too.
      (and (not command-only?) channel (not paused) (not (:auction state))
           (not (str/blank? text)))
      ;; Reject unexpected scripts before opening or extending chat. Keep only
      ;; a fixed notice, never the rejected transcript, in state or logs.
      (if (re-matches #"[\p{IsLatin}\p{P}\p{Zs}\x20-\x7E\t]*" text)
        [(assoc state :draft true)
         (if (:draft state)
           [{:type "text" :text (str " " (line))}]
           [enter {:type "text" :text (str (command state) " " (line))}])]
        [(assoc state :notice "didn't catch that, say it again") []])
      :else [state []]))))

(defn- step [channel delta]
  (let [i (.indexOf ^java.util.List channels channel)]
    (channels (mod (+ i delta) (count channels)))))

(declare button*)

(defn button
  "Handles one button press or release; clears any no-key notice. See `button*`."
  [state via name pressed?]
  (button* (dissoc state :notice) via name pressed?))

(defn- button*
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
  "Follows the game link's {:group :auction}. Joining a group makes its channel the default only
  while no channel is chosen (chat off); a chosen channel never moves with the group. The auction house opening enters auction mode; closing it leaves it, back to the
  channel underneath. Only changes count: the first situation seen is recorded. Mimiron's own
  state only, never an action."
  [state {:keys [group auction]}]
  (let [before (:situation state)
        regrouped? (and before group (not= group (:group before)))
        opened? (and auction (not (:auction before)))
        closed? (and before (not auction) (:auction before))]
    (cond-> (assoc state :situation {:group group :auction (boolean auction)})
      ;; Owner ruling (#12 Q1): a chosen channel only moves by a manual switch.
      (and regrouped? (nil? (:channel state)) (not= :solo group))
      (assoc :channel (situation-channels group))
      opened? (assoc :auction true :help false)
      closed? (assoc :auction false :help false))))

(defn status
  "The powerline text: auction mode (its phrases after \"auction help\"), else the channel,
  marked while paused or switching."
  [{:keys [channel target paused switching auction help notice]}]
  (cond
    (and auction help) auction/help-text
    notice notice
    auction "Auction"
    channel
    (str (spelt channel (str/capitalize channel))
         ;; The realm would crowd the powerline; the draft keeps it.
         (when (= "whisper" channel) (str " " (first (str/split target #"-" 2))))
         (cond switching " ↕" paused " (paused)"))))

(defn overrides
  "The pad buttons to keep from the game in this state."
  [{:keys [switching draft]}]
  (vec (distinct (concat (when switching ["dpad_up" "dpad_down" "east" "start"])
                         (when draft ["south" "east"])))))
