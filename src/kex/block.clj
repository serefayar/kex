(ns kex.block
  (:require [kex.crypto :as c]))

(defn- sign-block
  "Signs a block payload. Returns the payload with :hash and :sig assoc'd."
  [payload privkey]
  (let [bytes (c/encode-block payload)
        hash  (c/sha256 bytes)
        sig   (c/sign privkey hash)]
    (assoc payload :hash hash :sig sig)))

(defn authority-block
  "Creates and signs the first block of a token chain.
   Returns a block map with :facts, :rules, :checks, :prev nil, :hash, :sig."
  [facts rules checks privkey]
  (sign-block {:facts facts :rules rules :checks checks :prev nil} privkey))

(defn delegated-block
  "Creates and signs a block that extends an existing chain.
   Links to prev-block via :prev hash."
  [prev-block facts rules checks privkey]
  (sign-block {:facts facts :rules rules :checks checks :prev (:hash prev-block)} privkey))

(defn verify-chain
  "Verifies hashes, signatures, and chain linkage for a block sequence."
  [blocks pubkey]
  (loop [[b & rest] blocks
         prev-hash  nil]
    (if (nil? b)
      true
      (let [{:keys [prev hash sig]} b
            payload (dissoc b :hash :sig)
            bytes   (c/encode-block payload)
            calc    (c/sha256 bytes)]
        (and
         (= prev prev-hash)
         (c/bytes= hash calc)
         (c/verify pubkey hash sig)
         (recur rest hash))))))
