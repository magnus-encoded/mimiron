(ns mimiron.main
  "Tehuti plugin speaking protocol v0 over stdin and stdout."
  (:require [jsonista.core :as json]
            [mimiron.bindings :as bindings]
            [mimiron.router :as router])
  (:gen-class))

(set! *warn-on-reflection* true)

;; Replace with the value from Tehuti's focus log once WoW Forever has run.
(def app-ids #{"wow.exe"})

(def ^:private mapper (json/object-mapper {:decode-key-fn true}))

(defn- send! [msg]
  (println (json/write-value-as-string (assoc msg :jsonrpc "2.0")))
  (flush))

(defn- log [& parts]
  (binding [*out* *err*] (apply println "mimiron:" parts)))

(defn- notify! [method params] (send! {:method method :params params}))

(defn- handle
  "Applies one core message to `world`; returns the next world."
  [{:keys [state bound] :as world} {:keys [id method params]}]
  (let [reply! #(send! {:id id :result %})
        act! #(when (seq %) (notify! "actions" {:actions %}))]
    (case method
      "initialize" (do (reply! {:protocol_version 0 :encoding "json"
                                :events ["button" "focus" "speech"]})
                       (assoc world :ready true))
      "focus" (update world :state router/focus (contains? app-ids (:app_id params)))
      "speech" (let [[s actions] (router/speech state bound (:text params ""))]
                 (reply! actions)
                 (assoc world :state s))
      "button" (let [[s actions] (router/button state :request (:name params)
                                                (= "pressed" (:state params)))]
                 (reply! actions)
                 (assoc world :state s))
      "pad_input" (let [[s actions] (router/button state :pad (:button params)
                                                   (= "pressed" (:state params)))]
                    (act! actions)
                    (assoc world :state s))
      (do (when id (send! {:id id :error {:code -32601 :message "Method not found"}}))
          world))))

(defn- sync!
  "Sends overrides and status when they changed since the last message."
  [before after]
  (when (:ready after)
    (let [o (router/overrides (:state after))
          s (router/status (:state after))]
      (when (or (not (:ready before)) (not= o (router/overrides (:state before))))
        (notify! "pad_overrides" {:overrides (mapv #(hash-map :button %) o)}))
      (when (or (not (:ready before)) (not= s (router/status (:state before))))
        (notify! "status" {:text s})))))

(defn run [lines bound]
  (reduce (fn [world line]
            (try
              (let [next-world (handle world (json/read-value line mapper))]
                (sync! world next-world)
                next-world)
              (catch Exception e (log "bad line:" (ex-message e)) world)))
          {:state router/initial :bound bound :ready false}
          lines))

(defn -main [& _]
  (let [root (bindings/default-wtf-root)
        bound (if root (bindings/load-bindings root) {})]
    (log "bindings from" (or root "defaults"))
    (run (line-seq (java.io.BufferedReader. *in*)) bound)))
