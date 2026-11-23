(ns mimiron.main-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jsonista.core :as json]
            [mimiron.main :as main]))

(defn- session [& messages]
  (let [lines (map #(json/write-value-as-string (assoc % :jsonrpc "2.0")) messages)]
    (->> (with-out-str (binding [*err* (java.io.StringWriter.)] (main/run lines {})))
         str/split-lines
         (mapv #(json/read-value % (json/object-mapper {:decode-key-fn true}))))))

(def ^:private init
  {:id 0 :method "initialize" :params {:protocol_version 0 :encoding "json" :core_version "0"}})

(def ^:private wow
  {:method "focus" :params {:app_id "steam_proton" :title "World of Warcraft"}})

(deftest session-over-the-wire
  (let [out (session init wow
                     {:id 1 :method "speech" :params {:text "chat with party"}}
                     {:id 2 :method "speech" :params {:text "hi"}}
                     {:method "pad_input" :params {:button "south" :state "pressed"}})]
    (is (= ["button" "focus" "speech"] (get-in out [0 :result :events])))
    (is (= {:method "pad_overrides" :params {:overrides []}} (dissoc (out 1) :jsonrpc)))
    (is (= {:method "status" :params {:text nil}} (dissoc (out 2) :jsonrpc)))
    (is (not-any? #(#{"screen_probe.watch" "speech.hints"} (:method %)) out)
        "notices the core doesn't accept yet stay unsent")
    (is (some #(= {:text "Party"} (:params %)) out))
    (is (some #(= {:text "/p hi" :type "text"} (get-in % [:result 1])) out))
    (is (= {:method "actions" :params {:actions [{:type "key" :key "enter"}]}}
           (dissoc (last (filter #(= "actions" (:method %)) out)) :jsonrpc)))))

(deftest inert-outside-wow
  (doseq [focus [nil
                 {:method "focus" :params {:app_id "steam_proton" :title "Elden Ring"}}
                 {:method "focus" :params {:app_id "firefox" :title "World of Warcraft"}}]]
    (let [out (session init
                       focus
                       {:id 1 :method "speech" :params {:text "chat with party"}}
                       {:id 2 :method "speech" :params {:text "hi"}}
                       {:method "pad_input" :params {:button "south" :state "pressed"}})
          out (filterv some? out)]
      (is (every? #(or (nil? (:result %)) (= [] (:result %)) (map? (:result %))) out) focus)
      (is (not-any? #(= "actions" (:method %)) out) focus)
      (is (every? #(= {:overrides []} (:params %)) (filter #(= "pad_overrides" (:method %)) out)) focus)
      (is (every? #(= {:text nil} (:params %)) (filter #(= "status" (:method %)) out)) focus))))

(deftest leaving-wow-clears-overrides-and-status
  (let [out (session init wow
                     {:id 1 :method "speech" :params {:text "chat with party"}}
                     {:id 2 :method "speech" :params {:text "hello"}}
                     {:method "focus" :params {:app_id "steam_proton" :title "Elden Ring"}})
        notices (filterv #(#{"pad_overrides" "status"} (:method %)) out)]
    (is (= [{:overrides []} {:text nil}] (mapv :params (take-last 2 notices))))))

(defn- stderr-of [& lines]
  (let [err (java.io.StringWriter.)]
    (with-out-str (binding [*err* err] (main/run lines {})))
    (str err)))

(deftest logs-never-contain-message-content
  (let [secret "hunter2-party-secret"
        err (stderr-of (str "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"speech\",\"params\":{\"text\":\"" secret)
                       (str "[\"" secret "\"]")
                       (str "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"speech\",\"params\":{\"text\":{\"x\":\"" secret "\"}}}")
                       (str "\"" secret "\""))]
    (is (seq err) "the bad lines were logged")
    (is (not (str/includes? err secret)))
    (is (not (str/includes? err "hunter2")))))

(deftest nothing-before-initialize
  (is (= [] (filterv :method (session {:id 1 :method "speech" :params {:text "chat with party"}})))))

(deftest manifest-matches-plugin
  (let [m (json/read-value (io/file "mimiron.json"))]
    (is (= #{"key" "text"} (set (m "permissions"))))
    (is (= main/app-ids (set (m "app_ids"))))
    (is (= #{"button" "focus" "speech"} (set (m "events"))))))

(defn- languages [out]
  (mapv #(get-in % [:params :language]) (filter #(= "speech.language" (:method %)) out)))

(def ^:private other
  {:method "focus" :params {:app_id "firefox" :title "Firefox"}})

(deftest speech-language-follows-party-and-guild
  (let [out (session init wow
                     {:id 1 :method "speech" :params {:text "chat with party"}}
                     {:id 2 :method "speech" :params {:text "hi"}}
                     {:id 3 :method "speech" :params {:text "chat with guild"}}
                     {:id 4 :method "speech" :params {:text "chat with say"}})]
    (is (= ["en" "no" "en"] (languages out)))
    (is (= {:jsonrpc "2.0" :method "speech.language" :params {:language "no"}}
           (first (filter #(= "no" (get-in % [:params :language])) out))))))

(deftest speech-language-resent-on-focus-entry
  (let [out (session init wow
                     {:id 1 :method "speech" :params {:text "chat with party"}}
                     other wow wow)]
    (is (= ["en" "no" "no"] (languages out)) "re-sent once on re-entry, not on repeated focus")))

(deftest speech-language-only-on-change-and-only-in-wow
  (is (= [] (languages (session init other))))
  (is (= ["en"] (languages (session init wow
                                    {:id 1 :method "speech" :params {:text "chat with say"}}
                                    {:id 2 :method "speech" :params {:text "hi"}})))))

(deftest the-launcher-gets-no-status-and-no-game-input
  ;; WoW starts from the Battle.net launcher's Play button, so there is no launch advice to give.
  (let [launcher {:method "focus" :params {:app_id "steam_proton" :title "Battle.net"}}
        out (session init wow
                     {:id 1 :method "speech" :params {:text "chat with party"}}
                     {:id 2 :method "speech" :params {:text "hello"}}
                     launcher
                     {:id 3 :method "speech" :params {:text "more"}}
                     {:id 4 :method "button" :params {:name "south" :state "pressed"}}
                     {:method "pad_input" :params {:button "south" :state "pressed"}})]
    (is (false? (main/in-game? (:params launcher))))
    (is (= [{:overrides []} {:text nil}]
           (mapv :params (take-last 2 (filter #(#{"pad_overrides" "status"} (:method %)) out)))))
    (is (= [[] []] (mapv :result (filter #(#{3 4} (:id %)) out))))
    (is (not-any? #(= "actions" (:method %)) out))
    (is (not-any? #(= {:text "Start WoW with wow-forever"} (:params %))
                  (session init {:method "focus" :params {:app_id "steam_proton"
                                                          :title "BattleNet - Wine Desktop"}})))
    (is (= {:text "Party"} (:params (last (filter #(= "status" (:method %)) (session init wow
                       {:id 1 :method "speech" :params {:text "chat with party"}}
                       launcher wow))))))))

(deftest known-drafts-survive-non-a-wire-paths-without-being-sent
  (doseq [method ["button" "pad_input"]
          event [{:name "east" :button "east" :state "pressed"}
                 {:name "start" :button "start" :state "pressed"}
                 {:name "south" :button "south" :state "released"}
                 {:name "east" :button "east" :state "released"}]]
    (let [out (session init wow
                       {:id 1 :method "speech" :params {:text "chat with party"}}
                       {:id 2 :method "speech" :params {:text "first"}}
                       {:id 3 :method "speech" :params {:text "second"}}
                       {:id 4 :method method :params event}
                       {:method "focus" :params {:app_id "firefox" :title "Browser"}}
                       {:id 5 :method "speech" :params {:text "outside"}}
                       {:method method :params (assoc event :state "pressed")})
          actions (mapcat :result (filter #(#{3 4 5} (:id %)) out))]
      (is (not-any? #{{:type "key" :key "enter"}} actions) (str method event))
      (is (not-any? #(= "actions" (:method %)) out)))))
(deftest hostile-lines-recover
  (doseq [line ["{broken" "[]" "null"
                "{\"id\":3,\"method\":\"private-unknown\"}"
                "{\"id\":4,\"method\":\"speech\"}"
                (apply str (repeat (inc main/max-line-length) "x"))]]
    (let [err (java.io.StringWriter.)
          out (with-out-str
                (binding [*err* err]
                  (main/run [line (json/write-value-as-string init)] {})))]
      (is (str/includes? out "protocol_version"))
      (is (not (str/includes? (str err) "private-unknown")))
      (is (every? #(re-matches #"mimiron: error \w+ method (other|speech) length \d+" %)
                  (str/split-lines (str err)))))))

(deftest bounded-stdin-and-eof
  (let [huge (apply str (repeat (* 4 main/max-line-length) "x"))
        lines (main/read-lines (java.io.StringReader. (str huge "\n{}\n")))]
    (is (= [{:length (count huge)} "{}"] (vec lines))))
  (is (= [] (vec (main/read-lines (java.io.StringReader. "")))))
  (is (= ["{}"] (vec (main/read-lines (java.io.StringReader. "{}")))))
  (is (map? (main/run [] {}))))

(deftest screenshot-requires-game-focus
  (doseq [phrase ["screenshot" "take a screenshot" "take screenshot"]]
    (let [out (session init wow
                       {:id 1 :method "speech" :params {:text phrase}}
                       {:method "focus" :params {:app_id "firefox" :title "Browser"}}
                       {:id 2 :method "speech" :params {:text phrase}})]
      (is (= [{:type "key" :key "print_screen"}]
             (:result (first (filter #(= 1 (:id %)) out)))))
      (is (= [] (:result (first (filter #(= 2 (:id %)) out)))))
      (is (not-any? #(= "actions" (:method %)) out)))))

(deftest whisper-reaches-guild-and-party-names
  (doseq [[guild opts] [[["Arthas"] {}] [[] {:link-names ["Arthas"]}]]]
    (let [lines (map #(json/write-value-as-string (assoc % :jsonrpc "2.0"))
                     [init wow {:id 1 :method "speech" :params {:text "whisper arthas"}}
                      {:id 2 :method "speech" :params {:text "hi"}}])
          out (->> (with-out-str (binding [*err* (java.io.StringWriter.)]
                                   (main/run lines {} identity guild opts)))
                   str/split-lines
                   (mapv #(json/read-value % (json/object-mapper {:decode-key-fn true}))))]
      (is (some #(= {:text "/w Arthas hi" :type "text"} (get-in % [:result 1])) out)
          (pr-str opts)))))

(defn- run-with [opts & messages]
  (let [lines (map #(json/write-value-as-string (assoc % :jsonrpc "2.0")) messages)]
    (->> (with-out-str (binding [*err* (java.io.StringWriter.)]
                         (main/run lines {} identity [] opts)))
         str/split-lines
         (mapv #(json/read-value % (json/object-mapper {:decode-key-fn true}))))))

(defn- result [out id] (:result (first (filter #(= id (:id %)) out))))

(deftest bindings-are-reread-on-focus-and-before-speech
  (let [file (atom {})
        calls (atom 0)
        opts {:reload-bindings #(do (swap! calls inc) @file)}
        elsewhere {:method "focus" :params {:app_id "firefox" :title "Browser"}}
        quest {:method "speech" :params {:text "open quest log"}}
        out (run-with opts init elsewhere (assoc quest :id 1) wow (assoc quest :id 2))]
    (is (= [] (result out 1)) "outside WoW: nothing pressed")
    (is (= [{:type "key" :key "l"}] (result out 2)) "no file: the default key")
    (is (= 2 @calls) "read on WoW focus and before the phrase, never while elsewhere")
    (reset! file {"F7" "TOGGLEQUESTLOG"})
    (is (= [{:type "key" :key "f7"}] (result (run-with opts init wow (assoc quest :id 1)) 1))
        "a rebind in the file is pressed without a restart")))

(deftest a-failed-reread-keeps-the-last-bindings
  (let [n (atom 0)
        opts {:reload-bindings #(if (= 1 (swap! n inc))
                                  {"F7" "TOGGLEQUESTLOG"}
                                  (throw (java.io.IOException. "gone")))}
        out (run-with opts init wow {:id 1 :method "speech" :params {:text "open quest log"}})]
    (is (= [{:type "key" :key "f7"}] (result out 1)))))

(deftest language-overrides-follow-the-channel-for-the-session
  (let [said (fn [id text] {:id id :method "speech" :params {:text text}})
        out (session init wow
                     (said 1 "chat with party") (said 2 "chat in english")
                     (said 3 "chat with guild") (said 4 "chat with party")
                     (said 5 "stop chat") (said 6 "chat with party")
                     other wow
                     (said 7 "chat with say") (said 8 "chat på norsk")
                     (said 9 "chat with officer") (said 10 "chat with say"))]
    (is (= ["en" "no" "en" "no" "en" "en" "no"] (languages out)))
    (is (some #(= {:text "Party: English"} (:params %)) out))
    (is (some #(= {:text "Say: Norwegian"} (:params %)) out))
    (is (= [] (:result (first (filter #(= 2 (:id %)) out)))))
    (is (= [] (:result (first (filter #(= 8 (:id %)) out))))))
  (is (= ["en" "no"]
         (languages (session init wow
                             {:id 1 :method "speech" :params {:text "chat with party"}})))
      "a new plugin session starts with the defaults"))

(deftest language-commands-are-inert-outside-wow
  (is (= ["en" "no" "no"]
         (languages (session init wow
                             {:id 1 :method "speech" :params {:text "chat with party"}}
                             other
                             {:id 2 :method "speech" :params {:text "chat in english"}}
                             wow)))))

(defn- verb [id text]
  {:id id :method "verb" :params {:verb "mimiron" :locale "en" :text text}})

(deftest english-verb-commands-follow-the-current-channel
  (let [out (session init wow
                     (verb 1 "chat with party") (verb 2 "chat in english")
                     (verb 3 "chat with say") (verb 4 "chat in norwegian")
                     (verb 5 "chat with guild") (verb 6 "chat with party")
                     (verb 7 "stop chat"))]
    (is (= ["en" "no" "en" "no" "en"] (languages out)))
    (is (every? #(= [] (result out %)) (range 1 8)))
    (is (= {:text nil} (:params (last (filter #(= "status" (:method %)) out)))))))

(deftest verb-path-never-dictates-and-preserves-drafts
  (let [out (session init wow
                     (verb 1 "chat with say")
                     (verb 2 "private dictated words")
                     {:id 3 :method "speech" :params {:text "first"}}
                     (verb 4 "more private words")
                     {:id 5 :method "speech" :params {:text "second"}})]
    (is (= [] (result out 2)))
    (is (= [] (result out 4)))
    (is (= [{:type "text" :text " second"}] (result out 5)))))

(deftest verb-commands-use-normal-actions-and-focus-guard
  (is (= [{:type "key" :key "print_screen"}]
         (result (session init wow (verb 1 "screenshot")) 1)))
  (doseq [focus [other nil]
          request [(verb 1 "screenshot") (verb 1 "chat with say")]]
    (is (= [] (result (session init focus request) 1))))
  (doseq [params [{:verb "other" :locale "en" :text "screenshot"}
                  {:verb "mimiron" :locale "no" :text "screenshot"}]]
    (is (= [] (result (session init wow {:id 1 :method "verb" :params params}) 1))))
  ;; Legacy string verbs have no locale in the request.
  (is (= [{:type "key" :key "print_screen"}]
         (result (session init wow (update (verb 1 "screenshot") :params dissoc :locale)) 1))))

(deftest verb-errors-never-log-content
  (let [err (stderr-of (json/write-value-as-string
                       {:id 1 :method "verb" :params {:verb "mimiron" :text {:secret "private-message"}}}))]
    (is (str/includes? err "InvalidParams"))
    (is (not (str/includes? err "private-message")))))

(deftest verbs-register-once-per-focus-entry
  (doseq [options [nil {} {:verbs true} {:verbs "verbs"}]]
    (let [out (session (assoc-in init [:params :options] options)
                       other wow wow (verb 1 "chat with guild")
                       (verb 2 "chat in english") (verb 3 "ordinary dictation")
                       other wow wow)
          registrations (filter #(= "verbs" (:method %)) out)]
      (is (= 2 (count registrations)))
      (let [registered (get-in (first registrations) [:params :verbs 0])]
        (is (= "mimiron" (:word registered)))
        (is (= "en" (:locale registered)))
        (is (every? (set (:hints registered))
                    ["chat with party" "chat in english" "chat in norwegian"])))
      (is (every? #(= {:jsonrpc "2.0" :method "verbs"
                       :params main/verb-registration} %) registrations))
      (is (= [] (result out 3)))
      (is (= ["en" "no" "en" "en"] (languages out))))))

(deftest explicit-verbs-capability-can-disable-registration
  (is (not-any? #(= "verbs" (:method %))
                (session (assoc-in init [:params :options] {:verbs false}) wow other wow))))

(deftest filling-never-logs-the-words
  (doseq [prefix ["type " "mail recipient " "mail subject " "mail body "
                  "trade gold " "stack split " "vendor quantity "]
          secret ["private mail subject" "twenty five"]]
    (let [err (java.io.StringWriter.)]
    (with-out-str
      (binding [*err* err]
        (main/run (map #(json/write-value-as-string (assoc % :jsonrpc "2.0"))
                       [init wow {:id 1 :method "speech" :params {:text (str prefix secret)}}]) {})))
      (is (not (str/includes? (str err) secret))))))
