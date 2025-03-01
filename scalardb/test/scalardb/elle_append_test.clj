(ns scalardb.elle-append-test
  (:require [clojure.test :refer :all]
            [jepsen.client :as client]
            [scalardb.core :as scalar]
            [scalardb.elle-append :as elle-append]
            [spy.core :as spy])
  (:import (com.scalar.db.api DistributedTransaction
                              Get
                              Put
                              Result)
           (com.scalar.db.io Key
                             TextValue)
           (com.scalar.db.exception.transaction CommitException
                                                CrudException
                                                UnknownTransactionStatusException)
           (java.util Optional)))

(def ^:dynamic test-records (atom {0 {} 1 {} 2 {} 3 {} 4 {}}))

(def ^:dynamic get-count (atom 0))
(def ^:dynamic put-count (atom 0))
(def ^:dynamic commit-count (atom 0))

(defn- key->id
  [^Key k]
  (-> k .get first .get))

(defn- mock-result
  [id]
  (reify
    Result
    (getValue [this column]
      (if-let [v (get (@test-records id) (keyword column))]
        (->> v (TextValue. column) Optional/of)
        (Optional/empty)))))

(defn- mock-get
  [^Get g]
  (let [id (-> g .getPartitionKey key->id)]
    (swap! get-count inc)
    (if (seq (@test-records id))
      (Optional/of (mock-result id))
      (Optional/empty))))

(defn- mock-put
  [^Put p]
  (let [id (-> p .getPartitionKey key->id)
        v (some-> p .getValues (get "val") .getString .get)]
    (swap! test-records #(update % id assoc :val v))
    (swap! put-count inc)))

(def mock-transaction
  (reify
    DistributedTransaction
    (^Optional get [this ^Get g] (mock-get g))
    (^void put [this ^Put p] (mock-put p))
    (^void commit [this] (swap! commit-count inc))))

(def mock-transaction-throws-exception
  (reify
    DistributedTransaction
    (^Optional get [this ^Get g] (throw (CrudException. "get failed")))
    (^void put [this ^Put p] (throw (CrudException. "put failed")))
    (^void commit [this] (throw (CommitException. "commit failed")))))

(def mock-transaction-throws-unknown
  (reify
    DistributedTransaction
    (getId [this] "unknown-state-tx")
    (^Optional get [this ^Get g] (mock-get g))
    (^void put [this ^Put p] (mock-put p))
    (^void commit [this] (throw (UnknownTransactionStatusException. "unknown state")))))

(deftest append-client-init-test
  (with-redefs [scalar/setup-transaction-tables (spy/spy)
                scalar/prepare-transaction-service! (spy/spy)]
    (let [client (client/open! (elle-append/->AppendClient (atom false))
                               nil nil)]
      (client/setup! client nil)
      (is (true? @(:initialized? client)))
      (is (spy/called-n-times? scalar/setup-transaction-tables 6))
      (is (spy/called-once? scalar/prepare-transaction-service!))

        ;; setup isn't executed
      (client/setup! client nil)
      (is (spy/called-n-times? scalar/setup-transaction-tables 6)))))

(deftest append-client-invoke-test
  (binding [test-records (atom {0 {} 1 {} 2 {} 3 {} 4 {}})
            get-count (atom 0)
            put-count (atom 0)
            commit-count (atom 0)]
    (with-redefs [scalar/start-transaction (spy/stub mock-transaction)]
      (let [client (client/open! (elle-append/->AppendClient (atom false))
                                 nil nil)
            result (client/invoke! client
                                   {:isolation-level :serializable
                                    :serializable-strategy :extra-write
                                    :table-id (atom 1)}
                                   {:type :invoke
                                    :f :txn
                                    :value [0
                                            [[:r 1 nil]
                                             [:append 1 0]
                                             [:r 0 nil]
                                             [:append 1 1]
                                             [:append 3 0]]]})]
        (is (spy/called-once? scalar/start-transaction))
        (is (= 5 @get-count))
        (is (= 3 @put-count))
        (is (= 1 @commit-count))
        (is (= {0 {} 1 {:val "0,1"} 2 {} 3 {:val "0"} 4 {}}
               @test-records))
        (is (= :ok (:type result)))))))

(deftest append-client-invoke-crud-exception-test
  (with-redefs [scalar/start-transaction (spy/stub mock-transaction-throws-exception)
                scalar/try-reconnection-for-transaction! (spy/spy)]
    (let [client (client/open! (elle-append/->AppendClient (atom false))
                               nil nil)
          result (client/invoke! client
                                 {:isolation-level :serializable
                                  :serializable-strategy :extra-write
                                  :table-id (atom 1)}
                                 {:type :invoke
                                  :f :txn
                                  :value [0 [[:r 1 nil]]]})]
      (is (spy/called-once? scalar/start-transaction))
      (is (spy/called-once? scalar/try-reconnection-for-transaction!))
      (is (= :fail (:type result))))))

(deftest append-client-invoke-unknown-exception-test
  (binding [get-count (atom 0)
            put-count (atom 0)]
    (with-redefs [scalar/start-transaction (spy/stub mock-transaction-throws-unknown)
                  scalar/try-reconnection-for-transaction! (spy/spy)]
      (let [client (client/open! (elle-append/->AppendClient (atom false))
                                 nil nil)
            result (client/invoke! client
                                   {:isolation-level :serializable
                                    :serializable-strategy :extra-write
                                    :table-id (atom 1)
                                    :unknown-tx (atom #{})}
                                   {:type :invoke
                                    :f :txn
                                    :value [0
                                            [[:r 1 nil]
                                             [:append 1 0]]]})]
        (is (spy/called-once? scalar/start-transaction))
        (is (spy/not-called? scalar/try-reconnection-for-transaction!))
        (is (= 2 @get-count))
        (is (= 1 @put-count))
        (is (= :info (:type result)))
        (is (= "unknown-state-tx" (get-in result
                                          [:error :unknown-tx-status])))))))
