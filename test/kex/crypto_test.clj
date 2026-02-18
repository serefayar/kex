(ns kex.crypto-test
  (:require [kex.crypto :as sut]
            [kex.block :as block]
            [clojure.test :as t]))

(t/deftest canonical-map-order-test
  (t/testing "map key order does not affect canonical form"
    (t/is (= (sut/canonical {:b 2 :a 1})
             (sut/canonical {:a 1 :b 2})))))

(t/deftest canonical-nested-structure-test
  (t/testing "nested structures are canonicalized recursively"
    (t/is (= (sut/canonical {:z #{3 2 1}
                             :a [{:y 2 :x 1}]})
             (sut/canonical {:a [{:x 1 :y 2}]
                             :z #{1 2 3}})))))

(t/deftest canonical-vector-order-preserved
  (t/testing "vector order is preserved"
    (t/is (not=
           (sut/canonical [1 2 3])
           (sut/canonical [3 2 1])))))


(t/deftest sha256-deterministic-test
  (t/testing "same input yields same hash"
    (let [data (.getBytes "hello")]
      (t/is (sut/bytes=
            (sut/sha256 data)
            (sut/sha256 data))))))

(t/deftest sign-and-verify-test
  (t/testing "valid signature verifies"
    (let [kp   (sut/generate-keypair)
          msg  (.getBytes "important-data")
          sig  (sut/sign (.getPrivate kp) msg)]
      (t/is (true?
            (sut/verify
              (.getPublic kp)
              msg
              sig))))))

(t/deftest signature-fails-on-data-change
  (t/testing "signature fails if data changes"
    (let [kp   (sut/generate-keypair)
          msg  (.getBytes "important-data")
          sig  (sut/sign (.getPrivate kp) msg)]
      (t/is (false?
            (sut/verify
              (.getPublic kp)
              (.getBytes "tampered-data")
              sig))))))

(t/deftest signature-fails-with-wrong-key
  (t/testing "signature fails with wrong public key"
    (let [kp1  (sut/generate-keypair)
          kp2  (sut/generate-keypair)
          msg  (.getBytes "important-data")
          sig  (sut/sign (.getPrivate kp1) msg)]
      (t/is (false?
            (sut/verify
              (.getPublic kp2)
              msg
              sig))))))


(t/deftest authority-block-test
  (t/testing "authority block is self-contained and signed"
    (let [kp   (sut/generate-keypair)
          blk  (block/authority-block
                 [[:user "alice"]]
                 []
                 []
                 (.getPrivate kp))]
      (t/is (some? (:hash blk)))
      (t/is (some? (:sig blk)))
      (t/is (nil? (:prev blk))))))

(t/deftest delegated-block-links-to-previous
  (t/testing "delegated block links to previous hash"
    (let [kp   (sut/generate-keypair)
          b0   (block/authority-block
                 [[:user "alice"]]
                 []
                 []
                 (.getPrivate kp))
          b1   (block/delegated-block
                 b0
                 []
                 []
                 []
                 (.getPrivate kp))]
      (t/is (= (:hash b0) (:prev b1))))))

(t/deftest verify-chain-valid
  (t/testing "valid block chain verifies"
    (let [kp   (sut/generate-keypair)
          pub  (.getPublic kp)
          b0   (block/authority-block
                 [[:user "alice"]]
                 []
                 []
                 (.getPrivate kp))
          b1   (block/delegated-block
                 b0
                 []
                 []
                 []
                 (.getPrivate kp))]
      (t/is (true?
            (block/verify-chain [b0 b1] pub))))))

(t/deftest verify-chain-fails-on-tampered-block
  (t/testing "tampered block breaks chain"
    (let [kp   (sut/generate-keypair)
          pub  (.getPublic kp)
          b0   (block/authority-block
                 [[:user "alice"]]
                 []
                 []
                 (.getPrivate kp))
          b1   (assoc
                 (block/delegated-block
                   b0
                   []
                   []
                   []
                   (.getPrivate kp))
                 :facts [[:user "mallory"]])]
      (t/is (false?
            (block/verify-chain [b0 b1] pub))))))

(t/deftest verify-chain-fails-on-reordered-blocks
  (t/testing "reordering blocks breaks chain"
    (let [kp   (sut/generate-keypair)
          pub  (.getPublic kp)
          b0   (block/authority-block
                 [[:user "alice"]]
                 []
                 []
                 (.getPrivate kp))
          b1   (block/delegated-block
                 b0
                 []
                 []
                 []
                 (.getPrivate kp))]
      (t/is (false?
            (block/verify-chain [b1 b0] pub))))))
