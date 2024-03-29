(ns crux.tx
  (:require [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [crux.codec :as c]
            [crux.backup :as backup]
            [crux.db :as db]
            [crux.index :as idx]
            [crux.io :as cio]
            [clojure.java.io :as io]
            [crux.kv :as kv]
            [crux.memory :as mem]
            [crux.status :as status]
            [crux.tx.polling :as p]
            crux.api
            crux.tx.event
            [crux.query :as q]
            [taoensso.nippy :as nippy])
  (:import crux.codec.EntityTx
           crux.tx.consumer.Message
           java.io.Closeable
           [java.util.concurrent ExecutorService Executors TimeoutException TimeUnit]
           java.util.Date))

(set! *unchecked-math* :warn-on-boxed)

(def ^:private date? (partial instance? Date))

(s/def ::tx-id nat-int?)
(s/def ::tx-time date?)

(defmulti conform-tx-op first)

(defmethod conform-tx-op ::put [tx-op]
  (let [[op doc & args] tx-op
        id (:crux.db/id doc)]
    (into [::put id doc] args)))

(defmethod conform-tx-op ::cas [tx-op]
  (let [[op old-doc new-doc & args] tx-op
        new-id (:crux.db/id new-doc)
        old-id (:crux.db/id old-doc)]
    (if (or (= nil old-id) (= new-id old-id))
      (into [::cas new-id old-doc new-doc] args)
      (throw (IllegalArgumentException.
              (str "CAS, document ids do not match: " old-id " " new-id))))))

(defmethod conform-tx-op :default [tx-op] tx-op)

(defn tx-op->docs [tx-op]
  (let [[op id & args] (conform-tx-op tx-op)]
    (filter map? args)))

(defn tx-op->tx-event [tx-op]
  (let [[op id & args] (conform-tx-op tx-op)]
    (doto (into [op (str (c/new-id id))]
                (for [arg args]
                  (if (map? arg)
                    (-> arg c/new-id str)
                    arg)))
      (->> (s/assert :crux.tx.event/tx-event)))))

(defn tx-event->tx-op [[op id & args] snapshot object-store]
  (doto (into [op]
              (concat (when (contains? #{:crux.tx/delete :crux.tx/evict :crux.tx/fn} op)
                        [(c/new-id id)])

                      (for [arg args]
                        (or (when (satisfies? c/IdToBuffer arg)
                              (or (db/get-single-object object-store snapshot arg)
                                  {:crux.db/id (c/new-id id)
                                   :crux.db/evicted? true}))
                            arg))))
    (->> (s/assert :crux.api/tx-op))))

(defmulti index-tx-event
  (fn [[op :as tx-event] indexer kv object-store snapshot tx-log transact-time tx-id]
    op))

(defn- put-delete-kvs [object-store snapshot k start-valid-time end-valid-time transact-time tx-id content-hash]
  (let [eid (c/new-id k)
        ->new-entity-tx (fn [vt]
                          (c/->EntityTx eid vt transact-time tx-id content-hash))

        start-valid-time (or start-valid-time transact-time)

        new-entity-txs (if end-valid-time
                         (when-not (= start-valid-time end-valid-time)
                           (let [entity-history (idx/entity-history-seq-descending snapshot eid end-valid-time transact-time)]
                             (concat (->> (cons start-valid-time
                                                (->> (map #(.vt ^EntityTx %) entity-history)
                                                     (take-while #(neg? (compare start-valid-time %)))))
                                          (remove #{end-valid-time})
                                          (map ->new-entity-tx))

                                     [(if-let [entity-to-restore ^EntityTx (first entity-history)]
                                        (-> entity-to-restore
                                            (assoc :vt end-valid-time))

                                        (c/->EntityTx eid end-valid-time transact-time tx-id (c/nil-id-buffer)))])))

                         (->> (cons start-valid-time
                                    (with-open [i (kv/new-iterator snapshot)]
                                      (let [entity-as-of-idx (idx/new-entity-as-of-index i start-valid-time transact-time)]
                                        (when-let [visible-entity (some-> (idx/entity-at entity-as-of-idx eid)
                                                                          (select-keys [:tx-time :tx-id :content-hash]))]
                                          (->> (idx/entity-history-seq-ascending snapshot eid start-valid-time transact-time)
                                               (remove #{start-valid-time})
                                               (take-while #(= visible-entity (select-keys % [:tx-time :tx-id :content-hash])))
                                               (mapv #(.vt ^EntityTx %)))))))

                              (map ->new-entity-tx)))]

    (->> new-entity-txs
         (mapcat (fn [^EntityTx etx]
                   [[(c/encode-entity+vt+tt+tx-id-key-to
                      nil
                      (c/->id-buffer (.eid etx))
                      (.vt etx)
                      (.tt etx)
                      (.tx-id etx))
                     (c/->id-buffer (.content-hash etx))]
                    [(c/encode-entity+z+tx-id-key-to
                      nil
                      (c/->id-buffer (.eid etx))
                      (c/encode-entity-tx-z-number (.vt etx) (.tt etx))
                      (.tx-id etx))
                     (c/->id-buffer (.content-hash etx))]]))
         (into []))))

(defmethod index-tx-event :crux.tx/put [[op k v start-valid-time end-valid-time] indexer kv object-store snapshot tx-log transact-time tx-id]
  ;; This check shouldn't be required, under normal operation - the ingester checks for this before indexing
  ;; keeping this around _just in case_ - e.g. if we're refactoring the ingest code
  {:pre-commit-fn #(let [content-hash (c/new-id v)
                         correct-state? (db/known-keys? object-store snapshot [content-hash])]
                     (when-not correct-state?
                       (log/error "Put, incorrect doc state for:" content-hash "tx id:" tx-id))
                     correct-state?)
   :kvs (put-delete-kvs object-store snapshot k start-valid-time end-valid-time transact-time tx-id (c/new-id v))})

(defmethod index-tx-event :crux.tx/delete [[op k start-valid-time end-valid-time] indexer kv object-store snapshot tx-log transact-time tx-id]
  {:kvs (put-delete-kvs object-store snapshot k start-valid-time end-valid-time transact-time tx-id nil)})

(defmethod index-tx-event :crux.tx/cas [[op k old-v new-v at-valid-time :as cas-op] indexer kv object-store snapshot tx-log transact-time tx-id]
  (let [eid (c/new-id k)
        valid-time (or at-valid-time transact-time)]

    {:pre-commit-fn #(let [{:keys [content-hash] :as entity} (first (idx/entities-at snapshot [eid] valid-time transact-time))]
                       ;; see juxt/crux#362 - we'd like to just compare content hashes here, but
                       ;; can't rely on the old content-hashing returning the same hash for the same document
                       (if (= (db/get-single-object object-store snapshot (c/new-id content-hash))
                              (db/get-single-object object-store snapshot (c/new-id old-v)))
                         (let [correct-state? (not (nil? (db/get-single-object object-store snapshot (c/new-id new-v))))]
                           (when-not correct-state?
                             (log/error "CAS, incorrect doc state for:" (c/new-id new-v) "tx id:" tx-id))
                           correct-state?)
                         (do (log/warn "CAS failure:" (cio/pr-edn-str cas-op) "was:" (c/new-id content-hash))
                             false)))

     :kvs (put-delete-kvs object-store snapshot eid valid-time nil transact-time tx-id (c/new-id new-v))}))

(def evict-time-ranges-env-var "CRUX_EVICT_TIME_RANGES")
(def ^:dynamic *evict-all-on-legacy-time-ranges?* (= (System/getenv evict-time-ranges-env-var) "EVICT_ALL"))

(defmethod index-tx-event :crux.tx/evict [[op k & legacy-args] indexer kv object-store snapshot tx-log transact-time tx-id]
  (let [eid (c/new-id k)
        history-descending (idx/entity-history snapshot eid)]
    {:pre-commit-fn #(cond
                       (empty? legacy-args) true

                       (not *evict-all-on-legacy-time-ranges?*)
                       (throw (IllegalArgumentException. (str "Evict no longer supports time-range parameters. "
                                                              "See https://github.com/juxt/crux/pull/438 for more details, and what to do about this message.")))

                       :else (do
                               (log/warnf "Evicting '%s' for all valid-times, '%s' set"
                                          k evict-time-ranges-env-var)
                               true))

     :post-commit-fn #(when tx-log
                        (doseq [^EntityTx entity-tx history-descending]
                          ;; TODO: Direct interface call to help
                          ;; Graal, not sure why this is needed,
                          ;; fails with get-proxy-class issue
                          ;; otherwise.
                          (.submit_doc
                           ^crux.db.TxLog tx-log
                           (.content-hash entity-tx)
                           {:crux.db/id eid, :crux.db/evicted? true})))}))

(def ^:private tx-fn-eval-cache (memoize eval))

(defn log-tx-fn-error [fn-result fn-id body args-id args]
  (log/warn fn-result "Transaction function failure:" fn-id (cio/pr-edn-str body) args-id (cio/pr-edn-str args)))

(def tx-fns-enabled? (Boolean/parseBoolean (System/getenv "CRUX_ENABLE_TX_FNS")))

(defmethod index-tx-event :crux.tx/fn [[op k args-v :as tx-op] indexer kv object-store snapshot tx-log transact-time tx-id]
  (if-not tx-fns-enabled?
    (throw (IllegalArgumentException. (str "Transaction functions not enabled: " (cio/pr-edn-str tx-op))))
    (let [fn-id (c/new-id k)
          db (q/db kv object-store transact-time transact-time)
          {:crux.db.fn/keys [body] :as fn-doc} (q/entity db fn-id)
          {:crux.db.fn/keys [args] :as args-doc} (db/get-single-object object-store snapshot (c/new-id args-v))
          args-id (:crux.db/id args-doc)
          fn-result (try
                      (let [tx-ops (apply (tx-fn-eval-cache body) db (eval args))
                            _ (when tx-ops (s/assert :crux.api/tx-ops tx-ops))
                            docs (mapcat tx-op->docs tx-ops)
                            {arg-docs true docs false} (group-by (comp boolean :crux.db.fn/args) docs)]
                        ;; TODO: might lead to orphaned and unevictable
                        ;; argument docs if the transaction fails. As
                        ;; these nested docs never go to the doc topic,
                        ;; it's slightly less of an issue. It's done
                        ;; this way to support nested fns.
                        (doseq [arg-doc arg-docs]
                          (db/index-doc indexer (c/new-id arg-doc) arg-doc))
                        {:docs (vec docs)
                         :ops-result (vec (for [[op :as tx-event] (map tx-op->tx-event tx-ops)]
                                            (index-tx-event tx-event indexer kv object-store snapshot tx-log transact-time tx-id)))})
                      (catch Throwable t
                        t))]
      (if (instance? Throwable fn-result)
        {:pre-commit-fn (fn []
                          (log-tx-fn-error fn-result fn-id body args-id args)
                          false)}
        (let [{:keys [docs ops-result]} fn-result]
          {:pre-commit-fn #(do (doseq [doc docs]
                                 (db/index-doc indexer (c/new-id doc) doc))
                               (every? true? (for [{:keys [pre-commit-fn]} ops-result
                                                   :when pre-commit-fn]
                                               (pre-commit-fn))))
           :kvs (vec (apply concat
                            (when args-doc
                              [[(c/encode-entity+vt+tt+tx-id-key-to
                                 nil
                                 (c/->id-buffer args-id)
                                 transact-time
                                 transact-time
                                 tx-id)
                                (c/->id-buffer args-v)]
                               [(c/encode-entity+z+tx-id-key-to
                                 nil
                                 (c/->id-buffer args-id)
                                 (c/encode-entity-tx-z-number transact-time transact-time)
                                 tx-id)
                                (c/->id-buffer args-v)]])
                            (map :kvs ops-result)))
           :post-commit-fn #(doseq [{:keys [post-commit-fn]} ops-result
                                    :when post-commit-fn]
                              (post-commit-fn))})))))

(defmethod index-tx-event :default [[op & _] indexer kv object-store snapshot tx-log transact-time tx-id]
  (throw (IllegalArgumentException. (str "Unknown tx-op: " op))))

(def ^:dynamic *current-tx*)

(defrecord KvIndexer [kv tx-log object-store ^ExecutorService stats-executor]
  Closeable
  (close [_]
    (when stats-executor
      (doto stats-executor
        (.shutdown)
        (.awaitTermination 60000 TimeUnit/MILLISECONDS))))

  db/Indexer
  (index-doc [_ content-hash doc]
    (log/debug "Indexing doc:" content-hash)

    (when (not (contains? doc :crux.db/id))
      (throw (IllegalArgumentException.
              (str "Missing required attribute :crux.db/id: " (cio/pr-edn-str doc)))))

    (let [content-hash (c/new-id content-hash)
          evicted? (idx/evicted-doc? doc)]
      (when-let [normalized-doc (if evicted?
                                  (when-let [existing-doc (with-open [snapshot (kv/new-snapshot kv)]
                                                            (db/get-single-object object-store snapshot content-hash))]
                                    (idx/delete-doc-from-index kv content-hash existing-doc))

                                  (idx/index-doc kv content-hash doc))]

        (let [stats-fn #(idx/update-predicate-stats kv evicted? normalized-doc)]
          (if stats-executor
            (.submit stats-executor ^Runnable stats-fn)
            (stats-fn))))

      (db/put-objects object-store [[content-hash doc]])))

  (index-tx [this tx-events tx-time tx-id]
    (s/assert :crux.tx.event/tx-events tx-events)

    (with-open [snapshot (kv/new-snapshot kv)]
      (binding [*current-tx* {:crux.tx/tx-id tx-id
                              :crux.tx/tx-time tx-time
                              :crux.tx.event/tx-events tx-events}]
        (let [tx-command-results (vec (for [[op :as tx-event] tx-events]
                                        (index-tx-event tx-event this kv object-store snapshot tx-log tx-time tx-id)))]
          (log/debug "Indexing tx-id:" tx-id "tx-events:" (count tx-events))
          (if (->> (for [{:keys [pre-commit-fn]} tx-command-results
                         :when pre-commit-fn]
                     (pre-commit-fn))
                   (doall)
                   (every? true?))
            (do (kv/store kv (into (sorted-map-by mem/buffer-comparator) (mapcat :kvs) tx-command-results))
                (doseq [{:keys [post-commit-fn]} tx-command-results
                        :when post-commit-fn]
                  (post-commit-fn)))
            (do (log/warn "Transaction aborted:" (cio/pr-edn-str tx-events) (cio/pr-edn-str tx-time) tx-id)
                (kv/store kv [[(c/encode-failed-tx-id-key-to nil tx-id) c/empty-buffer]])))))))

  (docs-exist? [_ content-hashes]
    (with-open [snapshot (kv/new-snapshot kv)]
      (db/known-keys? object-store snapshot content-hashes)))

  (store-index-meta [_ k v]
    (idx/store-meta kv k v))

  (read-index-meta [_ k]
    (idx/read-meta kv k))

  status/Status
  (status-map [this]
    {:crux.index/index-version (idx/current-index-version kv)
     :crux.tx/latest-completed-tx (db/read-index-meta this :crux.tx/latest-completed-tx)
     :crux.tx-log/consumer-state (db/read-index-meta this :crux.tx-log/consumer-state)}))

(defn ^:deprecated await-no-consumer-lag [indexer timeout-ms]
  ;; this will likely be going away as part of #442
  (let [max-lag-fn #(some->> (db/read-index-meta indexer :crux.tx-log/consumer-state)
                             (vals)
                             (seq)
                             (map :lag)
                             (reduce max 0))]
    (if (cio/wait-while #(if-let [max-lag (max-lag-fn)]
                           (pos? (long max-lag))
                           true)
                        timeout-ms)
      (db/read-index-meta indexer :crux.tx/latest-completed-tx)
      (throw (TimeoutException.
               (str "Timed out waiting for index to catch up, lag is: " (or (max-lag-fn)
                                                                            "unknown")))))))

;;; TODO need to expose this as node/await-tx when 'sync' goes
(defn await-tx [indexer {::keys [tx-id] :as tx} timeout-ms]
  (let [seen-tx (atom nil)]
    (if (cio/wait-while #(let [latest-completed-tx (db/read-index-meta indexer :crux.tx/latest-completed-tx)]
                           (reset! seen-tx latest-completed-tx)
                           (or (nil? latest-completed-tx)
                               (pos? (compare tx-id (:crux.tx/tx-id latest-completed-tx)))))
                        timeout-ms)
      @seen-tx
      (throw (TimeoutException.
              (str "Timed out waiting for: " (cio/pr-edn-str tx)
                   " index has: " (cio/pr-edn-str @seen-tx)))))))

;;; will remove this when 'sync' goes
(defn ^:deprecated await-tx-time [indexer transact-time timeout-ms]
  (let [seen-tx (atom nil)]
    (if (cio/wait-while #(let [latest-completed-tx (db/read-index-meta indexer :crux.tx/latest-completed-tx)]
                           (reset! seen-tx latest-completed-tx)
                           (or (nil? latest-completed-tx)
                               (pos? (compare transact-time (:crux.tx/tx-time latest-completed-tx)))))
                        timeout-ms)
      @seen-tx
      (throw (TimeoutException.
              (str "Timed out waiting for: " (cio/pr-edn-str transact-time)
                   " index has: " (cio/pr-edn-str @seen-tx)))))))
