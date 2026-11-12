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

(deftest launcher-hint-without-game-input
  (let [launcher {:method "focus" :params {:app_id "steam_proton" :title "BattleNet - Wine Desktop"}}
        out (session init wow
                     {:id 1 :method "speech" :params {:text "chat with party"}}
                     {:id 2 :method "speech" :params {:text "hello"}}
                     launcher
                     {:id 3 :method "speech" :params {:text "more"}}
                     {:id 4 :method "button" :params {:name "south" :state "pressed"}}
                     {:method "pad_input" :params {:button "south" :state "pressed"}})]
    (is (false? (main/in-game? (:params launcher))))
    (is (= [{:overrides []} {:text "Start WoW with wow-forever"}]
           (mapv :params (take-last 2 (filter #(#{"pad_overrides" "status"} (:method %)) out)))))
    (is (= [[] []] (mapv :result (filter #(#{3 4} (:id %)) out))))
    (is (not-any? #(= "actions" (:method %)) out))
    (is (= {:text nil} (:params (last (session init launcher
                       {:method "focus" :params {:app_id "firefox" :title "BattleNet - Wine Desktop"}})))))
    (is (= {:text "Party"} (:params (last (session init wow
                       {:id 1 :method "speech" :params {:text "chat with party"}}
                       launcher wow)))))))

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
