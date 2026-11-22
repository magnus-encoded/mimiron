(ns mimiron.link
  "Game link: state the add-on draws as a strip of cells, read back by tehuti.
  Owner exception to fair-play rule 4 (2026-11-11): Mimiron reads this strip, which
  only its own add-on draws (party names and a few flags, never chat). Also, under that
  exception, Mimiron may type /reload, not prompted by the player, when the add-on asks
  and it is safe. Pure functions; main.clj supplies the clock and does the wiring.
  Wire format: addon/Mimiron/Link.lua. Change both together."
  (:require [clojure.string :as str]))

(def cell 4)
(def cell-count 64)
(def byte-count 96)

;; What main.clj asks tehuti to sample: the centre of each cell, from the window's top-left.
(def watch {:x (quot cell 2) :y (quot cell 2) :step cell :count cell-count})

;; Voice flags are state only: voice chat on (an active channel in push-to-talk mode), whether
;; it goes through Discord, and the player's push-to-talk setting. Never members or audio.
(def ^:private flag-bits {:combat 1 :chat 2 :wants-reload 4 :busy 8
                          :voice 16 :voice-discord 32 :keep-dictation 64})

(defn- nibble [v] (-> (Math/round (/ (double v) 17.0)) long (max 0) (min 15)))

;; The situation byte: the group in bits 0-1, the auction house in bit 2. It sets Mimiron's own
;; mode (default chat channel, auction mode), never a game input.
(def ^:private groups [:solo :party :raid :battleground])
(def ^:private auction-bit 4)

;; Cell 0 always draws 'M' 0x3: nibbles 4, 13, 3, i.e. [68 221 51]. A uniformly dimmed
;; or brightened window (an unfocused tile, a gamma shift) is scaled back per channel from it.
(def ^:private anchor [68 221 51])

(defn- calibrate [cells]
  (let [[r g b] (first cells)
        gains (mapv (fn [want got] (if (pos? got) (/ (double want) got) 1.0)) anchor [r g b])]
    (if (every? #(< 0.5 % 4.0) gains)
      (mapv (fn [c] (mapv * (take 3 c) gains)) cells)
      cells)))

(defn- cells->bytes [cells]
  (->> cells
       (mapcat #(map nibble (take 3 %)))
       (partition 2)
       (mapv (fn [[hi lo]] (+ (* 16 hi) lo)))))

(defn- utf8 [bytes]
  (String. (byte-array (map unchecked-byte bytes)) "UTF-8"))

(declare decode-raw)

(defn decode
  "The add-on's frame from sampled [r g b] cells, or nil when it isn't there or is torn.
  Tries the colours as sampled, then rescaled from the anchor cell (a dimmed tile)."
  [cells]
  (when (and (sequential? cells) (= cell-count (count cells))
             (every? #(and (sequential? %) (<= 3 (count %)) (every? number? (take 3 %))) cells))
    (or (decode-raw cells) (decode-raw (calibrate cells)))))

(defn- decode-raw
  [cells]
  (when (and (sequential? cells) (= cell-count (count cells))
             (every? #(and (sequential? %) (<= 3 (count %)) (every? number? (take 3 %))) cells))
    (let [b (cells->bytes cells)
          length (b 6)
          end (+ 7 length)]
      (when (and (= 0x4D (b 0)) (= 0x32 (b 1)) (< end byte-count)
                 (= (b end) (mod (reduce + (subvec b 0 end)) 256)))
        ;; "p=Names", or "k=KEY;p=Names" with WoW's push-to-talk binding while voice chat is on.
        (when-let [[_ ptt-key names] (re-find #"^(?:k=([^;,]+);)?p=(.*)$" (utf8 (subvec b 7 end)))]
          (merge {:session (b 3) :revision (b 4)
                  :group (groups (bit-and 3 (b 5)))
                  :auction (pos? (bit-and auction-bit (b 5)))
                  :overflow (= "+" names)
                  :party (if (or (str/blank? names) (= "+" names)) [] (str/split names #","))
                  :ptt-key ptt-key}
                 (into {} (map (fn [[k bit]] [k (pos? (bit-and bit (b 2)))]) flag-bits))))))))

;; :last is the latest frame seen, kept over a loading screen where the strip is gone.
(def initial {:frame nil :last nil :session nil :warned-at nil :reloaded-at nil :reloading false})

;; A reload is noticeable, so it is announced first and spaced out.
(def warn-ms 5000)
(def min-gap-ms (* 10 60 1000))
;; A /reload that never landed (typing lost) stops counting as in flight.
(def reload-timeout-ms 60000)

(defn safe?
  "No reload mid-fight, over an open chat box, during a cast or shop window, or over a draft."
  [frame router-state]
  (not (or (:combat frame) (:chat frame) (:busy frame)
           (:draft router-state) (:switching router-state))))

(def ^:private reload-actions
  ;; The add-on reports the chat box closed, so Enter opens it and the second Enter runs the command.
  [{:type "key" :key "enter"} {:type "text" :text "/reload"} {:type "key" :key "enter"}])

(defn observe
  "One sampled strip at `now` (ms). Returns [link actions events], where events
  holds :new-session when the add-on reloaded and its SavedVariables should be read."
  [link cells router-state now]
  (let [frame (decode cells)
        ;; The strip vanishes on loading screens, so the session outlives a missing frame.
        new-session? (and frame (not= (:session frame) (:session link)))
        link (cond-> (assoc link :frame frame)
               frame (assoc :session (:session frame) :last frame)
               new-session? (assoc :reloading false :warned-at nil)
               (and (:reloading link) (> (- now (:reloaded-at link)) reload-timeout-ms))
               (assoc :reloading false))
        wants? (and frame (:wants-reload frame) (not (:reloading link)))
        recent? (and (:reloaded-at link) (< (- now (:reloaded-at link)) min-gap-ms))
        link (cond
               (not wants?) (assoc link :warned-at nil)
               (:warned-at link) link
               :else (assoc link :warned-at now))
        ready? (and wants? (not recent?) (safe? frame router-state)
                    (>= (- now (:warned-at link)) warn-ms))]
    [(if ready? (assoc link :reloading true :reloaded-at now :warned-at nil) link)
     (if ready? reload-actions [])
     (if new-session? #{:new-session} #{})]))

(defn status
  "A powerline note while a reload is pending, else nil."
  [link]
  (cond (:reloading link) "syncing (reload)"
        (:warned-at link) "reload soon to sync"))
