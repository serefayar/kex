(ns kex.graph)

(defn next-node-id
  "Returns a tuple of:
   ```clojure
     [node-id next-counter]
   ```
   Node ids are deterministic keywords (:n1, :n2, ...)."
  [counter]
  [(keyword (str "n" counter))
   (inc counter)])



(defn check-node
  [id explain-node]
  {:id       id
   :kind     :check
   :check-id (:check-id explain-node)
   :result   (:result explain-node)})

(defn derived-fact-node
  [id explain-node]
  {:id    id
   :kind  :derived-fact
   :fact  (:fact explain-node)
   :rule  (:rule explain-node)
   :env   (:env explain-node)})

(defn authority-fact-node
  [id explain-node]
  {:id   id
   :kind :authority-fact
   :fact (:fact explain-node)})

(defn missing-fact
  [id explain-node]
  {:id   id
   :kind :missing-fact
   :fact (:fact explain-node)})

(defn explain-node->graph-node
  "Converts a single explain node into a graph node map.

   This function does not handle children; it only normalizes
   the current node into a graph-friendly representation."
  [id explain-node]
  (cond
    ;; check node
    (= (:type explain-node) :check) (check-node id explain-node)
    ;; derived fact
    (= (:origin explain-node) :derived) (derived-fact-node id explain-node)
    ;; authority fact
    (= (:origin explain-node) :authority) (authority-fact-node id explain-node)
    ;; missing fact (synthetic)
    (= (:kind explain-node) :missing) (missing-fact id explain-node)

    :else
    (throw (ex-info "Unknown explain node shape"
                    {:node explain-node}))))

(defn build-graph
  "Recursively converts an explain tree into a graph fragment.

   Returns a tuple:
     ```clojure
     [node-id next-counter {:nodes {...} :edges [...]}]
   ```"
  [explain-node counter]
  (let [[id next-counter] (next-node-id counter)
        node          (explain-node->graph-node id explain-node)]
    (cond
      ;; CHECK – PASS
      (and (= (:type explain-node) :check)
           (= (:result explain-node) :pass))
      (let [[child-id child-counter child-graph] (build-graph
                                                  (:because explain-node)
                                                  next-counter)]
        [id
         child-counter
         {:nodes (merge {id node} (:nodes child-graph))
          :edges (conj (:edges child-graph)
                       {:from id
                        :to   child-id
                        :label :because})}])

      ;; CHECK – FAIL
      (and (= (:type explain-node) :check)
           (= (:result explain-node) :fail))
      (let [missing {:kind :missing
                     :fact (:missing explain-node)}
            [child-id child-counter child-graph]
            (build-graph missing next-counter)]
        [id
         child-counter
         {:nodes (merge {id node} (:nodes child-graph))
          :edges (conj (:edges child-graph)
                       {:from id
                        :to   child-id
                        :label :missing})}])

      ;; DERIVED FACT → proof list
      (= (:origin explain-node) :derived)
      (reduce
       (fn [[_ ctr g] proof-node]
         (let [[pid next-ctr pg] (build-graph proof-node ctr)]
           [id
            next-ctr
            {:nodes (merge (:nodes g) (:nodes pg))
             :edges (conj (:edges g)
                          {:from id
                           :to   pid
                           :label :because})}]))
       [id next-counter {:nodes {id node} :edges []}]
       (:proof explain-node))

      ;; AUTHORITY FACT (leaf)
      :else
      [id
       next-counter
       {:nodes {id node}
        :edges []}])))

(defn explain->graph
  "Converts an explain tree produced by the core into a graph structure.

   The resulting graph contains:
   |||
   |:-|:- 
   |`:root`  | root node id
   |`:nodes` | map of node-id → node
   |`:edges` | list of directed edges with semantic labels."
  [explain]
  (let [[root-id _ graph] (build-graph explain 1)]
    (assoc graph :root root-id)))
