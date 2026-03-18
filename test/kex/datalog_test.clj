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
                           :query [[:can "alice" :read "file-1"]]}]
                 :block-index 0}]}
        result (sut/eval-token token :explain? true)]
    (t/is (true? (:valid? result)))
    (t/is (some? (:explain result)))))

(t/deftest eval-token-invalid
  (let [token {:blocks
               [{:facts  []
                 :rules  []
                 :checks [{:id :c1
                           :query [[:can "alice" :read "file-1"]]}]
                 :block-index 0}]}
        result (sut/eval-token token :explain? true)]
    (t/is (false? (:valid? result)))
    (t/is (= :fail (get-in result [:explain :result])))))

;; --- Block Isolation Tests ---

(t/deftest authority-public-fact-visible-in-delegated-block
  (t/testing "public facts from authority block propagate to delegated blocks"
    (let [token {:blocks
                 [{:facts  [[:public/role "alice" :admin]]
                   :rules  []
                   :checks []
                   :block-index 0}
                  {:facts  []
                   :rules  []
                   :checks [{:id :c1
                             :query [[:public/role "alice" :admin]]}]
                   :block-index 1}]}
          result (sut/eval-token token)]
      (t/is (true? (:valid? result))))))

(t/deftest delegated-public-fact-does-not-propagate
  (t/testing "public facts from delegated blocks do NOT propagate to subsequent blocks"
    (let [token {:blocks
                 [{:facts  [[:public/user "alice"]]
                   :rules  []
                   :checks []
                   :block-index 0}
                  {:facts  [[:public/role "alice" :admin]]
                   :rules  []
                   :checks []
                   :block-index 1}
                  {:facts  []
                   :rules  []
                   :checks [{:id :c1
                             :query [[:public/role "alice" :admin]]}]
                   :block-index 2}]}
          result (sut/eval-token token)]
      (t/is (false? (:valid? result))))))

(t/deftest delegated-local-fact-visible-to-own-checks
  (t/testing "delegated block's local facts are visible to its own checks"
    (let [token {:blocks
                 [{:facts  [[:public/user "alice"]]
                   :rules  []
                   :checks []
                   :block-index 0}
                  {:facts  [[:local-note "test"]]
                   :rules  []
                   :checks [{:id :c1
                             :query [[:local-note "test"]]}]
                   :block-index 1}]}
          result (sut/eval-token token)]
      (t/is (true? (:valid? result))))))

(t/deftest delegated-local-derivation-visible-to-own-checks
  (t/testing "delegated block's local derivations are visible to its own checks"
    (let [token {:blocks
                 [{:facts  [[:public/user "alice"]]
                   :rules  []
                   :checks []
                   :block-index 0}
                  {:facts  []
                   :rules  '[{:id   :derive-greeting
                              :head [:greeting "hello"]
                              :body [[:public/user ?u]]}]
                   :checks [{:id :c1
                             :query [[:greeting "hello"]]}]
                   :block-index 1}]}
          result (sut/eval-token token)]
      (t/is (true? (:valid? result))))))

(t/deftest delegated-derivation-does-not-propagate
  (t/testing "delegated block's derivations do NOT propagate to subsequent blocks"
    (let [token {:blocks
                 [{:facts  [[:public/user "alice"]]
                   :rules  []
                   :checks []
                   :block-index 0}
                  {:facts  []
                   :rules  '[{:id   :derive-admin
                              :head [:public/admin "alice"]
                              :body [[:public/user "alice"]]}]
                   :checks []
                   :block-index 1}
                  {:facts  []
                   :rules  []
                   :checks [{:id :c1
                             :query [[:public/admin "alice"]]}]
                   :block-index 2}]}
          result (sut/eval-token token)]
      (t/is (false? (:valid? result))))))

(t/deftest authority-derived-public-fact-propagates
  (t/testing "authority block's derived public facts propagate to delegated blocks"
    (let [token {:blocks
                 [{:facts  [[:public/user "alice"]]
                   :rules  '[{:id   :derive-greeting
                              :head [:public/greeting "alice"]
                              :body [[:public/user "alice"]]}]
                   :checks []
                   :block-index 0}
                  {:facts  []
                   :rules  []
                   :checks [{:id :c1
                             :query [[:public/greeting "alice"]]}]
                   :block-index 1}]}
          result (sut/eval-token token)]
      (t/is (true? (:valid? result))))))

(t/deftest all-block-checks-must-pass
  (t/testing "token is invalid if any block's check fails"
    (let [token {:blocks
                 [{:facts  [[:public/user "alice"]]
                   :rules  []
                   :checks [{:id :c1
                             :query [[:public/user "alice"]]}]
                   :block-index 0}
                  {:facts  []
                   :rules  []
                   :checks [{:id :c2
                             :query [[:public/user "bob"]]}]
                   :block-index 1}]}
          result (sut/eval-token token)]
      (t/is (false? (:valid? result))))))

(t/deftest unnamespaced-fact-redacted-in-proof
  (t/testing "unnamespaced facts are redacted in proof trees"
    (let [token {:blocks
                 [{:facts  [[:secret "x"]]
                   :rules  '[{:id   :derive-result
                              :head [:public/result "y"]
                              :body [[:secret ?s]]}]
                   :checks [{:id :c1
                             :query [[:public/result "y"]]}]
                   :block-index 0}]}
          result (sut/eval-token token :explain? true)
          proof  (get-in result [:explain :because :proof])]
      (t/is (true? (:valid? result)))
      (t/is (every? :redacted? proof)))))

(t/deftest private-fact-redacted-in-proof
  (t/testing "private-namespaced facts are redacted in proof trees"
    (let [token {:blocks
                 [{:facts  [[:private/secret "x"]]
                   :rules  '[{:id   :derive-result
                              :head [:public/result "y"]
                              :body [[:private/secret ?s]]}]
                   :checks [{:id :c1
                             :query [[:public/result "y"]]}]
                   :block-index 0}]}
          result (sut/eval-token token :explain? true)
          proof  (get-in result [:explain :because :proof])]
      (t/is (true? (:valid? result)))
      (t/is (every? :redacted? proof)))))
