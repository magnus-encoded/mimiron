(ns mimiron.jev-test
  (:require [clojure.test :refer [deftest is]]
            [mimiron.jev :as jev]
            [mimiron.router :as r]))

(deftest styles-keep-the-same-words
  (is (= "For The Horde!" (jev/style "for the horde." :shout)))
  (is (= "Glory To The Horde!" (jev/style "glory to the horde" :shout)))
  (is (= "FOR THE HORDE!" (jev/style "for the horde." :scream)))
  (is (= "meet me at the bank." (jev/style "meet me at the bank." :plain)))
  (is (= "meet me at the bank." (jev/style "meet me at the bank." nil))))

(deftest only-yell-is-restyled
  (let [shout #(jev/style % :shout)
        said (fn [channel] (-> (reduce (fn [s t] (first (r/speech s {} t))) r/initial
                                       [(str "chat with " channel)])
                               (r/speech {} "for the horde." shout) second last :text))]
    (is (= "/y For The Horde!" (said "yell")))
    (is (= "/s for the horde." (said "say")))))

(deftest a-yell-draft-extension-is-restyled-too
  (let [s (first (r/speech (first (r/speech r/initial {} "chat with yell")) {} "for the horde." identity))]
    (is (= [{:type "text" :text " Run!"}]
           (second (r/speech s {} "run." #(jev/style % :shout)))))))

(deftest without-a-key-the-text-is-left-as-heard
  (when-not (System/getenv "TYPESAFE_API_KEY")
    (is (nil? (jev/loudness "for the horde.")))))
