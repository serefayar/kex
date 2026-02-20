(ns kex.datalog
  (:require
   [clojure.string :as str]))

(defn variable?
  "Returns true if the given value represents a logic variable.

   A variable is defined as a symbol whose name starts with '?'."
  [var]
  (and (symbol? var)
       (str/starts-with? (name var) "?")))

(defn public-fact?
  "Returns true if the fact should propagate to subsequent blocks.

   Only facts with the :public/ namespace prefix propagate forward,
   e.g. [:public/role \"alice\" :agent].

   All other facts, including namespace-less facts, are treated as
   private and scoped to their block. Privacy is the default.
   Visibility must be explicitly declared using the :public/ prefix.

   This follows a fail-safe principle: a fact that is not explicitly
   marked public will never leak to downstream blocks or agents."
  [fact]
  (let [pred (first fact)]
    (and (keyword? pred)
         (= "public" (namespace pred)))))

(defn bind
  "Attempts to bind a logic variable to a concrete value.

   If the variable is unbound, associates it with the given value.
   If the variable is already bound, ensures the existing binding
   matches the value.

   Returns the updated environment map if successful,
   or nil if the binding would cause a conflict."
  [env var value]
  (if-let [existing (get env var)]
    (when (= existing value) env)
    (assoc env var value)))

(defn unify
  "Attempts to unify a pattern with a concrete fact, producing
   both variable bindings and proof metadata.

   On success, returns a map with:
    |||
    |:-|:-|
    | `:env`    | variable bindings
    | `:proof`  | evidence showing which fact enabled the match

   On failure, returns `nil`."
  ([pattern fact]
   (when-let [result (unify {:env {} :proof []} pattern fact)]
     (update result :proof conj
             {:type   :fact
              :fact   fact
              :origin :authority})))

  ([state pattern fact]
   (reduce (fn [state [pt ft]]
             (when state
               (let [{:keys [env proof]} state]
                 (cond
                   ;; case 1: variable
                   (variable? pt)
                   (when-let [new-env (bind env pt ft)]
                     {:env   new-env
                      :proof proof})

                   ;; case 2: literal match
                   (= pt ft)
                   state

                   :else nil))))
           state
           (map vector pattern fact))))

(defn eval-body
  "Evaluates a rule or query body against a set of facts.
  
   The body is a sequence of patterns that must all be satisfied
   simultaneously (logical AND).
  
   Returns a sequence of states, where each state contains:
   |||
   |:-|:-|
   | `:env`   | the combined variable bindings
   | `:proof` | all facts that contributed to satisfying the body."
  [body facts]
  (reduce
   (fn [states pattern]
     (for [state states
           fact  facts
           :let  [result (unify pattern fact)]
           :when result
           :let  [merged-env   (merge (:env state) (:env result))
                  merged-proof (into (:proof state)
                                     (:proof result))]]
       {:env   merged-env
        :proof merged-proof}))
   [{:env {} :proof []}]
   body))


(defn instantiate
  "Instantiates a rule head using a variable environment.

   Replaces all variables in the head with their bound values
   from the environment.

   If any variable in the head is unbound, returns nil."
  [head env]
  (let [result (mapv (fn [arg]
                       (cond
                         (variable? arg) (get env arg ::unbound)
                         :else arg))
                     head)]
    (when-not (some #{::unbound} result)
      result)))

(defn redact-if-private
  "Redacts a proof entry if it references a private fact.
  
   A fact is considered private if its predicate keyword belongs to
   the :private/ namespace, e.g. :private/session-key.
  
   Private facts must not appear in proof trees. Proof trees can flow
   to downstream agents, audit systems, or logs. Exposing private facts
   in this context would break block-level information boundaries.
  
   Redacted entries preserve the structure of the proof tree so that
   derivation chains remain traceable. The content is replaced with
   a sentinel value. The :redacted? flag signals to consumers that
   a private fact contributed to this derivation.
  
   Non-private entries are returned unchanged."
  [proof-entry]
  (let [fact (:fact proof-entry)
        private? (and (vector? fact)
                      (keyword? (first fact))
                      (= "private" (namespace (first fact))))]
    (if private?
      {:type      :fact
       :fact      :redacted/private-fact
       :origin    :authority
       :redacted? true}
      proof-entry)))

(defn fire-rule
  "Applies a rule to the current fact set and produces derived facts
   along with full explanation metadata.

   For each successful match of the rule body, generates a map with:
   |||
   |:-|:-|
   | `:fact`   | the derived fact
   | `:origin` | :derived
   | `:rule`   | the rule identifier
   | `:env`    | variable bindings used
   | `:proof`  | evidence from the rule body, with private facts redacted

   Private facts that contributed to a derivation appear as redacted
   entries in the proof. See: `redact-if-private`."
  [{:keys [id head body]} facts]
  (map (fn [{:keys [env proof]}]
         (when-let [fact (instantiate head env)]
           {:fact   fact
            :origin :derived
            :rule   id
            :env    env
            :proof  (mapv redact-if-private proof)}))
       (eval-body body facts)))

(defn apply-rules
  "Applies rules to the given fact list and produces derived facts
   along with full explanation metadata.
     
   facts: sequence of fact vectors (not a map). see: `fire-rule`"
  [rules facts]
  (mapcat #(fire-rule % facts) rules))

(defn eval-check
  "Evaluates a single authorization check against the current fact store.

   If the check passes, returns a `:pass` result with an explanation
   rooted in the fact that satisfied the check.

   If the check fails, returns a `:fail` result with information about
   the missing required fact."
  [{:keys [id query]} fact-store]
  (let [results (eval-body query (keys fact-store))]
    (if (seq results)
      ;; pass
      (let [binding (first results)
            fact    (instantiate (first query) (:env binding))
            explain (get fact-store fact)]
        {:result  :pass
         :explain {:type     :check
                   :check-id id
                   :result   :pass
                   :because  explain}})

      ;; fail
      {:result  :fail
       :explain {:type     :check
                 :check-id id
                 :result   :fail
                 :missing  query}})))

(defn eval-checks
  "Evaluates all checks on the final fact set.

   Checks never produce new facts; they only validate conditions."
  [checks store]
  (map (fn [c] (eval-check c store)) checks))


(defn eval-token
  "Evaluates an authorization token consisting of one or more blocks.

   Each block is evaluated in order, in its own scope. A block can only
   see facts that are explicitly marked as public from previous blocks,
   plus its own facts (public and private).

   Public facts use the :public/ namespace prefix, e.g. :public/role.
   Private facts use the :private/ namespace prefix, e.g. :private/session-key.
   Private facts are scoped to their block and do not propagate forward.

   For each block, the evaluation:
    1. Merges visible public facts from previous blocks with the block's own facts
    2. Applies the block's rules to derive new facts within the local scope
    3. Evaluates the block's checks against the local fact set
    4. Propagates only public facts (authority and derived) to the next block

   A token is valid only if all checks in all blocks pass.
   The first failing check short-circuits evaluation.

   Returns a map with:
   |||
   |:-|:-|
   | `:valid?`  | boolean authorization decision
   | `:explain` | proof tree of the first failing or last passing check (when :explain? is true)"
  [{:keys [blocks]} & {:keys [explain?]}]
  (loop [remaining     blocks
         visible-facts {}
         last-explain  nil]
    (if (empty? remaining)
      (cond-> {:valid? true}
        explain? (assoc :explain (or last-explain :no-checks)))
      (let [block       (first remaining)
            block-facts (reduce (fn [st ft]
                                  (assoc st ft {:origin :authority}))
                                {}
                                (:facts block))
            local-facts (merge visible-facts block-facts)
            derived     (apply-rules (:rules block) (keys local-facts))
            merged      (reduce #(assoc %1 (:fact %2) %2) local-facts derived)
            results     (eval-checks (:checks block) merged)
            failed      (first (filter #(= :fail (:result %)) results))
            last-pass   (last (filter #(= :pass (:result %)) results))]
        (if failed
          (cond-> {:valid? false}
            ;; :explain always present when failed, see eval-check.
            ;; :no-explain is a defensive fallback.
            explain? (assoc :explain (or (:explain failed) :no-explain)))
          (recur (rest remaining)
                 (into {}
                       (filter (fn [[fact _]] (public-fact? fact)))
                       (map (fn [[fact entry]]
                              [fact (update entry :proof #(mapv redact-if-private (or % [])))])
                            merged))
                 (or (:explain last-pass) last-explain)))))))