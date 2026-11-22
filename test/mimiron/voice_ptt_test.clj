(ns mimiron.voice-ptt-test
  "Voice chat push-to-talk, end to end against a fake core: initialize, focus, speech, pad
  and `screen_probe` notices in, the plugin's JSON lines out. The add-on says voice chat is
  on through the game link strip; Mimiron tells the core where push-to-talk goes with a
  `push_to_talk.route` notice, only when the core offers it. Spec: docs/notes/voice-chat-ptt.md."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [mimiron.link :as link]
            [mimiron.main :as main]))

;; Drawn by addon/Mimiron/Link.lua: voice chat on, binding LSHIFT + V, via Discord, party Ana.
(def voice-strip (edn/read-string (slurp "test/fixtures/link-strip-voice.edn")))

(defn- encode
  "Test-side encoder mirroring Link.lua, for states the fixtures don't cover."
  [{:keys [flags session payload] :or {flags 0 session 9 payload "p="}}]
  (let [p (vec (.getBytes ^String payload "UTF-8"))
        ;; Situation 1: a party, as in the voice fixture, so no mode change rides along.
        head (into [0x4D 0x32 flags session 0 1 (count p)] (map #(bit-and % 0xff) p))
        bytes (conj head (mod (reduce + head) 256))
        bytes (into bytes (repeat (- link/byte-count (count bytes)) 0))
        nibbles (mapcat (fn [b] [(quot b 16) (mod b 16)]) bytes)]
    (mapv (fn [c] (mapv #(* 17 %) c)) (partition 3 nibbles))))

(def ^:private voice 16)
(def ^:private chat-open 2)
(def ^:private keep-dictation 64)

(def voice-off (encode {:payload "p=Ana"}))
(def voice-chat-open (encode {:flags (+ voice 32 chat-open) :payload "k=SHIFT-V;p=Ana"}))
(def voice-kept (encode {:flags (+ voice keep-dictation) :payload "k=V;p=Ana"}))
(def voice-mouse-key (encode {:flags voice :payload "k=BUTTON4;p=Ana"}))
(def voice-plain-key (encode {:flags voice :payload "k=V;p=Ana"}))

(def ^:private both {:screen_probe "screen_probe.watch" :push_to_talk_route true})

(defn- init [options]
  {:id 0 :method "initialize"
   :params (cond-> {:protocol_version 0 :encoding "json" :core_version "0.0.0"}
             options (assoc :options options))})

(def ^:private wow {:method "focus" :params {:app_id "steam_proton" :title "World of Warcraft"}})
(def ^:private elsewhere {:method "focus" :params {:app_id "firefox" :title "Browser"}})
(defn- probe [cells] {:method "screen_probe" :params {:cells cells}})
(defn- say [id text] {:id id :method "speech" :params {:text text}})
(def ^:private send-a {:method "pad_input" :params {:button "south" :state "pressed"}})

(defn- fake-core [& messages]
  (let [lines (map #(json/write-value-as-string (assoc % :jsonrpc "2.0")) messages)]
    (->> (with-out-str
           (binding [*err* (java.io.StringWriter.)]
             (main/run lines {} identity [] {:clock (constantly 0)})))
         str/split-lines
         (mapv #(json/read-value % (json/object-mapper {:decode-key-fn true}))))))

(defn- routes [out] (mapv :params (filter #(= "push_to_talk.route" (:method %)) out)))
(defn- statuses [out] (mapv #(get-in % [:params :text]) (filter #(= "status" (:method %)) out)))

(def ^:private to-voice {:to "key" :key "v" :mods ["shift"]})
(def ^:private to-speech {:to "speech"})

(deftest a-core-without-the-route-changes-nothing
  (doseq [options [nil {:screen_probe "screen_probe.watch"} {:push_to_talk_route true}]]
    (let [out (fake-core (init options) wow (probe voice-strip))]
      (is (empty? (routes out)) (pr-str options))
      (is (not-any? #(and % (str/includes? % "PTT")) (statuses out)) (pr-str options)))))

(deftest voice-chat-takes-push-to-talk
  (let [out (fake-core (init both) wow (probe voice-off) (probe voice-strip) (probe voice-strip)
                       (probe voice-off))]
    (testing "nothing while voice chat is off: the core starts on speech"
      (is (= [to-voice to-speech] (routes out))))
    (testing "the powerline names where the button goes, and the source"
      (is (some #{"PTT: voice chat (Discord)"} (statuses out)))
      (is (nil? (last (statuses out)))))))

(deftest a-plain-key-has-no-mods
  (is (= [{:to "key" :key "v"}] (routes (fake-core (init both) wow (probe voice-plain-key))))))

(deftest an-open-chat-box-or-a-draft-keeps-dictation
  (testing "the add-on reports the chat box open: a held V would type into it"
    (is (= [to-voice to-speech to-voice]
           (routes (fake-core (init both) wow (probe voice-strip) (probe voice-chat-open)
                              (probe voice-strip))))))
  (testing "a draft Mimiron opened, until A sends it"
    (is (= [to-voice to-speech to-voice]
           (routes (fake-core (init both) wow (probe voice-strip)
                              (say 1 "chat with party") (say 2 "hello")
                              (probe voice-strip) send-a (probe voice-strip)))))))

(deftest the-setting-keeps-push-to-talk-for-dictation
  (let [out (fake-core (init both) wow (probe voice-kept))]
    (is (empty? (routes out)))
    (is (some #{"PTT: dictation"} (statuses out)))))

(deftest a-key-tehuti-cannot-press-stays-on-dictation
  (let [out (fake-core (init both) wow (probe voice-mouse-key))]
    (is (empty? (routes out)))
    (is (some #{"PTT: dictation (voice chat key not usable)"} (statuses out)))))

(deftest leaving-wow-hands-push-to-talk-back
  (let [out (fake-core (init both) wow (probe voice-strip) elsewhere (probe voice-strip) wow)]
    (is (= [to-voice to-speech] (routes out))
        "a strip seen elsewhere is ignored, and the frame waits for the next sample")))

(deftest a-loading-screen-does-not-drop-voice-chat
  (is (= [to-voice] (routes (fake-core (init both) wow (probe voice-strip)
                                       (probe (vec (repeat 64 [0 0 0]))) (probe voice-strip))))))
