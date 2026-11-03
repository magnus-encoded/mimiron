(ns mimiron.main
  "Tehuti plugin speaking protocol v0 over stdin and stdout."
  (:require [jsonista.core :as json])
  (:gen-class))

(set! *warn-on-reflection* true)

(def ^:private mapper (json/object-mapper {:decode-key-fn true}))

(defn- send! [msg]
  (println (json/write-value-as-string (assoc msg :jsonrpc "2.0")))
  (flush))

(defn- log [& parts]
  (binding [*out* *err*] (apply println "mimiron:" parts)))

(defn- handle [{:keys [id method params]}]
  (case method
    "initialize" (send! {:id id :result {:protocol_version 0
                                         :encoding "json"
                                         :events ["button" "focus"]}})
    "focus" (log "focus" (:app_id params) (:title params))
    ;; Decline every button until the chat router exists.
    "button" (send! {:id id :result []})
    (when id (send! {:id id :error {:code -32601 :message "Method not found"}}))))

(defn -main [& _]
  (doseq [line (line-seq (java.io.BufferedReader. *in*))]
    (try
      (handle (json/read-value line mapper))
      (catch Exception e (log "bad line:" (ex-message e))))))
