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
                     {:method "focus" :params {:app_id "steam_proton" :title "Elden Ring"}})
        notices (filterv #(#{"pad_overrides" "status"} (:method %)) out)]
    (is (= [{:overrides []} {:text nil}] (mapv :params (take-last 2 notices))))))

(deftest nothing-before-initialize
  (is (= [] (filterv :method (session {:id 1 :method "speech" :params {:text "chat with party"}})))))

(deftest manifest-matches-plugin
  (let [m (json/read-value (io/file "mimiron.json"))]
    (is (= #{"key" "text"} (set (m "permissions"))))
    (is (= main/app-ids (set (m "app_ids"))))
    (is (= #{"button" "focus" "speech"} (set (m "events"))))))
