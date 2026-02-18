(ns kex.block
  (:require [kex.crypto :as c]))


(defn authority-block
  "Creates and signs the initial (authority) block of a token chain.

   Arguments:
     - `facts`        : vector of fact tuples
     - `rules`        : vector of rule maps
     - `checks`       : vector of check maps
     - `private-key`  : java.security.PrivateKey used for signing

   Returns:
     A block map containing:
     |||
     |:-|:-|
     |`:facts` | vector of fact tuples
     |`:rules` | vector of rule maps
     |`:checks`| vector of check maps
     |`:prev`  | (nil for authority block)
     |`:hash`  | (SHA-256 digest of the canonical payload)
     |`:sig`   | (cryptographic signature of the hash)"
  [facts rules checks privkey]
  (let [payload {:facts facts
                 :rules rules
                 :checks checks
                 :prev nil}
        bytes   (c/encode-block payload)
        hash    (c/sha256 bytes)
        sig     (c/sign privkey hash)]
    (assoc payload
           :hash hash
           :sig  sig)))


(defn delegated-block
  "Creates and signs a new block that extends an existing block chain.

   Arguments:
     - `previous-block` : the immediately preceding block in the chain
     - `facts`          : additional facts (optional, typically attenuating)
     - `rules`          : additional rules (optional)
     - `checks`         : additional checks (often used for restriction)

     - `private-key`    : java.security.PrivateKey used for signing

   Returns:
     A new block map containing:
     |||
     |:-|:-|
     |`:facts` | vector of fact tuples
     |`:rules` | vector of rule maps
     |`:checks`| vector of check maps
     |`:prev`  | (hash of the previous block)
     |`:hash`  | (SHA-256 digest of the canonical payload)
     |`:sig`   | (cryptographic signature of the hash)"
  [prev-block facts rules checks privkey]
  (let [payload {:facts facts
                 :rules rules
                 :checks checks
                 :prev (:hash prev-block)}
        bytes   (c/encode-block payload)
        hash    (c/sha256 bytes)
        sig     (c/sign privkey hash)]
    (assoc payload
           :hash hash
           :sig  sig)))


(defn verify-chain
  "Verifies the integrity and authenticity of a sequence of blocks.

   Arguments:
     - `blocks`     : vector of block maps in order (authority → latest)
     - `public-key` : java.security.PublicKey used for signature verification

   Returns:
     - `true`  if the entire chain is valid
     - `false` otherwise"
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
