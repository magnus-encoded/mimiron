(ns mimiron.speech-hints-test
  "Dictation priming (tehuti #526) end to end against a fake core: `speech.hints`
  goes out only when the core offers it, always leads with the mode words, and
  never carries anything outside WoW. docs/notes/mode-switch-speech.md has the data."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [mimiron.main :as main]
            [mimiron.router :as router]))

(defn- init [options]
  {:id 0 :method "initialize"
   :params (cond-> {:protocol_version 0 :encoding "json" :core_version "0.0.0"}
             options (assoc :options options))})

(def ^:private wow {:method "focus" :params {:app_id "steam_proton" :title "World of Warcraft"}})
(def ^:private elsewhere {:method "focus" :params {:app_id "firefox" :title "Mail"}})
(defn- said [id text] {:id id :method "speech" :params {:text text}})

(defn- run [messages & {:keys [guild opts]}]
  (let [err (java.io.StringWriter.)
        out (with-out-str
              (binding [*err* err]
                (main/run (map #(json/write-value-as-string (assoc % :jsonrpc "2.0")) messages)
                          {} identity (or guild []) (merge {:clock (constantly 0)} opts))))]
    {:out (mapv #(json/read-value % (json/object-mapper {:decode-key-fn true})) (str/split-lines out))
     :err (str err)}))

(defn- hints [out] (mapv :params (filter #(= "speech.hints" (:method %)) out)))

(deftest a-core-without-speech-hints-gets-none
  (doseq [options [nil {} {:speech_hints false} {:screen_probe "screen_probe.watch"}]]
    (is (empty? (hints (:out (run [(init options) wow (said 1 "chat with party")])))) (pr-str options))))

(deftest a-core-offering-speech-hints-is-primed-with-the-mode-words-first
  (let [{:keys [out]} (run [(init {:speech_hints true}) wow (said 1 "chat with guild")
                            (said 2 "stop chat") elsewhere]
                           :guild ["Guildie"])]
    (is (= [{:scope "chat" :hotwords router/mode-hotwords}
            {:scope "chat" :hotwords (conj router/norwegian-mode-hotwords "Guildie")}
            {:scope "chat" :hotwords router/mode-hotwords}
            {:scope "chat" :hotwords []}]
           (hints out))
        "mode words in every mode, so a switch while chatting is primed too; names after them; cleared outside WoW")))

(deftest officer-chat-is-primed-with-guild-names
  (let [{:keys [out]} (run [(init {:speech_hints true}) wow (said 1 "chat with officer")]
                           :guild ["Guildie"])]
    (is (= {:scope "chat" :hotwords (conj router/norwegian-mode-hotwords "Guildie")} (last (hints out))))))

(deftest names-never-crowd-out-the-mode-words
  (let [guild (mapv #(str "Name" %) (range 100))
        sent (hints (:out (run [(init {:speech_hints true}) wow (said 1 "chat with guild")]
                               :guild guild)))]
    (is (every? #(<= (count (:hotwords %)) main/hint-budget) sent))
    (is (every? #(= 16 (count (remove (set guild) (:hotwords %)))) sent))))

(deftest outside-wow-nothing-is-sent
  (is (empty? (hints (:out (run [(init {:speech_hints true}) elsewhere (said 1 "chat with party")]))))))

(deftest the-status-reply-is-accepted-quietly
  (let [{:keys [out err]} (run [(init {:speech_hints true}) wow
                                {:method "speech.hints.status"
                                 :params {:scope "chat" :accepted 9 :dropped 0 :cap 48}}
                                (said 1 "chat with party")])]
    (is (str/blank? err) "no UnknownMethod line")
    (is (some #(= {:text "Party"} (:params %)) out))))

(deftest the-static-gate-still-works
  (testing "live-features turns hints on for a core that doesn't advertise them"
    (is (seq (hints (:out (run [(init nil) wow] :opts {:live-features #{"speech.hints"}})))))))

(deftest norwegian-dictation-keeps-whole-english-commands-primed
  (let [{:keys [out]} (run [(init {:speech_hints true}) wow
                            (said 1 "chat with say") (said 2 "chat på norsk")
                            (said 3 "sjatt med gruppe") (said 4 "chat på engelsk")
                            (said 5 "chat med laug")])]
    (doseq [hint (filter #(some #{"chat with say"} (:hotwords %)) (hints out))
            phrase ["chat with say" "switch channel" "chat in english"]]
      (is (some #{phrase} (:hotwords hint)) phrase))
    (is (= ["en" "no" "en" "no"]
           (mapv #(get-in % [:params :language])
                 (filter #(= "speech.language" (:method %)) out))))))

(deftest twenty-name-rosters-keep-at-least-sixteen-hints-in-both-languages
  (let [names (mapv #(str "Player" %) (range 20))]
    (doseq [channel ["party" "guild"]
            language ["en" "no"]]
      (let [sent (hints (:out (run [(init {:speech_hints true}) wow
                                    (said 1 (str "chat with " channel))
                                    (said 2 (str "chat in " (if (= language "no") "norwegian" "english")))]
                                   :guild names :opts {:link-names names})))
            words (:hotwords (last sent))
            modes (if (= language "no") router/norwegian-mode-hotwords router/mode-hotwords)]
        (is (= modes (vec (take (count modes) words))))
        (is (<= (count words) main/hint-budget))
        (is (<= 16 (count (filter (set names) words))))
        (is (= (= language "no") (boolean (some #{"chat with say"} words))))))))
