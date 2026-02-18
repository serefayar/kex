(ns kex.graph-test
  (:require [kex.graph :as sut]
            [clojure.test :as t]))

(t/deftest explain->graph-pass-simple
  (let [explain
        {:type :check
         :check-id :c1
         :result :pass
         :because
         {:origin :derived
          :fact   [:can "alice" :read "file-1"]
          :rule   :can-from-right
          :env    {'?u "alice"}
          :proof  [{:origin :authority
                    :fact   [:right "alice" :read "file-1"]}]}}

        graph (sut/explain->graph explain)]

    ;; root
    (t/is (= :n1 (:root graph)))

    ;; nodes
    (t/is (= #{:n1 :n2 :n3}
             (set (keys (:nodes graph)))))

    ;; check node
    (t/is (= {:id :n1
              :kind :check
              :check-id :c1
              :result :pass}
             (get-in graph [:nodes :n1])))

    ;; derived fact node
    (t/is (= :derived-fact
             (get-in graph [:nodes :n2 :kind])))

    ;; authority fact node
    (t/is (= :authority-fact
             (get-in graph [:nodes :n3 :kind])))

    ;; edges
    (t/is (= #{{:from :n1 :to :n2 :label :because}
               {:from :n2 :to :n3 :label :because}}
             (set (:edges graph))))))


(t/deftest explain->graph-fail-missing
  (let [explain
        {:type :check
         :check-id :c2
         :result :fail
         :missing [:can "alice" :write "file-1"]}

        graph (sut/explain->graph explain)]

    ;; root
    (t/is (= :n1 (:root graph)))

    ;; nodes
    (t/is (= #{:n1 :n2}
             (set (keys (:nodes graph)))))

    ;; check node
    (t/is (= {:id :n1
              :kind :check
              :check-id :c2
              :result :fail}
             (get-in graph [:nodes :n1])))

    ;; missing node
    (t/is (= {:id :n2
              :kind :missing-fact
              :fact [:can "alice" :write "file-1"]}
             (get-in graph [:nodes :n2])))

    ;; edge
    (t/is (= [{:from :n1 :to :n2 :label :missing}]
             (:edges graph)))))


(t/deftest explain->graph-multiple-proofs
  (let [explain
        {:type :check
         :check-id :c3
         :result :pass
         :because
         {:origin :derived
          :fact   [:can "alice" :read "file-1"]
          :rule   :can-from-right
          :env    {}
          :proof  [{:origin :authority
                    :fact   [:right "alice" :read "file-1"]}
                   {:origin :authority
                    :fact   [:resource "file-1"]}]}}

        graph (sut/explain->graph explain)]

    ;; node count: check + derived + 2 authority
    (t/is (= 4 (count (:nodes graph))))

    ;; exactly two outgoing edges from derived node
    (let [derived-id
          (some (fn [[id n]]
                  (when (= :derived-fact (:kind n)) id))
                (:nodes graph))

          outgoing
          (filter #(= (:from %) derived-id)
                  (:edges graph))]

      (t/is (= 2 (count outgoing)))
      (t/is (every? #(= :because (:label %)) outgoing)))))


(t/deftest explain->graph-deterministic-ids
  (let [explain
        {:type :check
         :check-id :c1
         :result :pass
         :because
         {:origin :authority
          :fact   [:user "alice"]}}

        g1 (sut/explain->graph explain)
        g2 (sut/explain->graph explain)]

    (t/is (= g1 g2))))


(t/deftest explain->graph-authority-only
  (let [explain
        {:type :check
         :check-id :c4
         :result :pass
         :because {:origin :authority
                   :fact [:admin "alice"]}}

        graph (sut/explain->graph explain)]

    (t/is (= 2 (count (:nodes graph))))
    (t/is (= 1 (count (:edges graph))))

    (t/is (= :authority-fact
             (-> graph :nodes :n2 :kind)))))
