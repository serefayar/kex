(ns kex.datalog-test
  (:require [kex.datalog :as sut]
            [clojure.test :as t]))

(t/deftest variable?-test
  (t/is (true?  (sut/variable? '?u)))
  (t/is (false? (sut/variable? :read)))
  (t/is (false? (sut/variable? "alice"))))

(t/deftest bind-test

  (t/testing "If the variable is unbound, associates it with the given value"
    (t/is (= '{?u "alice"}
             (sut/bind {} '?u "alice"))))

  (t/testing " If the variable is already bound, ensures the existing binding
  matches the value"
    (t/is (= '{?u "alice"}
             (sut/bind '{?u "alice"} '?u "alice"))))

  (t/testing "return nil if the binding would cause a conflict"
    (t/is (nil?
           (sut/bind '{?u "alice"} '?u "bob")))))

(t/deftest unify-test
  (t/testing "unify a pattern with a concrete fact"
    (let [result (sut/unify
                  '[:right ?u ?a ?r]
                  [:right "alice" :read "file-1"])]

      (t/is (= '{?u "alice" ?a :read ?r "file-1"}
               (:env result)))
      (t/is (= 1 (count (:proof result))))))

  (t/testing "if unification fails return nil"
    (t/is (nil?
           (sut/unify
            '[:right ?u ?a ?r]
            [:user "alice"])))))

(let [facts [[:right "alice" :read "file-1"]
             [:resource "file-1"]]
      body  '[[:right ?u ?a ?r]
              [:resource ?r]]
      results (sut/eval-body body facts)]
  results)

(t/deftest eval-body-test
  (let [facts [[:right "alice" :read "file-1"]
               [:resource "file-1"]]
        body  '[[:right ?u ?a ?r]
                [:resource ?r]]
        results (sut/eval-body body facts)]
    (t/is (= 1 (count results)))
    (t/is (= '{?u "alice" ?a :read ?r "file-1"}
             (:env (first results))))
    (t/is (= 2 (count (:proof (first results)))))))

(t/deftest instantiate-test
  (t/is (= [:can "alice" :read "file-1"]
           (sut/instantiate
            '[:can ?u ?a ?r]
            '{?u "alice" ?a :read ?r "file-1"})))

  (t/is (nil?
         (sut/instantiate
          '[:can ?u ?r]
          '{?u "alice"}))))

(t/deftest fire-rule-test
  (let [facts [[:right "alice" :read "file-1"]]
        rule  '{:id   :can-from-right
                :head [:can ?u ?a ?r]
                :body [[:right ?u ?a ?r]]}
        results (sut/fire-rule rule facts)]
    (t/is (= 1 (count results)))
    (t/is (= [:can "alice" :read "file-1"]
             (:fact (first results))))
    (t/is (= :can-from-right
             (:rule (first results))))))

(t/deftest eval-check-pass-test
  (let [fact-store
        {[:can "alice" :read "file-1"]
         {:origin :derived}}
        check {:id :c1
               :query [[:can "alice" :read "file-1"]]}
        result (sut/eval-check check fact-store)]
    (t/is (= :pass (:result result)))))

(t/deftest eval-check-fail-test
  (let [check {:id :c1
               :query [[:can "alice" :write "file-1"]]}
        result (sut/eval-check check {})]
    (t/is (= :fail (:result result)))))

(t/deftest eval-token-valid-with-explain
  (let [token {:blocks
               [{:facts  [[:right "alice" :read "file-1"]]
                 :rules  '[{:id   :can-from-right
                            :head [:can ?u ?a ?r]
                            :body [[:right ?u ?a ?r]]}]
                 :checks [{:id :c1
                           :query [[:can "alice" :read "file-1"]]}]}]}
        result (sut/eval-token token :explain? true)]
    (t/is (true? (:valid? result)))
    (t/is (some? (:explain result)))))

(t/deftest eval-token-invalid
  (let [token {:blocks
               [{:facts  []
                 :rules  []
                 :checks [{:id :c1
                           :query [[:can "alice" :read "file-1"]]}]}]}
        result (sut/eval-token token :explain? true)]
    (t/is (false? (:valid? result)))
    (t/is (= :fail (get-in result [:explain :result])))))
