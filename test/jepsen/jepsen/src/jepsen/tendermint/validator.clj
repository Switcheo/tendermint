(ns jepsen.tendermint.validator
  {:lang :core.typed
   :doc "Supports validator set configuration and changes."}
  (:require [clojure.set :as set]
            [clojure.tools.logging :refer [info warn]]
            [clojure.pprint :refer [pprint]]
            [clojure.core.typed :as t]
            [cheshire.core :as json]
            [dom-top.core :as dt]
            [jepsen.tendermint [client :as tc]
             [util :refer [base-dir]]]
            [jepsen [util :as util :refer [map-vals]]
             [control :as c]
             [client :as client]
             [nemesis :as nemesis]
             [generator :as gen]])
  (:import (clojure.tools.logging.impl LoggerFactory Logger)
           (clojure.lang Namespace
                         Symbol)))

; Type support
(defmacro tk
  "Typechecked keyword function. Returns the given keyword, but tells
  core.typed it's a function of [m -> v]."
  [kw m v]
  `(t/ann-form ~kw [~m ~'-> ~v]))

(defmacro tmfn
  "Typed map fn. Core.typed doesn't know (Map a b) is also the fn [a -> (Option
  b)], so we have to tell it."
  [m K V]
  `(t/fn [k# :- ~K] :- (t/Option ~V)
     (get ~m k#)))


; Domain types


(t/defalias Node
  "Jepsen nodes are strings."
  String)

(t/defalias Test
  "Jepsen tests have nodes and a current validator atom."
  (HMap :mandatory {:nodes            (t/NonEmptyVec  Node)
                    :validator-config (t/Atom1        Config)}
        :optional {:dup-validators              Boolean
                   :max-byzantine-vote-fraction Number
                   :super-byzantine-validators  Boolean}))

(t/defalias Version
  "Tendermint cluster version numbers."
  Long)

(t/defalias ShortKey
  "In some places, Tendermint represents keys only by their raw data."
  String)

(t/defalias Key
  "A key is a map with :type and :data. Tendermint uses this to represent
  public and private keys in validators"
  (HMap :mandatory {:type String
                    :data ShortKey}
        :complete? true))

(t/defalias GenValidator
  "The structure of a validator as generated by tendermint, and stored in
  priv_validator.json. Does not include votes."
  (HMap :mandatory {:address  String
                    :pub_key  Key
                    :priv_key Key}))

(t/defalias Validator
  "A Validator's complete structure, including both votes and information
  necessary to construct priv_validator.json & genesis.json."
  (HMap :mandatory {:address  String
                    :pub_key  Key
                    :priv_key Key
                    :votes    Long}))

(t/defalias Config
  "A configuration represents a definite state of the cluster: the validators
  which are a part of the cluster, what nodes are running what validators, the
  version number of the config in tendermint, the nodes that are in the test,
  etc.

  :prospective-validators is used to track validators we *try* to add to the
  cluster, but which haven't *actually* been added yet."
  (HMap :mandatory {:version                      Version
                    :node-set                     (t/Set Node)
                    :nodes                        (t/Map Node Key)
                    :validators                   (t/Map Key Validator)
                    :prospective-validators       (t/Map Key Validator)
                    :max-byzantine-vote-fraction  Number
                    :super-byzantine-validators   Boolean}))

(t/defalias TendermintValidator
  "The cluster's representation of a validator."
  (t/HMap :mandatory {:pub_key ShortKey
                      :power   Long}))

(t/defalias TendermintValidatorSet
  "The cluster's representation of a validator set."
  (t/HMap :mandatory {:validators (t/Coll TendermintValidator)
                      :version     Version}))

(t/defalias CreateTransition
  "Create an instance of a validator on a node"
  (t/HMap :mandatory {:type       (t/Val :create)
                      :node       Node
                      :validator  Validator}
          :complete? true))

(t/defalias DestroyTransition
  "Destroy an instance of a validator"
  (t/HMap :mandatory {:type (t/Val :destroy)
                      :node Node}
          :complete? true))

(t/defalias AddTransition
  "Add a new validator to the config"
  (t/HMap :mandatory {:type       (t/Val :add)
                      :version    Version
                      :validator  Validator}
          :complete? true))

(t/defalias RemoveTransition
  "Remove a validator from the config"
  (t/HMap :mandatory {:type     (t/Val :remove)
                      :version  Version
                      :pub_key  Key}
          :complete? true))

(t/defalias AlterVotesTransition
  "Change the votes allocated to a validator"
  (t/HMap :mandatory {:type     (t/Val :alter-votes)
                      :version  Version
                      :pub_key  Key
                      :votes    Long}
          :complete? true))

(t/defalias Transition (t/U CreateTransition
                            DestroyTransition
                            AddTransition
                            RemoveTransition
                            AlterVotesTransition))

; External types

(t/ann jepsen.control/*dir* String)
(t/ann jepsen.tendermint.util/base-dir String)

(t/ann ^:no-check clojure.core/update
       (t/All [m k v v' arg ...]
              (t/IFn
               [m k [v arg ... arg -> v'] arg ... arg -> (t/Assoc m k v')])))

(t/ann ^:no-check clojure.tools.logging/*logger-factory* LoggerFactory)

(t/ann ^:no-check clojure.tools.logging.impl/get-logger
       [LoggerFactory (t/U clojure.lang.Symbol Namespace)
        -> clojure.tools.logging.impl.Logger])

(t/ann ^:no-check clojure.tools.logging.impl/enabled?
       [Logger t/Keyword -> Boolean])

(t/ann ^:no-check clojure.tools.logging/log*
       [Logger t/Keyword (t/U Throwable nil) String -> nil])

(t/ann ^:no-check jepsen.util/map-vals
       (t/All [k v1 v2]
              [[v1 -> v2] (t/Map k v1) -> (t/Map k v2)]))

(t/ann ^:no-check jepsen.control/on-nodes
       (t/All [res]
              (t/IFn [Test [Test Node -> res]
                      -> (t/Map Node res)]
                     [Test (t/NonEmptyColl Node) [Test Node -> res]
                      -> (t/I (t/Map Node res)
                              (t/NonEmptySeqable
                               (clojure.lang.AMapEntry Node res)))])))

(t/ann ^:no-check jepsen.control/expand-path [String -> String])

(t/ann ^:no-check jepsen.control/exec [t/Any * -> String])

(t/ann ^:no-check cheshire.core/parse-string [String true ->
                                              (t/Map t/Keyword t/Any)])

(t/ann jepsen.tendermint.client/validator-set
       [Node -> TendermintValidatorSet])

; A regression in core.typed breaks occurrence typing for locals (!?), so we
; can only convince the type system of filters using function args.
(t/ann conform-map [t/Any -> (t/Map t/Any t/Any)])
(defn conform-map
  [x]
  (assert (map? x))
  x)

(t/ann conform-string [t/Any -> String])
(defn conform-string
  [x]
  (assert (string? x))
  x)

(t/ann conform-long [t/Any -> Long])
(defn conform-long
  [x]
  (assert (instance? Long x))
  x)

(t/ann conform-key [t/Any -> Key])
(defn conform-key
  [x]
  (let [m (conform-map x)]
    {:type (conform-string (:type m))
     :data (conform-string (:data m))}))

(t/ann conform-gen-validator [t/Any -> GenValidator])
(defn conform-gen-validator
  [x]
  (let [m (conform-map x)]
    {:address   (conform-string (:address x))
     :pub_key   (conform-key    (:pub_key x))
     :priv_key  (conform-key    (:priv_key x))}))

; OK, let's begin

(t/ann nodes-running-validators [Config -> (t/Map Key (t/Coll Node))])
(defn nodes-running-validators
  "Takes a config, yielding a map of validator keys to groups of nodes that run
  that validator."
  [config]
  (->> (:nodes config)
       (reduce (t/fn [m               :- (t/Map Key (t/Vec Node))
                      [node pub-key]  :- '[Node Key]]
                 (assoc m pub-key (conj (get m pub-key []) node)))
               {})))

(t/ann ^:no-check byzantine-validators [Config -> (t/Coll Validator)])
(defn byzantine-validators
  "A collection of all validators in the validator set which are running on
  more than one node."
  [config]
  (->> (nodes-running-validators config)
       (filter (t/fn [[key nodes] :- '[Key (t/Coll Node)]]
                 (< 1 (count nodes))))
       (map key)
       (keep (tmfn (:validators config) Key Validator))))

(t/ann initial-validator-votes [Config -> (t/Map Key Long)])
(defn initial-validator-votes
  "Takes a config. Computes a map of validator public keys to votes. When there
  are byzantine validators and the config has :super-byzantine-validators
  enabled, allocates just shy of 2/3 votes to the byzantine validator.
  Otherwise, allocates just shy of 1/3 votes to the byzantine validator."
  [config]
  (if-let [bs (seq (byzantine-validators config))]
    (do (assert (= 1 (count bs))
                "Only know how to deal with 1 or 0 byzantine validators")
        (let [b (:pub_key (first bs))
              n (count (:validators config))]
          ; For super dup validators, we want the dup validator key to have
          ; just shy of 2/3 voting power. That means the sum of the normal
          ; nodes weights should be just over 1/3, so that the remaining node
          ; can make up just under 2/3rds of the votes by itself. Let a normal
          ; node's weight be 2. Then 2(n-1) is the combined voting power of the
          ; normal bloc. We can then choose 4(n-1) - 1 as the weight for the
          ; dup validator. The total votes are
          ;
          ;    2(n-1) + 4(n-1) - 1
          ;  = 6(n-1) - 1
          ;
          ; which implies a single dup node has fraction...
          ;
          ;    (4(n-1) - 1) / (6(n-1) - 1)
          ;
          ; which approaches 2/3 from 0 for n = 1 -> infinity, and if a single
          ; regular node is added to a duplicate node, a 2/3+ majority is
          ; available for all n >= 1.
          ;
          ; For regular dup validators, let an individual node have weight 2.
          ; The total number of individual votes is 2(n-1), which should be
          ; just larger than twice the number of dup votes, e.g:
          ;
          ;     2(n-1) = 2d + e
          ;
          ; where e is some small positive integer, and d is the number of dup
          ; votes. Solving for d:
          ;
          ;     (2(n-1) - e) / 2 = d
          ;          n - 1 - e/2 = d    ; Choose e = 2
          ;                n - 2 = d
          ;
          ; The total number of votes is therefore:
          ;
          ;     2(n-1) + n - 2
          ;   = 3n - 4
          ;
          ; So a dup validator alone has vote fraction:
          ;
          ;     (n - 2) / (3n - 4)
          ;
          ; which is always under 1/3. And with a single validator, it has vote
          ; fraction:
          ;
          ;     (n - 2) + 2 / (3n - 4)
          ;   =           n / (3n - 4)
          ;
          ; which is always over 1/3.
          (let [base-votes (zipmap (remove #{b} (keys (:validators config)))
                                   (repeat 2))
                byz-votes  {b (conform-long
                               (if (:super-byzantine-validators config)
                                 (dec (* 4 (dec n)))
                                 (- n 2)))}]
            (t/ann-form base-votes (t/Map Key Long))
            (merge base-votes byz-votes))))

    ; Default case: no byzantine validator, everyone has 2 votes.
    (zipmap (keys (:validators config)) (repeat 2))))

(t/ann with-initial-validator-votes [Config -> Config])
(defn with-initial-validator-votes
  "Takes a config, computes the correct distribution of initial validator
  votes, and assigns those votes to validators, returning the resulting
  config."
  [config]
  (let [votes (initial-validator-votes config)
        validators (reduce (t/fn [m :- (t/Map Key Validator)
                                  [k votes] :- '[Key Long]]
                             (let [v (get m k)]
                               (assert v)
                               (assoc m k (assoc v :votes votes))))
                           (:validators config)
                           (initial-validator-votes config))]
    (assoc config :validators validators)))

(t/ann gen-validator [-> GenValidator])
(defn gen-validator
  "Generate a new validator structure, and return the validator's data as a
  map."
  []
  (conform-gen-validator
   (c/cd base-dir
         (-> (c/exec "./tendermint" :--home base-dir :gen_validator)
             (json/parse-string true)))))

(t/ann augment-gen-validator [GenValidator -> Validator])
(defn augment-gen-validator
  "Takes a GenValidator, as generated by tendermint, and adds :votes to make it
  a complete representation of a Validator."
  [v]
  (assoc v :votes 2))

(t/ann config [(HMap :optional {:version    Version
                                :node-set   (t/Set Node)
                                :nodes      (t/Map Node Key)
                                :validators (t/Map Key Validator)
                                :super-byzantine-validators Boolean
                                :max-byzantine-vote-fraction Number})
               -> Config])
(defn config
  "There are two pieces of state we need to handle. The first is the validator
  set, as known to the cluster, which maps public keys to maps like:

      {:address
       :pub_key {:type ...
                 :data ...}
       :priv_key {:type ...
                  :data ...}
       :votes    an-int}

  And the second is a map of nodes to the validator key they're running:

      {\"n1\" \"ABCD...\"
       ...}

  Additionally, we need a bound :max-byzantine-vote-fraction on the fraction of
  the vote any byzantine validator is allowed to control, a :version, denoting
  the version of the validator set that the cluster knows, and a :node-set, the
  set of nodes that exist."
  [opts]
  (merge {:validators             {}
          :nodes                  {}
          :node-set               #{}
          :version                -1
          :max-byzantine-vote-fraction 1/3
          :super-byzantine-validators false}
         opts
         {:prospective-validators {}}))

(t/ann initial-config [Test -> Config])
(defn initial-config
  "Constructs an initial configuration for a test with a list of :nodes
  provided."
  [test]
  (let [; Generate a validator for every node
        validators (c/with-test-nodes test
                     (augment-gen-validator (gen-validator)))
        ; Map of nodes to validators
        nodes (map-vals (tk :pub_key Validator Key) validators)
        ; Map of validator keys to validators
        validators (reduce (t/fn [m         :- (t/Map Key Validator)
                                  [node v]  :- '[Node Validator]]
                             (assoc m (:pub_key v) v))
                           {}
                           validators)

        ; If we're working with dup validators, run the second validator on 2
        ; nodes and drop the first.
        [n1 n2]     (:nodes test)
        validators  (if (:dup-validators test)
                      (let [v1 (get nodes n1)]
                        (assert v1)
                        (dissoc validators v1))
                      validators)
        nodes       (if (:dup-validators test)
                      (let [v2 (get validators (get nodes n2))]
                        (assert v2)
                        (assoc nodes n1 (:pub_key v2)))
                      nodes)]
    (t/ann-form validators (t/Map Key Validator))
    (-> {:validators validators
         :nodes      nodes
         :node-set   (set (:nodes test))
         :super-byzantine-validators (:super-byzantine-validators test false)
         :max-byzantine-vote-fraction (:max-byzantine-vote-fraction test 1/3)}
        config
        with-initial-validator-votes)))

(t/ann genesis [Config -> t/Any])
(defn genesis
  "Computes a genesis.json structure for the given config."
  [config]
  {:app_hash      ""
   :chain_id      "jepsen"
   :genesis_time  "0001-01-01T00:00:00.000Z"
   :validators    (->> (:validators config)
                       vals
                       (map (t/fn [validator :- Validator]
                              (let [pub-key (:pub_key validator)
                                    name (->> (:nodes config)
                                              (filter
                                               (t/fn [[_ v] :- '[t/Any Key]]
                                                 (= v pub-key)))
                                              first)
                                    _ (assert name)
                                    name (key name)]
                                {:amount  (:votes validator)
                                 :name    name
                                 :pub_key pub-key}))))})

(t/ann pub-key-on-node [Config Node -> (t/Option Key)])
(defn pub-key-on-node
  "What pubkey is running on a given node?"
  [config node]
  (-> config :nodes (get node)))

(t/ann total-votes [Config -> Number])
(defn total-votes
  "How many votes are in the validator set total?"
  [config]
  (->> (:validators config)
       vals
       (map (tk :votes Validator Number))
       (reduce + 0)))

(t/ann compact-key [Key -> ShortKey])
(defn compact-key
  "A compact, lossy, human-friendly representation of a validator key."
  [k]
  (subs (:data k) 0 5))

(t/ann compact-config [Config -> (HMap)])
(defn compact-config
  "Just the essentials, please. Compacts a config into a human-readable,
  limited representation for debugging."
  [c]
  {:version (:version c)
   :total-votes (total-votes c)
   :prospective-validators (->> (:prospective-validators c)
                                (map (t/fn [[k v] :- '[Key Validator]]
                                       (compact-key k)))
                                sort)
   :validators (->> (:validators c)
                    (map (t/fn [pair :- (clojure.lang.AMapEntry Key Validator)]
                           (let [k (key pair)
                                 v (val pair)]
                             [(compact-key k)
                              {:votes (:votes v)}])))
                    (into (sorted-map)))
   :nodes (map-vals compact-key (:nodes c))
   :max-byzantine-vote-fraction (:max-byzantine-vote-fraction c)})

(t/ann vote-fractions [Config -> (t/Map Key Number)])
(defn vote-fractions
  "A map of validator public keys to the fraction of the vote they control."
  [config]
  (let [total (total-votes config)]
    (->> (:validators config)
         (map-vals (t/fn [v :- Validator]
                     (/ (:votes v) total))))))

(t/ann running-validators [Config -> (t/Option (t/Coll Validator))])
(defn running-validators
  "A collection of validators running on at least one node."
  [config]
  (->> (set (vals (:nodes config)))
       (keep (tmfn (:validators config) Key Validator))))

(t/ann ghost-validators [Config -> (t/Coll Validator)])
(defn ghost-validators
  "A collection of validators not running on any node."
  [config]
  (set/difference (set (vals (:validators config)))
                  (set (running-validators config))))

(t/ann byzantine-validator-keys [Config -> (t/Coll Key)])
(defn byzantine-validator-keys
  "A collection of all validator keys in the validator set which are running on
  more than one node."
  [config]
  (map (tk :pub_key Validator Key) (byzantine-validators config)))

(t/ann dup-groups [Config -> (HMap :mandatory {:groups (t/Coll (t/Coll Node))
                                               :singles (t/Coll (t/Coll Node))
                                               :dups    (t/Coll (t/Coll Node))}
                                   :complete? true)])
(defn dup-groups
  "Takes a config. Computes a map of:

      {:groups  A collection of groups of nodes, each running the same validator
       :singles Groups with only one nodes
       :dups    Groups with multiple nodes}"
  [config]
  (let [groups (-> config nodes-running-validators vals)]
    {:groups  groups
     :singles (filter (t/fn [g :- (t/Coll t/Any)] (= 1 (count g))) groups)
     :dups    (filter (t/fn [g :- (t/Coll t/Any)] (< 1 (count g))) groups)}))

(t/ann at-least-one-running-validator? [Config -> Boolean])
(defn at-least-one-running-validator?
  "Does the given config have at least one validator which is running on some
  node?"
  [config]
  (boolean (seq (running-validators config))))

(t/ann omnipotent-byzantines? [Config -> Boolean])
(defn omnipotent-byzantines?
  "Does this config contain any byzantine validator which controls more than
  max-byzantine-vote-fraction of the vote?"
  [config]
  (let [vfs       (vote-fractions config)
        threshold (:max-byzantine-vote-fraction config)]
    (boolean (some (t/fn [k :- Key]
                     (let [vf (get vfs k)]
                       (assert vf (str "No vote fraction for " k))
                       (<= threshold vf)))
                   (byzantine-validator-keys config)))))

(t/ann ghost-limit Long)
(def ghost-limit
  "Ghosts are souls without bodies. How many validators can exist without
  actually running on any node?"
  2)

(t/ann too-many-ghosts? [Config -> Boolean])
(defn too-many-ghosts?
  "Does this config have too many validators which aren't running on any
  nodes?"
  [config]
  (< ghost-limit
     (count
      (set/difference (set (keys (:validators config)))
                      (set (vals (:nodes config)))))))

(t/ann zombie-limit Long)
(def zombie-limit
  "Zombies are bodies without souls. How many nodes can run a validator that's
  not actually a part of the cluster?"
  2)

(t/ann too-many-zombies? [Config -> Boolean])
(defn too-many-zombies?
  "Does this config have too many nodes which are running validators that
  aren't a part of the cluster?"
  [config]
  (< zombie-limit
     (count
      (remove (set (keys (:validators config)))
              (vals (:nodes config))))))

(t/ann quorum Number)
(def quorum
  "What fraction of the configuration's voting power should be
  online and non-byzantine in order to perform operations?"
  2/3)

(t/ann quorum? [Config -> Boolean])
(defn quorum?
  "Does the given configuration provide a quorum of running votes?"
  [config]
  (< quorum (/ (reduce + 0 (map (tk :votes Validator Number)
                                (running-validators config)))
               (total-votes config))))

(t/ann fault-limit Number)
(def fault-limit
  "What fraction of votes can be either byzantine or ghosts?"
  1/3)

(t/ann faulty? [Config -> Boolean])
(defn faulty?
  "Are too many nodes byzantine or down?"
  [config]
  (<= fault-limit
      (/ (reduce + 0 (map (tk :votes Validator Number)
                          (set/union (set (byzantine-validators config))
                                     (set (ghost-validators config)))))
         (total-votes config))))

(t/ann assert-valid [Config -> Config])
(defn assert-valid
  "Ensures that the given config is valid, and returns it. Throws
  AssertError if not."
  [config]
  (assert (at-least-one-running-validator? config))
  (assert (not (omnipotent-byzantines? config)))
  (assert (not (too-many-ghosts? config)))
  (assert (not (too-many-zombies? config)))
  (assert (quorum? config))
  (assert (not (faulty? config)))
  (assert (every? (:node-set config) (keys (:nodes config))))
  (assert (every? pos? (map (tk :votes Validator Number)
                            (vals (:validators config)))))
  config)

; Possible state transitions:
; - Create an instance of a validator on a node
; - Destroy a validator instance on some node

; - Add a validator to the validator set
; - Remove a validator from the config set

; - Adjust the weight of a validator

(t/ann pre-step [Config Transition -> Config])
(defn pre-step
  "Where `step` defines the consequences of an atomic transition, we don't
  actually get to perform all transitions atomically. In particular, when we
  create or delete a validator, we *request* that the system create or delete
  it, but we don't actually *know* whether it will happen until the transaction
  completes. This function transitions a configuration to that in-between
  state."
  [config transition]
  (assert-valid
   (case (:type transition)
     :create       config
     :destroy      config
     :add          (let [v (:validator transition)]
                     (assert (not (get-in config [:validators (:pub_key v)])))
                     (assoc config :prospective-validators
                            (assoc (:prospective-validators config)
                                   (:pub_key v) v)))
     :remove       config
     :alter-votes  config)))

(t/ann post-step [Config Transition -> Config])
(defn post-step
  "Complete a transition once we know it's been executed."
  [config transition]
  (assert-valid
   (case (:type transition)
      ; Create a new validator on a node
     :create (let [n (:node transition)
                   v (:validator transition)]
               (assert (not (get-in config [:nodes n])))
               (assoc config :nodes (assoc (:nodes config) n (:pub_key v))))

      ; Destroy a validator on a node
     :destroy (assoc config :nodes (dissoc (:nodes config)
                                           (:node transition)))


      ; Add a validator to the validator set


     :add (let [v (:validator transition)]
            (assert (not (get-in config [:validators (:pub_key v)])))
            (-> config
                (assoc :prospective-validators
                       (dissoc (:prospective-validators config)
                               (:pub_key v)))
                (assoc :validators
                       (assoc (:validators config) (:pub_key v) v))))

      ; Remove a validator from the validator set
     :remove (assoc config :validators
                    (dissoc (:validators config) (:pub_key transition)))

      ; Change the votes allocated to a validator
     :alter-votes (let [k (:pub_key transition)
                        v (:votes transition)
                        validators (:validators config)
                        validator  (get validators k)
                        _ (assert validator)
                        validator' (assoc validator :votes v)
                        validators' (assoc validators k validator')]
                    (assoc config :validators validators')))))

(t/ann step [Config Transition -> Config])
(defn step
  "Apply a low-level state transition to a config, returning a new config.
  Throws if the requested transition is illegal."
  [config transition]
  (-> config
      (pre-step transition)
      (post-step transition)))

(t/ann rand-validator [Config -> Validator])
(defn rand-validator
  "Selects a random validator from the config."
  [config]
  (rand-nth (vals (:validators config))))

(t/ann rand-free-node [Config -> (t/Option Node)])
(defn rand-free-node
  "Selects a random node which isn't running anything."
  [config]
  (when-let [candidates (seq (set/difference (:node-set config)
                                             (set (keys (:nodes config)))))]
    (rand-nth candidates)))

(t/ann rand-taken-node [Config -> (t/Option Node)])
(defn rand-taken-node
  "Selects a random node that's running a validator."
  [config]
  (rand-nth (keys (:nodes config))))

(t/ann rand-transition [Test Config -> Transition])
(defn rand-transition
  "Generates a random transition on the given config."
  [test config]
  (or (condp <= (rand)
        ; Create a new instance of a validator on a node.
        4/5 (let [v (rand-validator config)
                  n (rand-free-node config)]
              (when (and v n)
                {:type      :create
                 :node      n
                 :validator v}))

        ;; Nuke a node
        3/5 (when-let [node (rand-taken-node config)]
              {:type :destroy
               :node node})

        ;; Create a new validator
        2/5 (let [v (-> (c/on-nodes test
                                    [(rand-nth (:nodes test))]
                                    (t/fn [test :- Test, node :- Node]
                                      (gen-validator)))
                        first
                        val
                        (assoc :votes 2))]
              {:type      :add
               :version   (:version config)
               :validator v})

        ;; Remove a validator
        1/5 (let [v (rand-validator config)]
              {:type :remove
               :version (:version config)
               :pub_key (:pub_key v)})

        ; Adjust a node's weight
        0/5 (let [v (rand-validator config)]
              {:type    :alter-votes
               :version (:version config)
               :pub_key (:pub_key v)
               ; Long forces core.typed to admit it's not AnyInteger;
               ; strictly speaking we might go out of bounds
               :votes   (long (max 1 (+ (:votes v) (- (rand-int 11) 5))))}))

      ; We rolled an impossible transition; try again
      (recur test config)))

(t/ann ^:no-check rand-legal-transition [Test Config -> Transition])
(defn rand-legal-transition
  "Generates a random transition on the given config which results in a legal
  state."
  [test config]
  (dt/with-retry [i 0]
    (if (<= 100 i)
      (throw (RuntimeException. (str "Unable to generate state transition from "
                                     (pr-str config)
                                     " in less than 100 tries; aborting."
                                     " Last failure was:")))
      (let [t (rand-transition test config)]
        (step config t)
        t))
    (catch AssertionError e
      (retry (inc i)))))

(t/ann prospective-validator-by-short-key
       [Config ShortKey -> (t/Option Validator)])
(defn prospective-validator-by-short-key
  "Looks up a prospective validator by key data alone; e.g. instead of by
  {:type ...  :data ...}."
  [config pub-key-data]
  (t/loop [validators :- (t/Option (t/NonEmptyASeq Validator))
           (seq (vals (:prospective-validators config)))]
    (when validators
      (if (= pub-key-data (:data (:pub_key (first validators))))
        (first validators)
        (recur (next validators))))))

(t/ann validator-by-short-key [Config ShortKey -> (t/Option Validator)])
(defn validator-by-short-key
  "Looks up a validator by key data alone; e.g. instead of by {:type ... :data
  ...}."
  [config pub-key-data]
  (t/loop [validators :- (t/Option (t/NonEmptyASeq Validator))
           (seq (vals (:validators config)))]
    (when validators
      (if (= pub-key-data (:data (:pub_key (first validators))))
        (first validators)
        (recur (next validators))))))

(t/ann tendermint-validator-set->vote-map
       [Config TendermintValidatorSet -> (t/Map Key Long)])
(defn tendermint-validator-set->vote-map
  "Converts a map of tendermint short keys to tendermint validators into a
  map of full public keys to votes."
  [config validator-set]
  (->> validator-set
       :validators
       (map (t/fn [v :- TendermintValidator]
              (let [short-key (:pub_key v)
                    k (or (validator-by-short-key             config short-key)
                          (prospective-validator-by-short-key config short-key)
                          (throw (IllegalStateException.
                                  (str "Don't recognize cluster validator "
                                       (pr-str v)
                                       "; where did it come from?"))))]
                [(:pub_key k) (:power v)])))
       (into {})))

(t/ann clear-removed-nodes [Config (t/Map Key Long) -> Config])
(defn clear-removed-nodes
  "Takes a config and a map of validator keys to votes, and clears out config
  validators which no longer exist in votes map."
  [config votes]
  (->> (:validators config)
       (filter (t/fn [[k v] :- '[Key Validator]]
                 (contains? votes k)))
       (into {})
       (assoc config :validators)))

(t/ann update-known-nodes [Config (t/Map Key Long) -> Config])
(defn update-known-nodes
  "Takes a config and a map of validator keys to votes, and where a key is
  present in the vote map but is not yet a validator in the config, promotes
  that validator from :prospective-validators to :validators in the config.
  Also updates all votes in the config."
  [config votes]
  (reduce (t/fn [config :- Config, [k v] :- '[Key Long]]
            (let [validators (:validators config)]
              (if-let [validator (get validators k)]
                ; We know this validator already
                (let [validator (assoc validator :votes v)
                      validators (assoc validators k validator)]
                  (assoc config :validators validators))

                ; Promote from prospective-validators
                (let [prospective (:prospective-validators config)
                      validator   (get prospective k)
                      _           (assert validator
                                          (str "Don't recognize validator "
                                               k "; where did it come from?"))
                      validator   (assoc validator :votes v)
                      validators  (assoc validators k validator)
                      prospective (dissoc prospective k)]
                  (assoc config
                         :validators              validators
                         :prospective-validators  prospective)))))
          config
          votes))

(t/ann current-config [Test Node -> Config])
(defn current-config
  "Combines our internal test view of which nodes are running what validators
  with a transactional read of the current state of validator votes, producing
  a config that can be used to generate cluster transitions."
  [test node]
  ; TODO: improve node selection
  (let [local-config   @(:validator-config test)
        cluster-config (tc/validator-set node)
        votes          (tendermint-validator-set->vote-map
                        local-config cluster-config)]
    (-> local-config
        (clear-removed-nodes votes)
        (update-known-nodes votes)
        (assoc :version (:version cluster-config)))))

(t/tc-ignore

 (defn refresh-config!
   "Attempts to update the test's config with new information from the cluster.
  Returns our estimate of the current config. Not threadsafe."
   [test]
  ; TODO: make this threadsafe
   (or (reduce (fn [_ node]
                 (try
                   (when-let [c (current-config test node)]
                     (reset! (:validator-config test) c)
                     (reduced c))
                   (catch java.io.IOException e
                    ; (info e "unable to fetch current validator set config")
                     nil)))
               nil
               (shuffle (:nodes test)))
       @(:validator-config test)))

 (defn generator
   "A generator of legal state transitions on the current validator state."
   []
   (reify gen/Generator
     (op [this test process]
       (try
         (info "refreshing config")
         (let [config (refresh-config! test)]
           (info :config-refreshed)
           (info (with-out-str (pprint config)))
           (info (with-out-str (pprint (compact-config config))))
           {:type  :info
            :f     :transition
            :value (rand-legal-transition test config)})
         (catch Exception e
           (warn e "error generating transition")
           (throw e)))))))
