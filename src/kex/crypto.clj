(ns kex.crypto
  (:import
   [java.nio.charset StandardCharsets]
   [java.security MessageDigest Signature KeyPairGenerator]
   [java.util Arrays]))

(defn canonical
  "Transforms arbitrary Clojure data into a canonical form suitable
   for deterministic serialization.

   Ensures:
     - map keys are sorted
     - sets are normalized
     - nested structures are recursively canonicalized"
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
  "Compares two byte arrays for content equality.

   Returns `true` if the arrays contain identical bytes."
  [^bytes a ^bytes b]
  (Arrays/equals a b))

(defn sha256
  "Computes the SHA-256 hash of the given byte array.

   Returns a new byte array containing the 32-byte digest."
  [^bytes data]
  (.digest (MessageDigest/getInstance "SHA-256") data))

(defn generate-keypair
  "Helper to generate a new asymmetric keypair for signing and verification
   that, intended to be used by the authority issuing tokens.

   Returns a `java.security.KeyPair` instance containing:
     - a private key (used for signing blocks)
     - a public key  (used for verification)

   The algorithm must be consistent with the signing and verification
   functions."
  [alg]
  (let [kpg (KeyPairGenerator/getInstance alg)]
    (.generateKeyPair kpg)))

(defn sign
  "Signs the given byte array using the provided private key.

   Arguments:
     - `private-key`: a java.security.PrivateKey
     - `data`:        byte array to be signed

   Returns:
     - a byte array containing the signature

   The signing algorithm must match the verification algorithm
   used in `verify`."
  [^java.security.PrivateKey priv ^bytes data]
  (let [sig (Signature/getInstance "Ed25519")]
    (.initSign sig priv)
    (.update sig data)
    (.sign sig)))

(defn verify
  "Verifies that the given signature is valid for the provided data
   using the supplied public key.

   Arguments:
     - `public-key`: java.security.PublicKey
     - `data`:       byte array that was signed
     - `signature`:  byte array signature to verify

   Returns:
     - true  if the signature is valid
     - false otherwise

   If verification fails, the token must be rejected before any logical
   evaluation occurs."
  [^java.security.PublicKey pub ^bytes data ^bytes signature]
  (let [sig (Signature/getInstance "Ed25519")]
    (.initVerify sig pub)
    (.update sig data)
    (.verify sig signature)))

(defn encode-block
  "Encodes a block payload into a canonical byte representation.

   The encoding must be:
     - deterministic
     - stable across JVM runs
     - independent of map ordering"
  [block]
  (.getBytes
   (pr-str (canonical block))
   StandardCharsets/UTF_8))


