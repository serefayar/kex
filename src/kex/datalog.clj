(ns kex.datalog
  (:require
   [clojure.string :as str]))

(defn variable?
  "True if var is a logic variable (symbol starting with ?)."
  [var]
  (and (symbol? var)
       (str/starts-with? (name var) "?")))

(defn public-fact?
  "True if fact has :public/ namespace prefix.
   All other facts (including unnamespaced) are private by default."
  [fact]
  (let [pred (first fact)]
    (and (keyword? pred)
         (= "public" (namespace pred)))))

(defn bind
  "Binds var to value in env. Returns updated env, or nil on conflict."
  [env var value]
  (if-let [existing (get env var)]
    (when (= existing value) env)
    (assoc env var value)))

(defn unify
  "Unifies a pattern with a fact. Returns {:env bindings :proof [...]} or nil."
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
                   (variable? pt)
                   (when-let [new-env (bind env pt ft)]
                     {:env new-env :proof proof})

                   (= pt ft) state
                   :else nil))))
           state
           (map vector pattern fact))))

(defn eval-body
  "Evaluates a body (sequence of patterns, logical AND) against facts.
   Returns sequence of {:env bindings :proof [...]}."
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
  "Replaces variables in head with bound values from env. Nil if any unbound."
  [head env]
  (let [result (mapv (fn [arg]
                       (cond
                         (variable? arg) (get env arg ::unbound)
                         :else arg))
                     head)]
    (when-not (some #{::unbound} result)
      result)))

(defn redact-if-non-public
  "Replaces non-public facts in proof entries with :redacted/non-public-fact.
   Only :public/ namespaced facts pass through. Preserves proof tree structure."
  [proof-entry]
  (if (and (vector? (:fact proof-entry))
           (public-fact? (:fact proof-entry)))
    proof-entry
    {:type      :fact
     :fact      :redacted/non-public-fact
     :origin    (:origin proof-entry :authority)
     :redacted? true}))

(defn fire-rule
  "Applies a rule to facts. Returns derived facts with proof metadata.
   Non-public facts in proofs are redacted."
  [{:keys [id head body]} facts]
  (keep (fn [{:keys [env proof]}]
          (when-let [fact (instantiate head env)]
            {:fact   fact
             :origin :derived
             :rule   id
             :env    env
             :proof  (mapv redact-if-non-public proof)}))
        (eval-body body facts)))

(defn apply-rules
  "Applies all rules to facts. Returns derived facts with proofs."
  [rules facts]
  (mapcat #(fire-rule % facts) rules))

(defn eval-check
  "Evaluates a single check against the fact store.
   Returns {:result :pass/:fail :explain ...}."
  [{:keys [id query]} fact-store]
  (let [results (eval-body query (keys fact-store))]
    (if (seq results)
      (let [binding (first results)
            fact    (instantiate (first query) (:env binding))
            explain (get fact-store fact)]
        {:result  :pass
         :explain {:type     :check
                   :check-id id
                   :result   :pass
                   :because  explain}})
      {:result  :fail
       :explain {:type     :check
                 :check-id id
                 :result   :fail
                 :missing  query}})))

(defn eval-checks
  "Evaluates all checks against the fact store."
  [checks store]
  (map #(eval-check % store) checks))


(defn- authority-block?
  [block]
  (zero? (:block-index block 0)))

(defn- public-fact-subset
  "Filters fact store to public facts only, redacting non-public proofs."
  [fact-store]
  (into {}
        (filter (fn [[fact _]] (public-fact? fact)))
        (map (fn [[fact entry]]
               [fact (if (:proof entry)
                       (update entry :proof #(mapv redact-if-non-public %))
                       entry)])
             fact-store)))

(defn eval-token
  "Evaluates a token with origin-aware scoping.
   Authority block (index 0) public facts form the global scope.
   Delegated blocks see global scope but cannot extend it.
   All checks across all blocks must pass.
   Returns {:valid? bool, :explain tree}."
  [{:keys [blocks]} & {:keys [explain?]}]
  (loop [remaining    blocks
         global-facts {}
         last-explain nil]
    (if (empty? remaining)
      (cond-> {:valid? true}
        explain? (assoc :explain (or last-explain :no-checks)))
      (let [block       (first remaining)
            authority?  (authority-block? block)
            origin-tag  (if authority? :authority :delegated)

            block-facts (reduce (fn [st ft]
                                  (assoc st ft {:fact ft :origin origin-tag}))
                                {}
                                (:facts block))
            local-facts (merge global-facts block-facts)
            derived     (apply-rules (:rules block) (keys local-facts))
            merged      (reduce #(assoc %1 (:fact %2) %2) local-facts derived)

            results     (eval-checks (:checks block) merged)
            failed      (first (filter #(= :fail (:result %)) results))
            last-pass   (last (filter #(= :pass (:result %)) results))]
        (if failed
          (cond-> {:valid? false}
            explain? (assoc :explain (or (:explain failed) :no-explain)))
          (recur (rest remaining)
                 (if authority?
                   (public-fact-subset merged)
                   global-facts)
                 (or (:explain last-pass) last-explain)))))))