(ns mimiron.main
  "Tehuti plugin speaking protocol v0 over stdin and stdout."
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [mimiron.bindings :as bindings]
            [mimiron.jev :as jev]
            [mimiron.link :as link]
            [mimiron.roster :as roster]
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

;; Opt-in matching aid: while this flag file exists, each heard line and the
;; routing outcome go to a local file in /tmp. Off by default; never shipped.
(def ^:private speech-debug-flag "/tmp/mimiron-speech-debug")
(def ^:private speech-debug-file "/tmp/mimiron-speech.log")

(defn- debug-speech! [heard before after actions]
  (when (.exists (java.io.File. ^String speech-debug-flag))
    (try
      (let [f (java.io.File. ^String speech-debug-file)]
        (when-not (.exists f)
          (.createNewFile f)
          (.setReadable f false false) (.setReadable f true true)
          (.setWritable f false false) (.setWritable f true true))
        (spit f (str (java.time.LocalTime/now) " heard=" (pr-str heard)
                     " channel=" (:channel before) "->" (:channel after)
                     " actions=" (mapv :type actions) "\n")
              :append true))
      (catch Exception _ nil))))

(defn- notify! [method params] (send! {:method method :params params}))

(defn- whisper-names
  "Who \"whisper <name>\" may pick: the group (strip, else saved overflow) and guild."
  [world]
  (let [frame (get-in world [:link :frame])]
    (concat (if (and frame (not (:overflow frame))) (:party frame) (:link-names world))
            (:guild-names world))))

(defn- route
  "Applies a speech, button or pad input event in game; returns [world actions]."
  [{:keys [state bound shape] :as world} method params]
  (let [[s actions] (case method
                      "speech" (router/speech state bound (:text params "") shape
                                              (whisper-names world))
                      "button" (router/button state :request (:name params)
                                              (= "pressed" (:state params)))
                      "pad_input" (router/button state :pad (:button params)
                                                 (= "pressed" (:state params))))]
    (when (= method "speech") (debug-speech! (:text params "") state s actions))
    [(assoc world :state s) actions]))

;; Core features Mimiron may use. A strict core logs and drops unknown methods, and
;; counts them toward a plugin restart, so a notice stays unsent until the core accepts
;; it. "screen_probe" turns on when initialize advertises it in `options.screen_probe`
;; (docs/notes/screen-probe-contract.md). Add "speech.hints" (dictation hotwords,
;; CAPABILITIES.md) here when seshat says it's live.
(def live-features #{})

(defn- advertised
  "Features the core offers in initialize `options`."
  [params]
  (let [options (:options params)]
    (cond-> #{}
      (and (map? options) (some? (:screen_probe options)) (not (false? (:screen_probe options))))
      (conj "screen_probe"))))

(defn- live? [world feature] (contains? (:live-features world) feature))

(defn- handle
  "Applies one core message to `world`; returns the next world. Outside the
  game Mimiron is inert: events change nothing and every reply is empty."
  [world {:keys [id method params]}]
  (case method
    "initialize" (let [world (update world :live-features into (advertised params))]
                   (send! {:id id :result {:protocol_version 0 :encoding "json"
                                           :events ["button" "focus" "speech"]}})
                   ;; Ask tehuti to sample the add-on's strip, once the core supports it.
                   (when (live? world "screen_probe")
                     (notify! "screen_probe.watch" link/watch))
                   (assoc world :ready true))
    "screen_probe"
    ;; Only after asking for it, and only in game: a strip seen elsewhere is ignored.
    (if (and (:game world) (live? world "screen_probe"))
      (let [[l actions events] (link/observe (:link world) (:cells params) (:state world)
                                             ((:clock world)))
            world (cond-> (assoc world :link l)
                    (:new-session events) (merge ((:reload-saved world))))]
        (when (seq actions) (notify! "actions" {:actions actions}))
        world)
      world)
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
    [(router/overrides (:state world))
     (let [parts (remove nil? [(router/status (:state world)) (link/status (:link world))])]
       (when (seq parts) (str/join " · " parts)))]
    [[] (when (:launcher world) "Start WoW with wow-forever")]))

(def ^:private known-methods
  #{"initialize" "focus" "speech" "button" "pad_input" "screen_probe"})

;; Tehuti merges plugin and context hotwords under one cap of 64, so Mimiron
;; spends at most half and sends only what the current mode can use.
(def hint-budget 32)

(defn hotwords
  "Words to prime dictation with, by mode. With no channel, or while switching, the channel
  names (what the player is about to say). While chatting, the people the channel reaches:
  the group (strip, else saved overflow) on party, raid, battleground, say, yell and general;
  guild names on guild. Nothing outside the game, so other apps' dictation stays clean."
  [world]
  (let [{:keys [channel switching]} (:state world)
        frame (get-in world [:link :frame])
        party (if (and frame (not (:overflow frame))) (:party frame) (:link-names world))]
    (roster/hotwords (cond (not (:game world)) []
                           (or (nil? channel) switching) router/channels
                           (= "guild" channel) (:guild-names world)
                           :else party)
                     hint-budget)))

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
        (notify! "status" {:text s}))
      (let [h (hotwords after)]
        ;; The notice replaces the plugin's list, so an empty one clears it.
        (when (and (live? after "speech.hints")
                   (if fresh (seq h) (not= h (hotwords before))))
          (notify! "speech.hints" {:hotwords h}))))))

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
         "screen_probe" (sequential? (:cells params))
         true)))

(defn run
  ([lines bound] (run lines bound identity))
  ([lines bound shape] (run lines bound shape []))
  ([lines bound shape guild-names]
   (run lines bound shape guild-names {}))
  ([lines bound shape guild-names opts]
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
          (merge {:state router/initial :bound bound :shape shape :guild-names guild-names
                  :link link/initial :link-names [] :live-features live-features
                  :clock #(System/currentTimeMillis)
                  :reload-saved (constantly {})
                  :ready false :game false}
                 opts)
          lines)))

(defn -main [& _]
  (let [bound (try
                (if-let [cache (System/getenv "MIMIRON_BINDINGS")]
                  (bindings/load-bindings nil cache)
                  (bindings/load-bindings (bindings/default-wtf-root)))
                (catch Exception e
                  (error! (.getSimpleName (class e)) nil 0)
                  {}))
        ;; Guild names (opt-in add-on toggle) wait in memory until tehuti accepts
        ;; plugin hotwords (live-features); they are never logged or sent today.
        wtf (bindings/default-wtf-root)
        saved (roster/load-saved wtf)]
    (run (read-lines *in*) bound jev/shape (:guild saved)
         {:link-names (:link saved)
          ;; Evaluated on each new add-on session: the reload just wrote the file.
          :reload-saved #(let [s (roster/load-saved wtf)]
                           {:guild-names (:guild s) :link-names (:link s)})})))
