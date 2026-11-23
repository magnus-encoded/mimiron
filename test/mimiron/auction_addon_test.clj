(ns mimiron.auction-addon-test
  "The add-on side of auction phrases: addon/Mimiron/Auction.lua fills one field per
  `/mimiron ah` line and never buys, bids or posts (fair-play exception 3)."
  (:require [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [mimiron.auction :as a]))

(def ^:private lua (slurp "addon/Mimiron/Auction.lua"))

(deftest the-addon-never-commits-a-purchase-bid-or-listing
  ;; The Blizzard calls behind the Buy Now, Bid, Post and confirm buttons.
  (doseq [call ["PlaceBid" "StartCommoditiesPurchase" "ConfirmCommoditiesPurchase" "PostItem"
                "PostCommodity" "BuyoutButton" "BidButton" "PostButton" "BuyButton" ":Click("
                "StaticPopup" "print(" "MimironDB"]]
    (is (not (str/includes? lua call)) call)))

(deftest every-drafted-verb-has-an-addon-verb
  (doseq [id (a/fills)]
    (is (str/includes? lua (str "function verbs." (name id) "(")) id))
  (is (str/includes? (slurp "addon/Mimiron/Mimiron.lua") "MimironAuction.run"))
  (is (some #{"Auction.lua"} (str/split-lines (slurp "addon/Mimiron/Mimiron.toc")))))

(defn- lua51 []
  (try (when (zero? (:exit (sh/sh "lua5.1" "-v"))) "lua5.1")
       (catch java.io.IOException _ nil)))

(deftest each-verb-fills-its-field-under-a-stub-house
  ;; Runs where lua5.1 is installed (the test machine); CI without Lua skips it.
  (if-let [exe (lua51)]
    (let [{:keys [exit out]} (sh/sh exe "test/fixtures/ah-fill.lua" "addon/Mimiron/Auction.lua")]
      (is (zero? exit) out)
      (is (str/starts-with? out "ok ") out))
    (println "auction-addon-test: lua5.1 not found, stub-house run skipped")))

(deftest focused-text-box-fill-under-stub-ui
  (is (str/includes? (slurp "addon/Mimiron/Mimiron.lua") "MimironFill.focused(fill)"))
  (is (some #{"Fill.lua"} (str/split-lines (slurp "addon/Mimiron/Mimiron.toc"))))
  (if-let [exe (lua51)]
    (let [{:keys [exit out]} (sh/sh exe "test/fixtures/focused-fill.lua")]
      (is (zero? exit) out))
    (is true "lua5.1 not installed; run fixture on test machine")))
