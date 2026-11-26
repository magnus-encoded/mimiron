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


(deftest open-chat-with-slash-binding
  (is (= {:type "key" :key "slash"} (b/key-for {} "OPENCHATSLASH")))
  (is (= {:type "key" :key "j" :mods ["ctrl"]}
         (b/key-for (b/parse "bind CTRL-J OPENCHATSLASH") "OPENCHATSLASH")))
  (is (nil? (b/key-for {"/" "NONE"} "OPENCHATSLASH"))))
(deftest unusable-cache-is-safe
  (doseq [relative ["Account/A/bindings-cache.wtf"
                    "Account/A/Realm/Character/bindings-cache.wtf"]
          content ["" "garbled\u0000\ufffd\nnot a binding"]]
    (let [root (temp-dir)
          file (io/file root relative)]
      (io/make-parents file)
      (spit file content)
      (is (= {} (b/load-bindings root))))))

(deftest wow-modifier-syntax
  (doseq [[prefix mods] [["SHIFT-" ["shift"]]
                         ["CTRL-" ["ctrl"]]
                         ["ALT-" ["alt"]]
                         ["SHIFT-CTRL-ALT-" ["shift" "ctrl" "alt"]]
                         ["ALT-CTRL-SHIFT-" ["alt" "ctrl" "shift"]]]]
    (is (= {:type "key" :key "j" :mods mods}
           (b/key-action (str prefix "J"))))
    (is (= {:type "key" :key "minus" :mods mods}
           (b/key-action (str prefix "-")))))
  (is (= {"SHIFT-CTRL-ALT-J" "OPENALLBAGS"}
         (b/parse "bind SHIFT-CTRL-ALT-J OPENALLBAGS
"))))

(deftest pad-bindings-are-preserved-but-never-emulated-as-keys
  ;; Tehuti's plugin key action cannot emit native gamepad inputs.
  (doseq [pad ["PAD1" "PAD2" "PAD3" "PAD4" "PADSYSTEM" "PADBACK" "PADFORWARD"
               "PADLSHOULDER" "PADRSHOULDER" "PADLTRIGGER" "PADRTRIGGER"
               "PADLSTICK" "PADRSTICK" "PADDUP" "PADDDOWN" "PADDLEFT" "PADDRIGHT"]
          prefix ["" "SHIFT-" "CTRL-" "ALT-"]]
    (let [key (str prefix pad)
          bound (b/parse (str "bind B NONE
bind " key " OPENALLBAGS
bind J OPENALLBAGS
"))]
      (is (= "OPENALLBAGS" (get bound key)))
      (is (nil? (b/key-action key)))
      (is (= {:type "key" :key "j"} (b/key-for bound "OPENALLBAGS")))))
  (is (nil? (b/key-for {"B" "NONE" "PAD1" "OPENALLBAGS"} "OPENALLBAGS"))))

(deftest explicit-character-cache-selects-one-binding-set
  (let [root (io/file (temp-dir) "_forever_" "WTF")
        account (io/file root "Account/A/bindings-cache.wtf")
        char-a (io/file root "Account/A/Realm/One/bindings-cache.wtf")
        char-b (io/file root "Account/A/Realm/Two/bindings-cache.wtf")]
    (doseq [[file text] [[account "bind B NONE
bind M TOGGLEWORLDMAP
"]
                         [char-a "bind ALT-J TOGGLEWORLDMAP
"]
                         [char-b "bind PAD1 TOGGLEWORLDMAP
"]]]
      (io/make-parents file)
      (spit file text))
    (.setLastModified char-b (+ 10000 (.lastModified account)))
    (is (= {"M" "TOGGLEWORLDMAP" "B" "NONE"} (b/load-bindings root))
        "a newer character cache does not identify the logged-in character")
    (is (= {"ALT-J" "TOGGLEWORLDMAP"} (b/load-bindings root char-a)))
    (is (= {"PAD1" "TOGGLEWORLDMAP"} (b/load-bindings nil char-b)))
    (is (= {"M" "TOGGLEWORLDMAP" "B" "NONE"} (b/load-bindings root account)))
    (doseq [text ["" "garbled"]]
      (spit char-a text)
      (is (= {} (b/load-bindings root char-a)) "never merge another binding set"))
    (is (= {} (b/load-bindings root (io/file root "missing.wtf"))))))

(deftest unknown-character-never-borrows-character-only-cache
  (let [root (temp-dir)
        cache (io/file root "Account/A/Realm/One/bindings-cache.wtf")]
    (io/make-parents cache)
    (spit cache "bind J OPENALLBAGS
")
    (is (= {} (b/load-bindings root)))))

(deftest screenshot-default-respects-rebinding
  (is (= {:type "key" :key "print_screen"} (b/key-for {} "SCREENSHOT")))
  (is (nil? (b/key-for {"PRINTSCREEN" "NONE"} "SCREENSHOT")))
  (is (nil? (b/key-for {"PRINTSCREEN" "OPENALLBAGS"} "SCREENSHOT"))))

(deftest parse-keeps-only-key-to-action-lines
  ;; Retail's bindings-cache.wtf format. Spell, macro, item and click bindings name the player's
  ;; own things, so they are dropped with every other line: only `bind KEY ACTION` survives.
  (is (= {"ESCAPE" "TOGGLEGAMEMENU" "W" "MOVEFORWARD" "ENTER" "OPENCHAT" "/" "OPENCHATSLASH"
          "SHIFT-1" "ACTIONBUTTON1" "C" "TOGGLECHARACTER0" "F7" "TOGGLEQUESTLOG" "L" "NONE"
          "PAD1" "TOGGLETALENTS" "ALT-N" "TOGGLETALENTS"}
         (b/parse (slurp "test/fixtures/bindings-cache.wtf"))))
  (is (= {} (b/parse "BINDINGMODE 0\nbind CTRL-F SPELL Fireball\nbind G MACRO My plan\n"))))

(deftest fixture-rebinds-reach-the-voice-actions
  (let [bound (b/parse (slurp "test/fixtures/bindings-cache.wtf"))]
    (is (= {:type "key" :key "f7"} (b/key-for bound "TOGGLEQUESTLOG")) "the player's key wins")
    (is (= {:type "key" :key "n" :mods ["alt"]} (b/key-for bound "TOGGLETALENTS")) "PAD1 skipped")
    (is (= {:type "key" :key "c"} (b/key-for bound "TOGGLECHARACTER0")))
    (is (= {:type "key" :key "m"} (b/key-for bound "TOGGLEWORLDMAP")) "unlisted: the default")))

(deftest an-oversized-cache-is-not-read
  (let [root (temp-dir)
        file (io/file root "Account" "A" "bindings-cache.wtf")]
    (io/make-parents file)
    (spit file (apply str "bind J OPENALLBAGS\n" (repeat (inc b/max-cache-bytes) " ")))
    (is (= {} (b/load-bindings root)))))

(deftest watcher-rereads-only-when-the-file-changes
  (let [root (temp-dir)
        file (io/file root "Account" "A" "bindings-cache.wtf")
        current (b/watcher #(b/cache-file root nil))]
    (is (= {} (current)) "no cache yet: defaults")
    (io/make-parents file)
    (spit file "bind J OPENALLBAGS\n")
    (.setLastModified file 100000)
    (is (= {"J" "OPENALLBAGS"} (current)) "a new file is picked up")
    (spit file "bind K OPENALLBAGS\n")
    (.setLastModified file 100000)
    (is (= {"J" "OPENALLBAGS"} (current)) "same size and time: not read again")
    (spit file "bind F9 OPENALLBAGS\n")
    (.setLastModified file 200000)
    (is (= {"F9" "OPENALLBAGS"} (current)) "a rewrite is read")
    (.delete file)
    (is (= {} (current)) "a removed file falls back to the defaults")))

(deftest watcher-follows-the-explicit-file
  (let [root (temp-dir)
        file (io/file root "Account" "A" "Realm" "One" "bindings-cache.wtf")
        current (b/watcher #(b/cache-file root (str file)))]
    (io/make-parents file)
    (spit file "bind ALT-J TOGGLEWORLDMAP\n")
    (is (= {"ALT-J" "TOGGLEWORLDMAP"} (current)))))

(deftest watcher-never-writes
  (let [root (temp-dir)
        file (io/file root "Account" "A" "bindings-cache.wtf")]
    (io/make-parents file)
    (spit file "bind J OPENALLBAGS\n")
    (.setLastModified file 100000)
    ((b/watcher #(b/cache-file root nil)))
    (is (= 100000 (.lastModified file)))
    (is (= ["bindings-cache.wtf"] (vec (.list (.getParentFile file)))))))
