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

It also does not fully enforce attenuation. A new block can add broader facts that expand authority if no check prevents it. In Biscuit, block isolation prevents this; a new block cannot see or override facts from another block's private scope. In kex, that isolation is not implemented.

It is intentionally a small, inspectable system designed for exploration, learning, and conceptual validation, not spec compliance or production guarantees.

It is an experimental implementation of ideas explored in a series of writings on:

* [De-mystifying Agentic AI: Building a Minimal Agent Engine from Scratch with Clojure](https://serefayar.substack.com/p/minimal-agent-engine-from-scratch-with-clojure?r=359n9q)
* [OCapN and Structural Authority in Agentic AI](https://serefayar.substack.com/p/ocapn-and-structural-authority-in-agentic-ai?r=359n9q)
* [Interpreting OCapN Principles in Cloud-Native Agentic AI Architectures](https://serefayar.substack.com/p/interpreting-ocapn-principles-in-cloud-native-agentic-ai)
* [Reconstructing Biscuit Token in Clojure](https://serefayar.substack.com/p/reconstructing-biscuit-token-in-clojure)

Biscuit Token Specification: https://www.biscuitsec.org/

If this experiment resonates with you, reading the original Biscuit specification is strongly recommended.

## Why Clojure

Clojure is well suited for this kind of exploration.

**Data-Oriented Design:** The entire system is expressed as immutable maps and vectors. Tokens, rules, proofs, and graphs are all plain data.

**Datalog Culture:** The Clojure ecosystem is familiar with Datalog-style reasoning (Datomic, DataScript, etc.). The mental model fits naturally.

**REPL-Driven Experimentation:** Authorization logic becomes inspectable. You can evaluate a token, examine its proof tree, transform it into a graph, and reason about it interactively.

**Capability Thinking:** Clojure encourages modeling behavior through data and transformation. That aligns with object-capability principles.

## Examples

### Issue a token

The issuer creates the first block. It defines who Alice is and what agents are allowed to do.

```clojure
(require '[kex.core :as kex])

(def keypair (kex/new-keypair))

(def token
  (kex/issue
    {:facts  [[:user "alice"] [:role "alice" :agent]]
     :rules  '[{:id   :agent-can-read-agents
                :head [:right ?user :read ?agt]
                :body [[:role ?user :agent]
                       [:internal-agent ?agt]]}]
     :checks []}
    {:private-key (:priv keypair)}))
```

### Attenuate the token

A second service appends a new block. It adds facts about what Alice can access, and a rule that derives read rights from those facts. Because kex collects all facts and rules from all blocks into a single pool before evaluation, this block's facts and rules will be combined with the first block's during derivation.

```clojure
(def delegated-token
  (kex/attenuate
    token
    {:facts  [[:internal-agent "bob"]
              [:can "alice" :read "endpoint-1"]]
     :rules  '[{:id   :can-implies-right
                :head [:right ?user :read ?res]
                :body [[:can ?user :read ?res]]}]
     :checks []}
    {:private-key (:priv keypair)}))
```

### Add a check

A third party appends one more block. It adds nothing but a check. This token is only valid if Alice can read endpoint-1.

```clojure
(def auth-token
  (kex/attenuate
    delegated-token
    {:facts  []
     :rules  []
     :checks [{:id    :can-read-endpoint-1
               :query '[[:right "alice" :read "endpoint-1"]]}]}
    {:private-key (:priv keypair)}))
```

The authorization succeeds only if the query evaluates to a non-empty result set.

Negative constraints (e.g., "require empty") are intentionally not supported in this PoC. The system follows a strictly positive, monotonic logic model aligned with capability-based design. This keeps the engine simple, predictable, and easier to reason about.

### Verify cryptographically

```clojure
;; If this returns false, evaluation must stop.
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
;; Returns a structured explanation tree showing why the check passed or failed.
(:explain decision)
```

### Convert explanation to a graph

```clojure
;; This can be visualized with Graphviz or similar tools.
(def graph
  (kex/graph (:explain decision)))

;; => {:root :n1 :nodes {...} :edges [...]}
```

## License

Copyright © 2026 Seref R. Ayar

Distributed under the Eclipse Public License version 1.0.