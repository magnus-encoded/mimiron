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
                  (assoc :game game?
                         :launcher (and (contains? app-ids (:app_id params))
                                        (= "BattleNet - Wine Desktop" (:title params))))
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
    [[] (when (:launcher world) "Start WoW with wow-forever")]))

(def ^:private known-methods
  #{"initialize" "focus" "speech" "button" "pad_input"})

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

(def max-line-length 65536)

(defn read-lines
  "Bounded JSON-line reader. Oversized lines are drained without retaining content."
  [^java.io.Reader reader]
  (lazy-seq
   (let [buf (StringBuilder.)]
     (loop [n 0]
       (let [c (.read reader)]
         (if (or (= -1 c) (= 10 c))
           (when (or (pos? n) (= 10 c))
             (cons (if (> n max-line-length) {:length n} (str buf))
                   (when-not (= -1 c) (read-lines reader))))
           (do (when (< n max-line-length) (.append buf (char c)))
               (recur (inc n)))))))))

(defn- error! [class-name method length]
  (log "error" class-name "method" (or (known-methods method) "other") "length" length))

(defn- valid-message? [{:keys [method params]}]
  (and (string? method)
       (map? params)
       (case method
         "speech" (string? (:text params))
         "focus" (and (string? (:app_id params)) (string? (:title params)))
         "button" (and (string? (:name params)) (#{"pressed" "released"} (:state params)))
         "pad_input" (and (string? (:button params)) (#{"pressed" "released"} (:state params)))
         true)))

(defn run [lines bound]
  (reduce (fn [world line]
            (let [length (if (map? line) (:length line) (count line))]
              (if (> length max-line-length)
                (do (error! "LineTooLong" nil length) world)
                (let [msg (try (json/read-value line mapper)
                               (catch Exception e
                                 (error! (.getSimpleName (class e)) nil length)
                                 ::invalid))]
                  (cond
                    (= ::invalid msg) world
                    (not (map? msg)) (do (error! "InvalidRequest" nil length) world)
                    (not (known-methods (:method msg)))
                    (do (error! "UnknownMethod" nil length) (handle world msg))
                    (not (valid-message? msg))
                    (do (error! "InvalidParams" (:method msg) length) world)
                    :else
                    (try
                      (let [next-world (handle world msg)]
                        (sync! world next-world)
                        next-world)
                      (catch Exception e
                        (error! (.getSimpleName (class e)) (:method msg) length)
                        world)))))))
          {:state router/initial :bound bound :ready false :game false}
          lines))

(defn -main [& _]
  (let [bound (try
                (if-let [cache (System/getenv "MIMIRON_BINDINGS")]
                  (bindings/load-bindings nil cache)
                  (bindings/load-bindings (bindings/default-wtf-root)))
                (catch Exception e
                  (error! (.getSimpleName (class e)) nil 0)
                  {}))]
    (run (read-lines *in*) bound)))
