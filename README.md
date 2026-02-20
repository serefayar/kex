# kex

kex is an experimental, data-oriented authorization engine inspired by the Biscuit token model. It implements a minimal capability-based token system with:

* Append-only, cryptographically signed blocks
* Minimal Datalog-style rule evaluation
* Offline verification
* Explainable authorization decisions
* Proof graph visualization

## Status

**Proof of Concept (PoC).**

kex is not:

* A full Biscuit spec implementation
* Production hardened
* Performance optimized
* Recursive Datalog complete
* Revocation-ready

Block isolation is implemented via namespace-based fact visibility.
Facts marked `:public/` propagate across blocks. Facts marked `:private/`
are scoped to their block. This differs from Biscuit's cryptographic
block scoping but provides a practical approximation for exploration purposes.

It is intentionally a small, inspectable system designed for exploration, learning, and conceptual validation, not spec compliance or production guarantees.

It is an experimental implementation of ideas explored in a series of writings on:

* [De-mystifying Agentic AI: Building a Minimal Agent Engine from Scratch with Clojure](https://serefayar.substack.com/p/minimal-agent-engine-from-scratch-with-clojure?r=359n9q)
* [OCapN and Structural Authority in Agentic AI](https://serefayar.substack.com/p/ocapn-and-structural-authority-in-agentic-ai?r=359n9q)
* [Interpreting OCapN Principles in Cloud-Native Agentic AI Architectures](https://serefayar.substack.com/p/interpreting-ocapn-principles-in-cloud-native-agentic-ai)
* [Reconstructing Biscuit in Clojure](https://serefayar.substack.com/p/reconstructing-biscuit-in-clojure)

Biscuit Token Specification: https://www.biscuitsec.org/

If this experiment resonates with you, reading the original Biscuit specification is worth the time.

## Why Clojure

Clojure is well suited for this kind of exploration.

**Data-Oriented Design:** The entire system is expressed as immutable maps and vectors. Tokens, rules, proofs, and graphs are all plain data.

**Datalog Culture:** The Clojure ecosystem is familiar with Datalog-style reasoning (Datomic, DataScript, etc.). The mental model fits naturally.

**REPL-Driven Experimentation:** Authorization logic becomes inspectable. You can evaluate a token, examine its proof tree, transform it into a graph, and reason about it interactively.

**Capability Thinking:** Clojure encourages modeling behavior through data and transformation. That aligns with object-capability principles.

## Examples

### Issue a token

The issuer creates the first block. Facts marked with `:public/` propagate
to subsequent blocks. Facts marked with `:private/` are scoped to this block only.
```clojure
(require '[kex.core :as kex])

(def keypair (kex/new-keypair))

(def token
  (kex/issue
    {:facts  [[:public/user "alice"]
              [:public/role "alice" :agent]
              [:private/issuer-id "orchestrator-001"]]
     :rules  '[{:id   :agent-can-read-agents
                :head [:public/right ?user :read ?agt]
                :body [[:public/role ?user :agent]
                       [:public/internal-agent ?agt]]}]
     :checks []}
    {:private-key (:priv keypair)}))
```

### Attenuate the token

A second service appends a new block. It can see public facts from the
previous block, and can add its own facts and rules. Private facts from
the first block are not visible here.
```clojure
(def delegated-token
  (kex/attenuate
    token
    {:facts  [[:public/internal-agent "bob"]
              [:public/can "alice" :read "web-search"]
              [:private/delegation-note "limited to web tools only"]]
     :rules  '[{:id   :can-implies-right
                :head [:public/right ?user :read ?res]
                :body [[:public/can ?user :read ?res]]}]
     :checks []}
    {:private-key (:priv keypair)}))
```

### Add a check

A third party appends one more block with a check only. The check query
uses public facts, which is the only fact space visible across blocks.
```clojure
(def auth-token
  (kex/attenuate
    delegated-token
    {:facts  []
     :rules  []
     :checks [{:id    :can-read-web-search
               :query '[[:public/right "alice" :read "web-search"]]}]}
    {:private-key (:priv keypair)}))
```

### Verify cryptographically
```clojure
(kex/verify auth-token {:public-key (:pub keypair)})
;; => true
```

### Evaluate authorization
```clojure
(def decision
  (kex/evaluate auth-token :explain? true))

(:valid? decision)
;; => true
```

### Inspect the proof
```clojure
(:explain decision)
;; =>
;; {:type     :check
;;  :check-id :can-read-web-search
;;  :result   :pass
;;  :because  {:fact   [:public/right "alice" :read "web-search"]
;;             :origin :derived
;;             :rule   :can-implies-right
;;             :env {?user "alice", ?res "web-search"},
;;             :proof  [{:type   :fact
;;                       :fact   [:public/can "alice" :read "web-search"]
;;                       :origin :authority}]}}
```

Private facts never appear in the proof tree. They do not propagate
beyond their block and never enter the evaluation scope of subsequent blocks.

### Convert explanation to a graph
```clojure
(def graph (kex/graph (:explain decision)))
;; => {:root  :n1
;;     :nodes {:n1 {:id :n1 :kind :check       :check-id :can-read-web-search ...}
;;             :n2 {:id :n2 :kind :derived-fact :fact [:public/right "alice" :read "web-search"] ...}
;;             :n3 {:id :n3 :kind :authority-fact :fact [:public/can "alice" :read "web-search"]}}
;;     :edges [{:from :n1 :to :n2 :label :because}
;;             {:from :n2 :to :n3 :label :because}]}
```

### Private facts in derivation
A rule can use private facts to derive public ones. The derived fact propagates forward, but the private fact that contributed to it is redacted in the proof tree. This ensures private block data does not leak to downstream agents, audit systems, or logs.

```clojure
(def token-with-private
  (kex/issue
    {:facts  [[:public/user "alice"]
              [:private/clearance-level "alice" :top-secret]]
     :rules  '[{:id   :clearance-implies-right
                :head [:public/right ?user :read :classified-docs]
                :body [[:private/clearance-level ?user :top-secret]]}]
     :checks [{:id    :can-read-classified
               :query '[[:public/right "alice" :read :classified-docs]]}]}
    {:private-key (:priv keypair)}))

(kex/verify token-with-private {:public-key (:pub keypair)})
;; => true

(def decision (kex/evaluate token-with-private :explain? true))

(:valid? decision)
;; => true

(:explain decision)
;; =>
;; {:type     :check
;;  :check-id :can-read-classified
;;  :result   :pass
;;  :because  {:fact    [:public/right "alice" :read :classified-docs]
;;             :origin  :derived
;;             :rule    :clearance-implies-right
;;             :env     {?user "alice"}
;;             :proof   [{:type      :fact
;;                        :fact      :redacted/private-fact
;;                        :origin    :authority
;;                        :redacted? true}]}}
```
The proof tree shows that a private fact contributed to the derivation, but its content is not visible. The derivation chain remains traceable without exposing block-internal data.

## License

Copyright © 2026 Seref R. Ayar

Distributed under the Eclipse Public License version 1.0.