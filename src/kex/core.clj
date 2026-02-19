(ns kex.core
  (:require [kex.block :as block]
            [kex.datalog :as datalog]
            [kex.graph :as graph]
            [kex.crypto :as crypto]))

(defn new-keypair
  "Helper to generate a new asymmetric keypair for signing and verification
   
   Returns a `java.security.KeyPair` instance containing:
     - a private key (used for signing blocks)
     - a public key  (used for verification)
   
   Uses Ed25519 algoritm in this PoC"
  []
  (let [kp (crypto/generate-keypair "Ed25519")]
    {:priv (.getPrivate kp)
     :pub (.getPublic kp)}))

(defn issue
  "Creates a new authority token.

   Arguments:
     - payload map with:
       |||
       |:-|:-|
       | `:facts`  | vector of fact tuples
       | `:rules`  | vector of rule maps
       | `:checks` | vector of check maps (optional) 
     - opts map with:
         `:private-key` (required)

   Returns:
     A vector containing the initial signed authority block."
  [{:keys [facts rules checks] :as _blocks} {:keys [private-key] :as _opts}]
  (let [authority
        (block/authority-block
         (or facts [])
         (or rules [])
         (or checks [])
         private-key)]
    [authority]))

(defn attenuate
  "Appends a new delegated block to an existing token.

   Arguments:
     - `token`   : vector of blocks (authority → latest)
     - payload : map with optional keys:
       |||
       |:-|:-|
       |`:facts`| vector of fact tuples
       |`:rules`| vector of rule maps
       |`:checks`| vector of check maps (optional)
     - opts map with:
         `:private-key` (required)

   Returns:
     A new token vector with the additional delegated block appended."
  [token {:keys [facts rules checks] :as _blocks} {:keys [private-key] :as _opts}]
  (let [previous-block (peek token)
        new-block
        (block/delegated-block
         previous-block
         (or facts [])
         (or rules [])
         (or checks [])
         private-key)]
    (conj token new-block)))

(defn verify
  "Verifies the integrity and authenticity of a token.

   Arguments:
     - `token` : vector of blocks
     - `opts`  : map with:
        `:public-key` (required)

   Returns:
     - `true` if the block chain is valid
     - `false` otherwise

   Performs cryptographic verification only.
   It does not evaluate authorization logic.

   Logical evaluation must only be performed after this
   function returns true."
  [token {:keys [public-key] :as _opts}]
  (block/verify-chain token public-key))

(defn evaluate
  "Evaluates an already verified token against its internal checks.

   Arguments:
     - `token` : vector of blocks (binary form)
     - `opts`  : map with optional:
         :explain? (boolean)

   Returns:

    ```clojure
     {:valid?  boolean
      :explain explain-tree (when :explain? true)}
    ```
   It does:
     1. Extracts logical content (:facts, :rules, :checks)
     2. Delegates evaluation to the core engine
     3. Returns the authorization decision

   **IMPORTANT**:
     Assumes the token has already been
     cryptographically verified."
  [token & {:keys [explain?]}]
  (let [core-token
        {:blocks
         (mapv #(select-keys % [:facts :rules :checks])
               token)}]
    (datalog/eval-token core-token :explain? explain?)))

(defn graph
  "Converts an explain tree into a graph representation.

   Arguments:
     - `explain-tree` (returned from kex.core/evaluate)

   Returns:
    ```clojure
     {:root  node-id
      :nodes {node-id → node}
      :edges [{:from id :to id :label kw} ...]}
    ```
   The resulting graph is suitable for:
     - visualization (Graphviz, etc.)
     - audit logging
     - debugging
     - API responses

   Performs no authorization logic.
   It is a pure transformation."
  [explain-tree]
  (graph/explain->graph explain-tree))
