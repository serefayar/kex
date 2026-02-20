(ns kex.specs
  (:require [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as st]
            [kex.block :as block]))

(s/def ::fact
  (s/and
   vector?
   (fn [v]
     (and (keyword? (first v))
          (seq (rest v))
          (every? some? (rest v))))))

(s/def ::id keyword?)

(s/def ::head ::fact)

(s/def ::body
  (s/coll-of ::fact :kind vector?))

(s/def ::rule
  (s/keys :req-un [::id ::head ::body]))

(s/def ::query
  (s/coll-of ::fact :kind vector?))

(s/def ::check
  (s/keys :req-un [::id ::query]))

(s/def ::hash
  (s/and bytes?
         #(= 32 (alength ^bytes %))))  ;; SHA-256

(s/def ::sig
  bytes?)

(s/def ::prev
  (s/nilable ::hash))

(s/def ::facts
  (s/coll-of ::fact :kind vector?))

(s/def ::rules
  (s/coll-of ::rule :kind vector?))

(s/def ::checks
  (s/coll-of ::check :kind vector?))

(s/def ::block
  (s/keys
   :req-un [::facts ::rules ::checks ::hash ::sig]
   :opt-un [::prev]))

(s/def ::token
  (s/coll-of ::block :kind vector?))



(comment

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

  (s/valid? ::authority-block (first token))
  )
  

(defn authority-block?
  [block]
  (nil? (:prev block)))

(s/def ::authority-block
  (s/and ::block authority-block?))

(defn delegated-block?
  [block]
  (some? (:prev block)))

(s/def ::delegated-block
  (s/and ::block delegated-block?))

(defn valid-chain-shape?
  [blocks]
  (and
   (seq blocks)
   (nil? (:prev (first blocks)))
   (every?
    (fn [[prev current]]
      (= (:prev current) (:hash prev)))
    (partition 2 1 blocks))))

(s/def ::block-chain
  (s/and
   ::token
   valid-chain-shape?))


(s/fdef block/authority-block
  :args (s/cat
         :facts  ::facts
         :rules  ::rules
         :checks ::checks
         :private-key any?)
  :ret  (s/and
         ::authority-block
         #(nil? (:prev %))))

(s/fdef block/delegated-block
  :args (s/cat
         :previous-block ::block
         :facts          ::facts
         :rules          ::rules
         :checks         ::checks
         :private-key    any?)
  :ret ::delegated-block
  :fn (fn [{:keys [args ret]}]
        (= (:prev ret)
           (:hash (:previous-block args)))))

(s/fdef block/verify-chain
  :args (s/cat
         :blocks     ::block-chain
         :public-key any?)
  :ret boolean?)



(def ^:private fns-with-specs
  [`block/authority-block
   `block/delegated-block
   `block/verify-chain
   ])

(defn instrument []
  (st/instrument fns-with-specs))

(defn unstrument []
  (st/unstrument fns-with-specs))
