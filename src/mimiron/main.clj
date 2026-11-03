(ns mimiron.main
  "Tehuti plugin speaking protocol v0 over stdin and stdout."
  (:require [jsonista.core :as json]
            [mimiron.bindings :as bindings]
            [mimiron.router :as router])
  (:gen-class))

(set! *warn-on-reflection* true)

;; WoW Forever runs through Proton, whose windows all share this class, so
;; the title tells WoW apart from other Proton games.
(def app-ids #{"steam_proton"})
(def game-title "World of Warcraft")

(defn in-game? [{:keys [app_id title]}]
  (and (contains? app-ids app_id) (= game-title title)))

(def ^:private mapper (json/object-mapper {:decode-key-fn true}))

(defn- send! [msg]
  (println (json/write-value-as-string (assoc msg :jsonrpc "2.0")))
  (flush))

(defn- log [& parts]
  (binding [*out* *err*] (apply println "mimiron:" parts)))

(defn- notify! [method params] (send! {:method method :params params}))

(defn- route
  "Applies a speech, button or pad input event in game; returns [world actions]."
  [{:keys [state bound] :as world} method params]
  (let [[s actions] (case method
                      "speech" (router/speech state bound (:text params ""))
                      "button" (router/button state :request (:name params)
                                              (= "pressed" (:state params)))
                      "pad_input" (router/button state :pad (:button params)
                                                 (= "pressed" (:state params))))]
    [(assoc world :state s) actions]))

(defn- handle
  "Applies one core message to `world`; returns the next world. Outside the
  game Mimiron is inert: events change nothing and every reply is empty."
  [world {:keys [id method params]}]
  (case method
    "initialize" (do (send! {:id id :result {:protocol_version 0 :encoding "json"
                                             :events ["button" "focus" "speech"]}})
                     (assoc world :ready true))
    "focus" (let [game? (in-game? params)]
              (-> world
                  (assoc :game game?)
                  (update :state router/focus game?)))
    ("speech" "button" "pad_input")
    (let [[next-world actions] (if (:game world) (route world method params) [world []])]
      (if id
        (send! {:id id :result actions})
        (when (seq actions) (notify! "actions" {:actions actions})))
      next-world)
    (do (when id (send! {:id id :error {:code -32601 :message "Method not found"}}))
        world)))

(defn- shown
  "What the core should see: nothing while another app has focus."
  [world]
  (if (:game world)
    [(router/overrides (:state world)) (router/status (:state world))]
    [[] nil]))

(defn- sync!
  "Sends overrides and status when they changed since the last message."
  [before after]
  (when (:ready after)
    (let [[o s] (shown after)
          [o0 s0] (shown before)
          fresh (not (:ready before))]
      (when (or fresh (not= o o0))
        (notify! "pad_overrides" {:overrides (mapv #(hash-map :button %) o)}))
      (when (or fresh (not= s s0))
        (notify! "status" {:text s})))))

(defn run [lines bound]
  (reduce (fn [world line]
            (try
              (let [next-world (handle world (json/read-value line mapper))]
                (sync! world next-world)
                next-world)
              (catch Exception e (log "bad line:" (ex-message e)) world)))
          {:state router/initial :bound bound :ready false :game false}
          lines))

(defn -main [& _]
  (let [root (bindings/default-wtf-root)
        bound (if root (bindings/load-bindings root) {})]
    (log "bindings from" (or root "defaults"))
    (run (line-seq (java.io.BufferedReader. *in*)) bound)))
