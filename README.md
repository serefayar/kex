# kex

kex is an experimental, data-oriented authorization engine inspired by Biscuit token model. It implements a minimal capability-based token system with:

* Append-only, cryptographically signed blocks

* Datalog-style rule evaluation

* offline verification

* explainable authorization decisions

* proof graph visualization


## Status

**Proof of Concept (PoC).**
 
kex is not:

* A full Biscuit spec implementation
* Production hardened
* Performance optimized
* Recursive Datalog complete
* Revocation-ready

It is intentionally small, inspectable system designed to explore ideas, learning, and conceptual validation, not spec compliance or production guarantees. 

It is an experimental implementation of ideas explored in a series of writings on:

* [De-mystifying Agentic AI: Building a Minimal Agent Engine from Scratch with Clojure](https://serefayar.substack.com/p/minimal-agent-engine-from-scratch-with-clojure?r=359n9q)

* [OCapN and Structural Authority in Agentic AI](https://serefayar.substack.com/p/ocapn-and-structural-authority-in-agentic-ai?r=359n9q)

* [Interpreting OCapN Principles in Cloud-Native Agentic AI Architectures](https://serefayar.substack.com/p/interpreting-ocapn-principles-in-cloud-native-agentic-ai)

* [Reconstructing Biscuit Token in Clojure](https://serefayar.substack.com/p/reconstructing-biscuit-token-in-clojure)


Biscuit Token Specification

https://www.biscuitsec.org/

If this experiment resonates with you, I strongly recommend reading the original Biscuit specification and related work.


## Why

This project exists because Clojure is uniquely suited for this kind of exploration.

1. Data-Oriented Design:
   The entire system is expressed as immutable maps and vectors. Tokens, rules, proofs, and graphs are all plain data.

2. Datalog Culture:
   The Clojure ecosystem is deeply familiar with Datalog-style reasoning (Datomic, DataScript, etc.). The mental model fits naturally.

3. REPL-Driven Experimentation:
   Authorization logic becomes inspectable. You can evaluate a token, examine its proof tree, transform it into a graph, and reason about it interactively.

4. Capability Thinking:
   Clojure encourages modeling behavior through data and transformation. That aligns well with object-capability principles.


## Examples


### Issue a token

``` clojure
(require '[kex.core :as kex])

(def keypair (kex/new-keypair))

;; This creates the initial authority block.
(def token
  (kex/issue
    {:facts [[:user "alice"]
            [:role "alice" :agent]
            [:internal-agent "bob"]]
    :rules '[{:id :right-from-role
              :head [:right ?user :read ?agt]
              :body [[:role ?user :agent]
                     [:internal-agent ?agt]]}]
    :checks []}

    {:private-key (:priv keypair)}))
```

### Attenuate the token

``` clojure

;; A delegated block is appended. The chain is immutable.
(def restricted-token
  (kex/attenuate
    token
    {:facts [[:user "bob"]
            [:role "bob" :agent]
            [:internal-agent "alice"]
            [:can "alice" :read "endpoint-1"]]
    :rules []
    :checks '[]}

    {:private-key (:priv keypair)}))

```

### Auth Decision 

```clojure
(def auth-token 
 (kex/attenuate
    restricted-token
    {:checks [{:id :can-read-endpoint-1
               :query '[[:right ?user :read "bob"]
                        [:can ?user :read "endpoint-1"]]}]}
    
  {:private-key (:priv keypair)}))
```

The authorization succeeds only if the query evaluates to a non-empty result set.

Negative constraints (e.g., “require empty”) are intentionally not supported in this PoC.
The system follows a strictly positive, monotonic logic model aligned with capability-based design.

This keeps the engine simple, predictable, and easier to reason about.

### Verify cryptographically

``` clojure

;; if this returns false, evaluation must stop.
(kex/verify auth-token {:public-key (:pub keypair)})
;; => true
```

### Evaluate authorization

``` clojure

(def decision
  (kex/evaluate auth-token :explain? true))

(:valid? decision)
;; => false

```

### Inspect the proof

``` clojure

;; Returns a structured explanation tree showing why the check passed (or failed).
(:explain decision)

```

### Convert explanation to a graph

``` clojure

;; This can be visualized with Graphviz or similar tools.
(def graph
  (kex/graph (:explain decision)))

;; => {:root  :n1 :nodes {...} :edges [...]}

```

## License

Copyright © 2026 Seref R. Ayar

Distributed under the Eclipse Public License version 1.0.
