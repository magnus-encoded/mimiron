(ns mimiron.screen-probe-test
  "End to end against a fake core: initialize, focus and `screen_probe` notices in, the
  plugin's JSON lines out. The strips are drawn by addon/Mimiron/Link.lua
  (test/fixtures/draw-strip.lua). docs/notes/screen-probe-contract.md is the spec."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [mimiron.main :as main]))

(defn- fixture [name] (edn/read-string (slurp (str "test/fixtures/" name ".edn"))))

;; 20 raid names, too many for the strip: "p=+", wants a reload, out of combat, session 7.
(def overflow (fixture "link-strip-overflow"))
;; The same raid after the reload wrote the names to disk: session 8, no reload wanted.
(def overflow-saved (fixture "link-strip-overflow-saved"))
;; A loading screen: the strip is gone.
(def blank (vec (repeat 64 [0 0 0])))

(defn- dim
  "An unfocused tile: the compositor dims every channel to about two thirds."
  [cells]
  (mapv (fn [c] (mapv #(Math/round (* 0.67 %)) c)) cells))

(def ^:private reload-keys
  [{:type "key" :key "enter"} {:type "text" :text "/reload"} {:type "key" :key "enter"}])

(defn- core-init [options]
  {:id 0 :method "initialize"
   :params (cond-> {:protocol_version 0 :encoding "json" :core_version "0.0.0"}
             options (assoc :options options))})

(def ^:private wow {:method "focus" :params {:app_id "steam_proton" :title "World of Warcraft"}})
(def ^:private elsewhere {:method "focus" :params {:app_id "steam_proton" :title "Elden Ring"}})
(defn- probe [cells] {:method "screen_probe" :params {:cells cells}})

(defn- fake-core
  "Plays [ms message] pairs into Mimiron with the clock at ms. Returns
  {:out [[ms json] ...] :reads n}, where reads counts SavedVariables re-reads."
  [script]
  (let [now (atom 0)
        reads (atom 0)
        out (atom [])
        lines (letfn [(step [s]
                        (lazy-seq
                         (when-let [[ms m] (first s)]
                           (reset! now ms)
                           (cons (json/write-value-as-string (assoc m :jsonrpc "2.0"))
                                 (step (rest s))))))]
                (step script))
        printed (with-out-str
                  (binding [*err* (java.io.StringWriter.)]
                    (main/run lines {} identity []
                              {:clock #(deref now)
                               :reload-saved #(do (swap! reads inc) {:link-names ["Aerin"]})})))]
    (doseq [l (str/split-lines printed)]
      (swap! out conj (json/read-value l (json/object-mapper {:decode-key-fn true}))))
    {:out @out :reads @reads}))

(defn- method [out m] (filter #(= m (:method %)) out))
(defn- statuses [out] (mapv #(get-in % [:params :text]) (method out "status")))
(defn- reloads [out] (filter #(= reload-keys (get-in % [:params :actions])) (method out "actions")))

(defn- heartbeat
  "A sample every second from `from` to `to` ms, as the contract's 1/s floor."
  [cells from to]
  (map (fn [ms] [ms (probe cells)]) (range from (inc to) 1000)))

(def ^:private reload-cycle
  (concat [[0 wow]]
          (heartbeat overflow 0 6000)
          [[7000 (probe blank)]]
          (heartbeat overflow-saved 9000 11000)))

(deftest a-core-without-screen-probe-changes-nothing
  (let [{:keys [out reads]} (fake-core (cons [0 (core-init nil)] reload-cycle))]
    (is (empty? (method out "screen_probe.watch")))
    (is (empty? (method out "actions")))
    (is (every? nil? (statuses out)))
    (is (zero? reads))))

(deftest unknown-options-do-not-turn-it-on
  (let [{:keys [out]} (fake-core (cons [0 (core-init {:action_submission "submit_actions"})]
                                       reload-cycle))]
    (is (empty? (method out "screen_probe.watch")))
    (is (empty? (method out "actions")))))

(defn- reload-cycle-as-seen [cells-of]
  (let [script (concat [[0 (core-init {:screen_probe "screen_probe.watch"})] [0 wow]]
                       (heartbeat (cells-of overflow) 0 6000)
                       [[7000 (probe blank)]]
                       (heartbeat (cells-of overflow-saved) 9000 11000))]
    (fake-core script)))

(deftest a-core-with-screen-probe-gets-the-watch-and-a-safe-reload
  (let [{:keys [out reads]} (reload-cycle-as-seen identity)]
    (testing "the watch, exactly once, right after the initialize reply"
      (is (= {:jsonrpc "2.0" :method "screen_probe.watch" :params {:x 2 :y 2 :step 4 :count 64}}
             (second out)))
      (is (= 1 (count (method out "screen_probe.watch")))))
    (testing "warned on the powerline, then one /reload after 5 s, then cleared by the new session"
      (is (= [nil "reload soon to sync" "syncing (reload)" nil] (statuses out)))
      (is (= 1 (count (reloads out)))))
    (testing "each session seen re-reads the saved names: the first strip, then after the reload"
      (is (= 2 reads)))))

(deftest a-dimmed-window-decodes-the-same
  (let [plain (reload-cycle-as-seen identity)
        dimmed (reload-cycle-as-seen dim)]
    (is (= (:out plain) (:out dimmed)))
    (is (= 1 (count (reloads (:out dimmed)))))))

(deftest strips-outside-the-game-are-ignored
  (let [{:keys [out reads]}
        (fake-core (concat [[0 (core-init {:screen_probe "screen_probe.watch"})] [0 elsewhere]]
                           (heartbeat overflow 0 6000)
                           (heartbeat overflow-saved 7000 8000)))]
    (is (empty? (method out "actions")))
    (is (not-any? #(and % (str/includes? % "reload")) (statuses out)))
    (is (zero? reads))))

(deftest malformed-cells-are-dropped
  (let [{:keys [out]}
        (fake-core [[0 (core-init {:screen_probe "screen_probe.watch"})] [0 wow]
                    [0 (probe (pop overflow))]
                    [1000 {:method "screen_probe" :params {:cells "nonsense"}}]
                    [2000 (probe (assoc overflow 5 [0 0 0]))]
                    [8000 (probe (pop overflow))]])]
    (is (empty? (method out "actions")))
    (is (every? nil? (statuses out)))))
