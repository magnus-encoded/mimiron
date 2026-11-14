(ns mimiron.voice-actions-test
  "Voice opens the game's panels: one phrase, one key the player bound (or WoW's default key
  while it isn't rebound). docs/notes/playability-gaps.md G-bindings."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [mimiron.bindings :as b]
            [mimiron.router :as r]))

(def ^:private panels
  "Phrase, WoW binding action, and the key it presses with no bindings read (nil: no default)."
  [["open character" "TOGGLECHARACTER0" "c"]
   ["open character sheet" "TOGGLECHARACTER0" "c"]
   ["open reputation" "TOGGLECHARACTER2" "u"]
   ["open currency" "TOGGLECURRENCY" nil]
   ["open honor" "TOGGLECHARACTER4" "h"]
   ["open pvp" "TOGGLECHARACTER4" "h"]
   ["open talents" "TOGGLETALENTS" "n"]
   ["open professions" "TOGGLEPROFESSIONBOOK" "k"]
   ["open quest log" "TOGGLEQUESTLOG" "l"]
   ["open quests" "TOGGLEQUESTLOG" "l"]
   ["open guild" "TOGGLEGUILDTAB" "j"]
   ["open friends" "TOGGLESOCIAL" "o"]
   ["open social" "TOGGLESOCIAL" "o"]
   ["open raid" "TOGGLERAIDTAB" nil]
   ["open group finder" "TOGGLEGROUPFINDER" nil]
   ["open looking for group" "TOGGLEGROUPFINDER" nil]
   ["open collections" "TOGGLECOLLECTIONS" "p"]
   ["open mounts" "TOGGLECOLLECTIONS" "p"]])

(defn- actions [state bound said] (second (r/speech state bound said)))

(deftest each-panel-phrase-presses-one-default-key
  (doseq [[said _ k] panels]
    (is (= (if k [(cond-> {:type "key" :key k} (= "p" k) (assoc :mods ["shift"]))] [])
           (actions r/initial {} said))
        said)))

(deftest the-players-own-binding-wins
  (doseq [[said action] panels]
    (is (= [{:type "key" :key "f7" :mods ["alt"]}] (actions r/initial {"ALT-F7" action} said))
        said)))

(deftest a-rebound-default-key-is-never-pressed
  (doseq [[said _ k] panels :when k]
    (let [default (str/upper-case k)
          default (if (= "p" k) "SHIFT-P" default)]
      (is (= [] (actions r/initial {default "MOVEFORWARD"} said)) said))))

(deftest spoken-variants-reach-the-same-panel
  (doseq [said ["Open the quest log." "open my talents" "open the group finder"
                "open the character sheet" "open my bags"]]
    (is (= 1 (count (actions r/initial {"F9" "TOGGLEGROUPFINDER"} said))) said)))

(deftest panels-never-type-into-an-open-draft-or-dictate
  (let [draft (reduce #(first (r/speech %1 {} %2)) r/initial ["chat with party" "hello"])
        chatting (first (r/speech r/initial {} "chat with party"))]
    (doseq [[said] panels]
      (is (= [] (actions draft {} said)) "a key would type a letter into the box")
      (testing "in chat mode the phrase opens the panel, it isn't posted"
        (is (not-any? #(= "text" (:type %)) (actions chatting {} said)) said)))))

(deftest panels-never-press-enter-or-escape
  (doseq [[said action] panels
          key ["ENTER" "ESCAPE" "SHIFT-ENTER"]]
    (is (not-any? #(#{"enter" "escape"} (:key %)) (actions r/initial {key action} said)) said)))

(deftest every-default-key-is-pressable
  (doseq [[action k] b/default-keys]
    (is (b/key-action k) action)))

(deftest the-addon-probe-reports-every-voice-action-binding
  ;; The runtime agent checks the default keys above against the live client with /mimiron probe.
  (let [lua (slurp "addon/Mimiron/Mimiron.lua")]
    (doseq [action (set (vals r/voice-action-bindings))]
      (is (str/includes? lua (str "\"" action "\"")) action))))
