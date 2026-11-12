(ns mimiron.link-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [mimiron.link :as link]
            [mimiron.main :as main]))

;; Drawn by addon/Mimiron/Link.lua under a stub WoW API: party Bjørn and Ana, in combat,
;; session 42. Regenerate it when the wire format changes.
(def strip (edn/read-string (slurp "test/fixtures/link-strip.edn")))

(defn- encode
  "Test-side encoder mirroring Link.lua."
  [{:keys [flags session payload] :or {flags 0 session 1 payload "p="}}]
  (let [p (vec (.getBytes ^String payload "UTF-8"))
        head (into [0x4D 0x31 flags session 0 (count p)] (map #(bit-and % 0xff) p))
        bytes (conj head (mod (reduce + head) 256))
        bytes (into bytes (repeat (- link/byte-count (count bytes)) 0))
        nibbles (mapcat (fn [b] [(quot b 16) (mod b 16)]) bytes)]
    (mapv (fn [c] (mapv #(* 17 %) c)) (partition 3 nibbles))))

(deftest decodes-what-the-add-on-draws
  (is (= {:session 42 :revision 1 :overflow false :party ["Ana" "Bjørn"]
          :combat true :chat false :wants-reload false :busy false
          :voice false :voice-discord false :keep-dictation false :ptt-key nil}
         (link/decode strip))))

;; Drawn by Link.lua with voice chat on: an active channel in push-to-talk mode, binding
;; LSHIFT + V, voiceProvider 1 (Discord), session 9, party Ana.
(def voice-strip (edn/read-string (slurp "test/fixtures/link-strip-voice.edn")))

(deftest decodes-voice-chat-state
  (is (= {:session 9 :party ["Ana"] :voice true :voice-discord true :keep-dictation false
          :ptt-key "SHIFT-V" :overflow false}
         (select-keys (link/decode voice-strip)
                      [:session :party :voice :voice-discord :keep-dictation :ptt-key :overflow]))))

(deftest voice-fields-ride-along-with-overflow-and-old-frames
  (is (= {:ptt-key "V" :overflow true :party [] :keep-dictation true}
         (select-keys (link/decode (encode {:flags (+ 16 64) :payload "k=V;p=+"}))
                      [:ptt-key :overflow :party :keep-dictation])))
  (is (= {:ptt-key nil :party ["Ana"] :voice false}
         (select-keys (link/decode (encode {:payload "p=Ana"})) [:ptt-key :party :voice])))
  (is (nil? (link/decode (encode {:payload "k=V;x=1"}))) "an unknown payload is a torn frame"))

(deftest tolerates-colour-drift-and-rejects-torn-frames
  (is (= ["Ana" "Bjørn"] (:party (link/decode (mapv (fn [c] (mapv #(min 255 (+ % 6)) c)) strip)))))
  (is (= ["Ana" "Bjørn"] (:party (link/decode (mapv (fn [c] (mapv #(* 0.67 %) c)) strip))))
      "an unfocused tile dims every channel by about a third")
  (is (nil? (link/decode (assoc strip 5 [0 0 0]))))
  (is (nil? (link/decode (vec (repeat 64 [0 0 0])))))
  (is (nil? (link/decode (pop strip))))
  (is (nil? (link/decode "nonsense"))))

(def ^:private wants (encode {:flags 4 :payload "p=+"}))

(defn- run-link [steps]
  (reduce (fn [[l acc] [cells state now]]
            (let [[l a e] (link/observe l cells state now)]
              [l (conj acc [a e (link/status l)])]))
          [link/initial []] steps))

(deftest reload-is-warned-then-sent-only-when-safe
  (let [idle {:draft false :switching false}
        [_ out] (run-link [[wants idle 0]
                           [wants idle 1000]
                           [(encode {:flags (+ 4 1) :payload "p=+"}) idle 6000]
                           [wants {:draft true} 7000]
                           [wants idle 8000]
                           [wants idle 9000]])]
    (testing "first sight warns, sends nothing"
      (is (= [[] "reload soon to sync"] ((juxt first last) (out 0))))
      (is (= [] (first (out 1)))))
    (testing "combat and a draft hold it back"
      (is (= [] (first (out 2))))
      (is (= [] (first (out 3)))))
    (testing "then exactly one /reload"
      (is (= [{:type "key" :key "enter"} {:type "text" :text "/reload"} {:type "key" :key "enter"}]
             (first (out 4))))
      (is (= "syncing (reload)" (last (out 4))))
      (is (= [] (first (out 5)))))))

(deftest new-session-asks-for-saved-names-and-spaces-reloads
  (let [idle {}
        [l out] (run-link [[wants idle 0] [wants idle 5000]
                           [nil idle 6000]
                           [(encode {:flags 4 :session 2 :payload "p=+"}) idle 9000]
                           [(encode {:flags 4 :session 2 :payload "p=+"}) idle 20000]])]
    (is (= #{:new-session} (second (out 0))))
    (is (= #{:new-session} (second (out 3))))
    (is (not (:reloading l)))
    (is (= [] (first (out 4))) "ten minutes between reloads")))

(deftest stuck-reload-times-out
  (let [[l _] (run-link [[wants {} 0] [wants {} 5000] [wants {} 70000]])]
    (is (not (:reloading l)))))

(deftest wire-ignores-strip-outside-wow-and-primes-hotwords
  (let [msg (fn [m] (json/write-value-as-string (assoc m :jsonrpc "2.0")))
        probe {:method "screen_probe" :params {:cells strip}}
        go (fn [focus-title]
             (->> (with-out-str
                    (binding [*err* (java.io.StringWriter.)]
                      (main/run (map msg [{:id 0 :method "initialize" :params {}}
                                          {:method "focus" :params {:app_id "steam_proton" :title focus-title}}
                                          probe
                                          {:id 1 :method "speech" :params {:text "chat with party"}}
                                          {:id 2 :method "speech" :params {:text "chat with guild"}}
                                          {:id 3 :method "speech" :params {:text "stop chat"}}])
                                {} identity ["Guildie"] {:clock (constantly 0)
                                                         :live-features #{"screen_probe" "speech.hints"}})))
                  str/split-lines
                  (mapv #(json/read-value % (json/object-mapper {:decode-key-fn true})))))
        in-game (go "World of Warcraft")
        elsewhere (go "Elden Ring")]
    (is (some #(= {:x 2 :y 2 :step 4 :count 64} (:params %)) in-game))
    (is (= [{:hotwords mimiron.router/channels} {:hotwords ["Ana" "Bjørn"]} {:hotwords ["Guildie"]}
            {:hotwords mimiron.router/channels}]
           (mapv :params (filter #(= "speech.hints" (:method %)) in-game)))
        "channel names until a channel is chosen, then that channel's people; back to channels on stop")
    (is (not-any? #(= "actions" (:method %)) in-game))
    (is (not-any? #(= "speech.hints" (:method %)) elsewhere) "inert outside WoW")
    (is (every? #(<= (count (get-in % [:params :hotwords])) main/hint-budget)
                (filter #(= "speech.hints" (:method %)) in-game)))))
