(ns mimiron.window-fixture
  (:require [clojure.edn :as edn]
            [mimiron.link :as link]))

(defn cells [situation]
  (let [cells (edn/read-string (slurp "test/fixtures/link-strip-solo.edn"))
        nibbles (mapcat #(map (fn [v] (quot v 17)) %) cells)
        bytes (mapv (fn [[a b]] (+ (* 16 a) b)) (partition 2 nibbles))
        end (+ 7 (bytes 6))
        bytes (assoc bytes 5 situation)
        bytes (assoc bytes end (mod (reduce + (subvec bytes 0 end)) 256))]
    (mapv (fn [ns] (mapv #(* 17 %) ns))
          (partition 3 (mapcat (fn [b] [(quot b 16) (mod b 16)]) bytes)))))

(defn open-state [state]
  (assoc state :windows (:windows (link/decode (cells 120)))))
