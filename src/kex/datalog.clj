(ns kex.datalog
  (:require 
   [clojure.string :as str]))

(defn variable?
  "Returns true if the given value represents a logic variable.

   A variable is defined as a symbol whose name starts with '?'."
  [var]
  (and (symbol? var)
       (str/starts-with? (name var) "?")))

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
   | `:proof`  | evidence from the rule body"
  [{:keys [id head body]} facts]
  (map (fn [{:keys [env proof]}]
         (when-let [fact (instantiate head env)]
           {:fact   fact
            :origin :derived
            :rule id
            :env    env
            :proof  proof}))
       (eval-body body facts)))

(defn apply-rules
  "Applies rules to the final fact set and produces derived facts
   along with full explanation metadata. see: `fire-rule`"
  [rules facts]
  (mapcat #(fire-rule % (keys facts)) rules))

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

   Each block may contribute facts, rules, and checks. The evaluation:
    1. Collects all facts
    2. Collects all rules
    2. Applies rules to derive new facts
    3. Evaluates all checks on the final facts [eval-checks]

   Returns a map with:
   |||
   |:-|:-|
   | `:valid?` | boolean authorization decision
   | `:explain` | optional proof tree when :explain? is enabled

   This is the main entry point for authorization decisions."
  [{:keys [blocks] :as _token} & {:keys [explain?]}]
  (let [all-facts (reduce (fn [st ft]
                            (assoc st ft {:origin :authority}))
                          {}
                          (mapcat :facts blocks))
        all-rules (into [] (mapcat :rules) blocks)
        derived (apply-rules all-rules all-facts)
        merged (reduce #(assoc %1 (:fact %2) %2) all-facts derived)
        results (eval-checks (mapcat :checks blocks) merged)
        failed (first (filter #(= :fail (:result %)) results))] 
    (cond
      failed
      (as-> {:valid? false} m
        (when explain? (assoc m :explain (:explain failed))))

      :else
      (as-> {:valid? true} m
        (when explain? (assoc m :explain (:explain (last results))))))))

