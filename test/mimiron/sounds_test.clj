(ns mimiron.sounds-test
  (:require [clojure.test :refer [deftest is]]
            [mimiron.sounds :as s]))

(deftest swapped-consonants-share-a-key
  (is (= (s/sound-key "guild") (s/sound-key "guilt") (s/sound-key "gold") (s/sound-key "killed")))
  (is (= (s/sound-key "yell") (s/sound-key "y'all") (s/sound-key "yale")))
  (is (= (s/sound-key "chat") (s/sound-key "shut")))
  (is (not= (s/sound-key "chat") (s/sound-key "that"))))

(deftest closest-needs-a-unique-near-target
  (let [targets {"say" "say" "raid" "raid" "reply" "reply" "guild" "guild"}]
    (is (= "guild" (s/closest targets "gil")))
    (is (= "raid" (s/closest targets "ray")))
    (is (= "say" (s/closest targets "safe")))
    (is (nil? (s/closest targets "healer")))
    (is (nil? (s/closest targets "rape")) "as near to raid as to reply")
    (is (nil? (s/closest targets "")))))
