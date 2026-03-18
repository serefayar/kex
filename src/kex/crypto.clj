(ns kex.crypto
  (:import
   [java.nio.charset StandardCharsets]
   [java.security MessageDigest Signature KeyPairGenerator]
   [java.util Arrays]))

(defn canonical
  "Normalizes data for deterministic serialization.
   Sorts map keys, normalizes sets, recurses into nested structures."
  [x]
  (cond
    (map? x)
    (into (sorted-map)
          (for [[k v] x]
            [k (canonical v)]))

    (set? x)
    (mapv canonical (sort x))

    (vector? x)
    (mapv canonical x)

    (seq? x)
    (mapv canonical x)

    :else x))


(defn bytes=
  "Content equality for byte arrays."
  [^bytes a ^bytes b]
  (Arrays/equals a b))

(defn sha256
  "SHA-256 digest of a byte array."
  [^bytes data]
  (.digest (MessageDigest/getInstance "SHA-256") data))

(defn generate-keypair
  "Generates a java.security.KeyPair for the given algorithm."
  [alg]
  (let [kpg (KeyPairGenerator/getInstance alg)]
    (.generateKeyPair kpg)))

(defn sign
  "Ed25519 signature of data using private key."
  [^java.security.PrivateKey priv ^bytes data]
  (let [sig (Signature/getInstance "Ed25519")]
    (.initSign sig priv)
    (.update sig data)
    (.sign sig)))

(defn verify
  "Verifies an Ed25519 signature against data and public key."
  [^java.security.PublicKey pub ^bytes data ^bytes signature]
  (let [sig (Signature/getInstance "Ed25519")]
    (.initVerify sig pub)
    (.update sig data)
    (.verify sig signature)))

(defn encode-block
  "Deterministic canonical byte encoding of a block payload."
  [block]
  (.getBytes
   (pr-str (canonical block))
   StandardCharsets/UTF_8))
