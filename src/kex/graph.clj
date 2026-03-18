(ns kex.graph)

(defn- next-node-id
  "Returns [node-id next-counter]. IDs are :n1, :n2, ..."
  [counter]
  [(keyword (str "n" counter))
   (inc counter)])

(defn- check-node
  [id explain-node]
  {:id       id
   :kind     :check
   :check-id (:check-id explain-node)
   :result   (:result explain-node)})

(defn- derived-fact-node
  [id explain-node]
  {:id    id
   :kind  :derived-fact
   :fact  (:fact explain-node)
   :rule  (:rule explain-node)
   :env   (:env explain-node)})

(defn- authority-fact-node
  [id explain-node]
  {:id   id
   :kind :authority-fact
   :fact (:fact explain-node)})

(defn- missing-fact
  [id explain-node]
  {:id   id
   :kind :missing-fact
   :fact (:fact explain-node)})

(defn- explain-node->graph-node
  "Converts an explain node to a graph node (without children)."
  [id explain-node]
  (cond
    (= (:type explain-node) :check)      (check-node id explain-node)
    (= (:origin explain-node) :derived)  (derived-fact-node id explain-node)
    (= (:origin explain-node) :authority)(authority-fact-node id explain-node)
    (= (:kind explain-node) :missing)    (missing-fact id explain-node)

    :else
    (throw (ex-info "Unknown explain node shape"
                    {:node explain-node}))))

(defn- build-graph
  "Recursively converts an explain tree into [node-id next-counter graph]."
  [explain-node counter]
  (let [[id next-counter] (next-node-id counter)
        node          (explain-node->graph-node id explain-node)]
    (cond
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

      :else
      [id
       next-counter
       {:nodes {id node}
        :edges []}])))

(defn explain->graph
  "Converts an explain tree to {:root id :nodes {id node} :edges [...]}."
  [explain]
  (let [[root-id _ graph] (build-graph explain 1)]
    (assoc graph :root root-id)))
