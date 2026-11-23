(ns mimiron.invariant-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [jsonista.core :as json]
            [mimiron.main :as main]))

(def event-gen
  (gen/one-of
   [(gen/fmap (fn [focused]
                {:method "focus"
                 :params {:app_id (if focused "steam_proton" "firefox")
                          :title "World of Warcraft"}})
              gen/boolean)
    (gen/fmap (fn [text] {:method "speech" :params {:text text}})
              (gen/one-of [(gen/elements ["chat with party" "chat with guild"
                                         "switch channel" "stop chat" "pause chat"
                                         "resume chat" "open bags" "open map"])
                           (gen/fmap #(str "private-speech-" %) gen/string-alphanumeric)]))
    (gen/let [method (gen/elements ["button" "pad_input"])
              button (gen/elements ["south" "east" "select" "start" "dpad_up" "dpad_down"])
              state (gen/elements ["pressed" "released"])]
      {:method method :params {(if (= method "button") :name :button) button :state state}})]))

(def mapper (json/object-mapper {:decode-key-fn true}))

(defn check-sequence [events]
  ;; Exercise the real wire loop one event at a time, retaining its world.
  ;; run accepts an initial world through reduce's seed only, so replay each
  ;; prefix and compare the newly emitted suffix.
  (let [err (java.io.StringWriter.)
        messages (mapv #(json/write-value-as-string %) events)
        _ (with-out-str
              (binding [*err* err]
                ;; Observe states at event boundaries without replacing routing.
                (main/run messages {})
                (main/run (for [event events
                                :let [text (get-in event [:params :text])]
                                :when text]
                            (str "{\"method\":\"speech\",\"params\":{\"text\":"
                                 (json/write-value-as-string text))) {})))
        ;; Replay prefixes to inspect each event's actions and prior draft.
        snapshots
        (mapv (fn [n]
                (let [result (atom nil)
                      text (with-out-str
                             (binding [*err* (java.io.StringWriter.)]
                               (reset! result (main/run (take n messages) {}))))]
                  {:world @result
                   :out (mapv #(json/read-value % mapper) (remove str/blank? (str/split-lines text)))}))
              (range (inc (count events))))]
    (and
     (:ok
      (reduce
       (fn [{:keys [open? ok]} [i event]]
         (let [after (get-in snapshots [(inc i) :world])
               prior-out (:out (snapshots i))
               emitted (drop (count prior-out) (:out (snapshots (inc i))))
               actions (mapcat #(or (get-in % [:params :actions])
                                    (when (vector? (:result %)) (:result %))) emitted)
               ;; Model the actual chat box from emitted keys, independently
               ;; of the router's draft flag, and preserve it over focus loss.
               chat (reduce (fn [{:keys [open? sent?] :as chat} action]
                              (case (:key action)
                                "enter" {:open? (not open?) :sent? (or sent? open?)}
                                "escape" (assoc chat :open? false)
                                chat))
                            {:open? open? :sent? false} actions)
               pad-send? (and (= "pad_input" (:method event))
                              (= "south" (get-in event [:params :button]))
                              (= "pressed" (get-in event [:params :state]))
                              open?
                              (get-in snapshots [i :world :state :draft]))]
           ;; The core sends Enter on request-side South; release clears the draft.
           {:open? (and (:open? chat)
                        (not (and (= "button" (:method event))
                                  (= "south" (get-in event [:params :name]))
                                  (= "released" (get-in event [:params :state]))
                                  (get-in snapshots [i :world :state :draft]))))
            :ok (and ok
                     (or (:game after) (empty? actions))
                     (or (not (:sent? chat)) pad-send?)
                     (not-any? #(= "select" (:button %))
                               (mapcat #(get-in % [:params :overrides]) emitted)))}))
       {:open? false :ok true}
       (map-indexed vector events)))
     (every? (fn [event]
               (let [text (get-in event [:params :text])]
                 (or (not (seq text)) (not (str/includes? (str err) text)))))
             events))))

(deftest generative-invariants
  (let [result (tc/quick-check
                300
                (prop/for-all [events (gen/vector event-gen 0 60)]
                  (check-sequence
                   (cons {:id 0 :method "initialize" :params {}} events)))
                :seed 20261104)]
    (is (:pass? result) (pr-str (select-keys result [:seed :num-tests :shrunk])))))

(deftest shrunk-request-release-regression
  (is (check-sequence
       [{:id 0 :method "initialize" :params {}}
        {:method "focus" :params {:app_id "steam_proton" :title "World of Warcraft"}}
        {:method "speech" :params {:text "chat with party"}}
        {:method "speech" :params {:text "private-speech-"}}
        {:method "button" :params {:name "south" :state "released"}}
        {:method "speech" :params {:text "private-speech-"}}])))
