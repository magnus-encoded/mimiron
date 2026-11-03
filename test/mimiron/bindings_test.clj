(ns mimiron.bindings-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [mimiron.bindings :as b]))

(deftest parse
  (is (= {"P" "NONE" "SHIFT-1" "FOO"} (b/parse "bind P NONE\nbind SHIFT-1 FOO\nbad line\n"))))

(deftest unbound-default-is-not-used
  (is (nil? (b/key-for {"M" "NONE"} "TOGGLEWORLDMAP"))))

(deftest unsupported-key-is-skipped-for-next-binding
  (is (= {:type "key" :key "f5"}
         (b/key-for (array-map "BUTTON4" "TOGGLESPELLBOOK" "F5" "TOGGLESPELLBOOK") "TOGGLESPELLBOOK"))))

(deftest key-translation
  (is (= {:type "key" :key "1" :mods ["shift"]} (b/key-action "SHIFT-1")))
  (is (= {:type "key" :key "page_up"} (b/key-action "PAGEUP")))
  (is (= {:type "key" :key "minus"} (b/key-action "-")))
  (is (= {:type "key" :key "minus" :mods ["ctrl"]} (b/key-action "CTRL--")))
  (is (nil? (b/key-action "NUMPAD0")))
  (is (nil? (b/key-action "BUTTON5"))))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory "mimiron" (make-array java.nio.file.attribute.FileAttribute 0))))

(deftest load-reads-newest-cache-and-never-writes
  (let [root (temp-dir)
        old (io/file root "Account" "OLD" "bindings-cache.wtf")
        new (io/file root "Account" "NEW" "bindings-cache.wtf")]
    (io/make-parents old) (io/make-parents new)
    (spit old "bind J TOGGLEWORLDMAP\n")
    (spit new "bind K TOGGLEWORLDMAP\n")
    (.setLastModified old 1000)
    (let [before (.lastModified new)]
      (is (= {"K" "TOGGLEWORLDMAP"} (b/load-bindings root)))
      (is (= before (.lastModified new)))
      (is (= ["bindings-cache.wtf"] (vec (.list (.getParentFile new))))))))

(deftest missing-cache-gives-empty
  (is (= {} (b/load-bindings (temp-dir)))))

(deftest wtf-path-from-launcher
  (is (= "/home/u/Games/battlenet/drive_c/World of Warcraft/_forever_/WTF"
         (b/wtf-root-from-launcher
          (str "export WINEPREFIX=\"$HOME/Games/battlenet\"\n"
               "cd \"$WINEPREFIX/drive_c/World of Warcraft/_forever_\" || exit 1\n")
          "/home/u"))))
