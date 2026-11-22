(ns mimiron.roster-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [mimiron.roster :as roster]))

(def saved-on
  "MimironDB = {\n[\"guildnames\"] = true,\n[\"roster\"] = {\n\"Arthas\",\n\"Jaina\",\n\"Thrall\",\n},\n[\"probe\"] = {\n[\"x\"] = \"nope\",\n},\n}\n")

(deftest names-only-when-the-toggle-is-on
  (is (= ["Arthas" "Jaina" "Thrall"] (roster/parse saved-on)))
  (is (= [] (roster/parse (str/replace saved-on "true" "false"))))
  (is (= [] (roster/parse "MimironDB = {\n[\"roster\"] = {\n\"Leftover\",\n},\n}\n")))
  (is (= [] (roster/parse ""))))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory "mimiron" (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest load-reads-the-newest-account-file-and-never-writes
  (let [root (temp-dir)
        f (io/file root "Account" "A" "SavedVariables" "Mimiron.lua")]
    (io/make-parents f)
    (spit f saved-on)
    (is (= ["Arthas" "Jaina" "Thrall"] (roster/load-roster root)))
    (is (= saved-on (slurp f)))
    (is (= [] (roster/load-roster nil)))
    (is (= [] (roster/load-roster (temp-dir))))))

(deftest hotwords-are-the-names-capped
  (is (= ["Arthas" "Jaina"] (roster/hotwords ["Arthas" "Jaina"] 64)))
  (is (= 3 (count (roster/hotwords (map str (range 100)) 3)))))

(deftest a-heard-name-matches-a-known-name
  (let [names ["Arthas" "Jaína" "Thrall-Silvermoon" "Mal Ganis"]]
    (doseq [[heard name] [["arthas" "Arthas"] ["jaina" "Jaína"] ["thrall" "Thrall-Silvermoon"]
                          ["thrall silvermoon" "Thrall-Silvermoon"] ["mal ganis" "Mal Ganis"]
                          ["malganis" "Mal Ganis"]]]
      (is (= name (roster/match-name names heard)) heard))
    (doseq [heard ["art" "arthas jaina" "" "nobody"]]
      (is (nil? (roster/match-name names heard)) heard))
    (is (nil? (roster/match-name [] "arthas")))))
