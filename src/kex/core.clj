(ns kex.core
  (:require [kex.block :as block]
            [kex.datalog :as datalog]
            [kex.graph :as graph]
            [kex.crypto :as crypto]))

(defn new-keypair
  "Generates an Ed25519 keypair. Returns {:priv ... :pub ...}."
  []
  (let [kp (crypto/generate-keypair "Ed25519")]
    {:priv (.getPrivate kp)
     :pub (.getPublic kp)}))

(defn issue
  "Creates a new authority token (single signed block).
   Payload: {:facts [...] :rules [...] :checks [...]}.
   Opts: {:private-key pk}."
  [{:keys [facts rules checks]} {:keys [private-key]}]
  [(block/authority-block
    (or facts [])
    (or rules [])
    (or checks [])
    private-key)])

(defn attenuate
  "Appends a delegated block to a token. Delegated blocks can restrict
   capabilities via checks but cannot expand authority.
   Same payload/opts shape as `issue`."
  [token {:keys [facts rules checks]} {:keys [private-key]}]
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
  "Cryptographic chain verification only. Does not evaluate authorization.
   Call `evaluate` only after this returns true.
   Opts: {:public-key pk}."
  [token {:keys [public-key]}]
  (block/verify-chain token public-key))

(defn evaluate
  "Evaluates a verified token against its checks.
   Returns {:valid? bool, :explain tree}.
   Assumes token was already `verify`'d."
  [token & {:keys [explain?]}]
  (let [core-token
        {:blocks
         (vec (map-indexed
               (fn [idx block]
                 (-> (select-keys block [:facts :rules :checks])
                     (assoc :block-index idx)))
               token))}]
    (datalog/eval-token core-token :explain? explain?)))

(defn graph
  "Transforms an explain tree into {:root id, :nodes {...}, :edges [...]}.
   Pure transformation, no authorization logic."
  [explain-tree]
  (graph/explain->graph explain-tree))
