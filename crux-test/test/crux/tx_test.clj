(ns crux.tx-test
  (:require [clojure.test :as t]
            [clojure.java.io :as io]
            [crux.codec :as c]
            [crux.db :as db]
            [crux.index :as idx]
            [crux.fixtures :as f]
            [crux.fixtures.standalone :as fs]
            [crux.fixtures.api :refer [*api*] :as apif]
            [crux.fixtures.kv :as fkv]
            [crux.tx :as tx]
            [crux.kv :as kv]
            [crux.api :as api]
            [crux.rdf :as rdf]
            [crux.query :as q])
  (:import [java.util Date]
           [java.time Duration]))

(t/use-fixtures :each fs/with-standalone-node fkv/with-kv-dir apif/with-node f/with-silent-test-check)

(def picasso-id :http://dbpedia.org/resource/Pablo_Picasso)
(def picasso-eid (c/new-id picasso-id))

(def picasso
  (-> "crux/Pablo_Picasso.ntriples"
      (rdf/ntriples)
      (rdf/->default-language)
      (rdf/->maps-by-id)
      (get picasso-id)))

;; TODO: This is a large, useful, test that exercises many parts, but
;; might be better split up.
(t/deftest test-can-index-tx-ops-acceptance-test
  (let [content-hash (c/new-id picasso)
        valid-time #inst "2018-05-21"
        {:crux.tx/keys [tx-time tx-id]}
        (api/submit-tx *api* [[:crux.tx/put picasso valid-time]])
        _ (api/sync *api* tx-time nil)
        expected-entities [(c/map->EntityTx {:eid          picasso-eid
                                             :content-hash content-hash
                                             :vt           valid-time
                                             :tt           tx-time
                                             :tx-id        tx-id})]]

    (with-open [snapshot (kv/new-snapshot (:kv-store *api*))]
      (t/testing "can see entity at transact and valid time"
        (t/is (= expected-entities
                 (idx/entities-at snapshot [:http://dbpedia.org/resource/Pablo_Picasso] tx-time tx-time)))
        (t/is (= expected-entities
                 (idx/all-entities snapshot tx-time tx-time))))

      (t/testing "cannot see entity before valid or transact time"
        (t/is (empty? (idx/entities-at snapshot [:http://dbpedia.org/resource/Pablo_Picasso] #inst "2018-05-20" tx-time)))
        (t/is (empty? (idx/entities-at snapshot [:http://dbpedia.org/resource/Pablo_Picasso] tx-time #inst "2018-05-20")))

        (t/is (empty? (idx/all-entities snapshot #inst "2018-05-20" tx-time)))
        (t/is (empty? (idx/all-entities snapshot tx-time #inst "2018-05-20"))))

      (t/testing "can see entity after valid or transact time"
        (t/is (some? (idx/entities-at snapshot [:http://dbpedia.org/resource/Pablo_Picasso] #inst "2018-05-22" tx-time)))
        (t/is (some? (idx/entities-at snapshot [:http://dbpedia.org/resource/Pablo_Picasso] tx-time tx-time))))

      (t/testing "can see entity history"
        (t/is (= [(c/map->EntityTx {:eid          picasso-eid
                                    :content-hash content-hash
                                    :vt           valid-time
                                    :tt           tx-time
                                    :tx-id        tx-id})]
                 (idx/entity-history snapshot :http://dbpedia.org/resource/Pablo_Picasso)))))

    (t/testing "add new version of entity in the past"
      (let [new-picasso (assoc picasso :foo :bar)
            new-content-hash (c/new-id new-picasso)
            new-valid-time #inst "2018-05-20"
            {new-tx-time :crux.tx/tx-time
             new-tx-id   :crux.tx/tx-id}
            (api/submit-tx *api* [[:crux.tx/put new-picasso new-valid-time]])
            _ (api/sync *api* new-tx-time nil)]

        (with-open [snapshot (kv/new-snapshot (:kv-store *api*))]
          (t/is (= [(c/map->EntityTx {:eid          picasso-eid
                                      :content-hash new-content-hash
                                      :vt           new-valid-time
                                      :tt           new-tx-time
                                      :tx-id        new-tx-id})]
                   (idx/entities-at snapshot [:http://dbpedia.org/resource/Pablo_Picasso] new-valid-time new-tx-time)))
          (t/is (= [(c/map->EntityTx {:eid          picasso-eid
                                      :content-hash new-content-hash
                                      :vt           new-valid-time
                                      :tt           new-tx-time
                                      :tx-id        new-tx-id})] (idx/all-entities snapshot new-valid-time new-tx-time)))

          (t/is (empty? (idx/entities-at snapshot [:http://dbpedia.org/resource/Pablo_Picasso] #inst "2018-05-20" #inst "2018-05-21"))))))

    (t/testing "add new version of entity in the future"
      (let [new-picasso (assoc picasso :baz :boz)
            new-content-hash (c/new-id new-picasso)
            new-valid-time #inst "2018-05-22"
            {new-tx-time :crux.tx/tx-time
             new-tx-id   :crux.tx/tx-id}
            (api/submit-tx *api* [[:crux.tx/put new-picasso new-valid-time]])
            _ (api/sync *api* new-tx-time nil)]

        (with-open [snapshot (kv/new-snapshot (:kv-store *api*))]
          (t/is (= [(c/map->EntityTx {:eid          picasso-eid
                                      :content-hash new-content-hash
                                      :vt           new-valid-time
                                      :tt           new-tx-time
                                      :tx-id        new-tx-id})]
                   (idx/entities-at snapshot [:http://dbpedia.org/resource/Pablo_Picasso] new-valid-time new-tx-time)))
          (t/is (= [(c/map->EntityTx {:eid          picasso-eid
                                      :content-hash content-hash
                                      :vt           valid-time
                                      :tt           tx-time
                                      :tx-id        tx-id})]
                   (idx/entities-at snapshot [:http://dbpedia.org/resource/Pablo_Picasso] new-valid-time tx-time)))
          (t/is (= [(c/map->EntityTx {:eid          picasso-eid
                                      :content-hash new-content-hash
                                      :vt           new-valid-time
                                      :tt           new-tx-time
                                      :tx-id        new-tx-id})] (idx/all-entities snapshot new-valid-time new-tx-time))))

        (t/testing "can correct entity at earlier valid time"
          (let [new-picasso (assoc picasso :bar :foo)
                new-content-hash (c/new-id new-picasso)
                prev-tx-time new-tx-time
                prev-tx-id new-tx-id
                new-valid-time #inst "2018-05-22"
                {new-tx-time :crux.tx/tx-time
                 new-tx-id   :crux.tx/tx-id}
                (api/submit-tx *api* [[:crux.tx/put new-picasso new-valid-time]])
                _ (api/sync *api* new-tx-time nil)]

            (with-open [snapshot (kv/new-snapshot (:kv-store *api*))]
              (t/is (= [(c/map->EntityTx {:eid          picasso-eid
                                          :content-hash new-content-hash
                                          :vt           new-valid-time
                                          :tt           new-tx-time
                                          :tx-id        new-tx-id})]
                       (idx/entities-at snapshot [:http://dbpedia.org/resource/Pablo_Picasso] new-valid-time new-tx-time)))
              (t/is (= [(c/map->EntityTx {:eid          picasso-eid
                                          :content-hash new-content-hash
                                          :vt           new-valid-time
                                          :tt           new-tx-time
                                          :tx-id        new-tx-id})] (idx/all-entities snapshot new-valid-time new-tx-time)))

              (t/is (= prev-tx-id (-> (idx/entities-at snapshot [:http://dbpedia.org/resource/Pablo_Picasso] prev-tx-time prev-tx-time)
                                      (first)
                                      :tx-id))))))

        (t/testing "can delete entity"
          (let [new-valid-time #inst "2018-05-23"
                {new-tx-time :crux.tx/tx-time
                 new-tx-id   :crux.tx/tx-id}
                (api/submit-tx *api* [[:crux.tx/delete :http://dbpedia.org/resource/Pablo_Picasso new-valid-time]])
                _ (api/sync *api* new-tx-time nil)]
            (with-open [snapshot (kv/new-snapshot (:kv-store *api*))]
              (t/is (empty? (idx/entities-at snapshot [:http://dbpedia.org/resource/Pablo_Picasso] new-valid-time new-tx-time)))
              (t/testing "first version of entity is still visible in the past"
                (t/is (= tx-id (-> (idx/entities-at snapshot [:http://dbpedia.org/resource/Pablo_Picasso] valid-time new-tx-time)
                                   (first)
                                   :tx-id)))))))))

    (t/testing "can retrieve history of entity"
      (with-open [snapshot (kv/new-snapshot (:kv-store *api*))]
        (let [picasso-history (idx/entity-history snapshot :http://dbpedia.org/resource/Pablo_Picasso)]
          (t/is (= 5 (count (map :content-hash picasso-history))))
          (with-open [i (kv/new-iterator snapshot)]
            (doseq [{:keys [content-hash]} picasso-history
                    :when (not (= (c/new-id nil) content-hash))
                    :let [version-k (c/encode-attribute+entity+content-hash+value-key-to
                                     nil
                                     (c/->id-buffer :http://xmlns.com/foaf/0.1/givenName)
                                     (c/->id-buffer :http://dbpedia.org/resource/Pablo_Picasso)
                                     (c/->id-buffer content-hash)
                                     (c/->value-buffer "Pablo"))]]
              (t/is (kv/get-value snapshot version-k)))))))))

(t/deftest test-can-cas-entity
  (let [{picasso-tx-time :crux.tx/tx-time, picasso-tx-id :crux.tx/tx-id} (api/submit-tx *api* [[:crux.tx/put picasso]])]

    (t/testing "compare and set does nothing with wrong content hash"
      (let [wrong-picasso (assoc picasso :baz :boz)
            {cas-failure-tx-time :crux.tx/tx-time} (api/submit-tx *api* [[:crux.tx/cas wrong-picasso (assoc picasso :foo :bar)]])]

        (api/sync *api* cas-failure-tx-time (Duration/ofMillis 1000))

        (with-open [snapshot (kv/new-snapshot (:kv-store *api*))]
          (t/is (= [(c/map->EntityTx {:eid picasso-eid
                                      :content-hash (c/new-id picasso)
                                      :vt picasso-tx-time
                                      :tt picasso-tx-time
                                      :tx-id picasso-tx-id})]
                   (idx/entity-history snapshot picasso-id))))))

    (t/testing "compare and set updates with correct content hash"
      (let [new-picasso (assoc picasso :new? true)
            {new-tx-time :crux.tx/tx-time, new-tx-id :crux.tx/tx-id} (api/submit-tx *api* [[:crux.tx/cas picasso new-picasso]])]

        (api/sync *api* new-tx-time (Duration/ofMillis 1000))

        (with-open [snapshot (kv/new-snapshot (:kv-store *api*))]
          (t/is (= [(c/map->EntityTx {:eid picasso-eid
                                      :content-hash (c/new-id new-picasso)
                                      :vt new-tx-time
                                      :tt new-tx-time
                                      :tx-id new-tx-id})
                    (c/map->EntityTx {:eid picasso-eid
                                      :content-hash (c/new-id picasso)
                                      :vt picasso-tx-time
                                      :tt picasso-tx-time
                                      :tx-id picasso-tx-id})]
                   (idx/entity-history snapshot picasso-id)))))))

  (t/testing "compare and set can update non existing nil entity"
    (let [ivan {:crux.db/id :ivan, :value 12}
          {ivan-tx-time :crux.tx/tx-time, ivan-tx-id :crux.tx/tx-id} (api/submit-tx *api* [[:crux.tx/cas nil ivan]])]
      (api/sync *api* ivan-tx-time nil)

      (with-open [snapshot (kv/new-snapshot (:kv-store *api*))]
        (t/is (= [(c/map->EntityTx {:eid (c/new-id :ivan)
                                    :content-hash (c/new-id ivan)
                                    :vt ivan-tx-time
                                    :tt ivan-tx-time
                                    :tx-id ivan-tx-id})]
                 (idx/entity-history snapshot :ivan)))))))

(t/deftest test-can-evict-entity
  (let [{put-tx-time :crux.tx/tx-time} (api/submit-tx *api* [[:crux.tx/put picasso #inst "2018-05-21"]])
        {evict-tx-time :crux.tx/tx-time} (api/submit-tx *api* [[:crux.tx/evict picasso-id]])]

    (api/sync *api* evict-tx-time nil)

    (with-open [snapshot (kv/new-snapshot (:kv-store *api*))]
      (let [picasso-history (idx/entity-history snapshot picasso-id)]
        (t/testing "eviction keeps tx history"
          (t/is (= 1 (count (map :content-hash picasso-history)))))
        (t/testing "eviction removes docs"
          (t/is (empty? (db/get-objects (:object-store *api*) snapshot (keep :content-hash picasso-history)))))))))

(t/deftest test-handles-legacy-evict-events
  (let [{put-tx-time ::tx/tx-time, put-tx-id ::tx/tx-id} (api/submit-tx *api* [[:crux.tx/put picasso #inst "2018-05-21"]])

        _ (api/sync *api* put-tx-time nil)

        evict-tx-time #inst "2018-05-22"
        evict-tx-id (inc put-tx-id)

        index-evict! #(db/index-tx (:indexer *api*)
                                   [[:crux.tx/evict picasso-id #inst "2018-05-23"]]
                                   evict-tx-time
                                   evict-tx-id)]

    ;; we have to index these manually because the new evict API won't allow docs
    ;; with the legacy valid-time range
    (t/testing "eviction throws if legacy params and no explicit override"
      (t/is (thrown-with-msg? IllegalArgumentException
                              #"^Evict no longer supports time-range parameters."
                              (index-evict!))))

    (t/testing "no docs evicted yet"
      (with-open [snapshot (kv/new-snapshot (:kv-store *api*))]
        (t/is (seq (db/get-objects (:object-store *api*) snapshot
                                   (->> (idx/entity-history snapshot picasso-id)
                                        (keep :content-hash)))))))

    (binding [tx/*evict-all-on-legacy-time-ranges?* true]
      (index-evict!))

    ;; give the evict loopback time to evict the doc
    (Thread/sleep 500)

    (t/testing "eviction removes docs"
      (with-open [snapshot (kv/new-snapshot (:kv-store *api*))]
        (t/is (empty? (db/get-objects (:object-store *api*) snapshot
                                      (->> (idx/entity-history snapshot picasso-id)
                                           (keep :content-hash)))))))))

(t/deftest test-multiple-txs-in-same-ms-441
  (let [ivan {:crux.db/id :ivan}
        ivan1 (assoc ivan :value 1)
        ivan2 (assoc ivan :value 2)
        t #inst "2019-11-29"]
    (db/index-doc (:indexer *api*) (c/new-id ivan1) ivan1)
    (db/index-doc (:indexer *api*) (c/new-id ivan2) ivan2)

    (db/index-tx (:indexer *api*) [[:crux.tx/put :ivan (c/->id-buffer (c/new-id ivan1))]] t 1)
    (db/index-tx (:indexer *api*) [[:crux.tx/put :ivan (c/->id-buffer (c/new-id ivan2))]] t 2)

    (with-open [snapshot (kv/new-snapshot (:kv-store *api*))]
      (t/is (= [(c/->EntityTx (c/new-id :ivan) t t 2 (c/new-id ivan2))
                (c/->EntityTx (c/new-id :ivan) t t 1 (c/new-id ivan1))]
               (idx/entity-history snapshot (c/new-id :ivan))))

      (t/is (= [(c/->EntityTx (c/new-id :ivan) t t 2 (c/new-id ivan2))]
               (idx/entity-history-seq-descending snapshot (c/new-id :ivan) t t)))

      (t/is (= [(c/->EntityTx (c/new-id :ivan) t t 2 (c/new-id ivan2))]
               (idx/entity-history-seq-ascending snapshot (c/new-id :ivan) t t))))))

(t/deftest test-can-store-doc
  (let [content-hash (c/new-id picasso)]
    (t/is (= 48 (count picasso)))
    (t/is (= "Pablo" (:http://xmlns.com/foaf/0.1/givenName picasso)))

    (db/submit-doc (:tx-log *api*) content-hash picasso)

    (Thread/sleep 1000)

    (with-open [snapshot (kv/new-snapshot (:kv-store *api*))]
      (t/is (= {content-hash picasso}
               (db/get-objects (:object-store *api*) snapshot [content-hash])))

      (t/testing "non existent docs are ignored"
        (t/is (= {content-hash picasso}
                 (db/get-objects (:object-store *api*)
                                 snapshot
                                 [content-hash
                                  "090622a35d4b579d2fcfebf823821298711d3867"])))
        (t/is (empty? (db/get-objects (:object-store *api*) snapshot [])))))))

(t/deftest test-put-delete-range-semantics
  (t/are [txs history] (let [eid (keyword (gensym "ivan"))
                             ivan {:crux.db/id eid, :name "Ivan"}
                             res (mapv (fn [[value & vts]]
                                         (api/submit-tx *api* [(into (if value
                                                                       [:crux.tx/put (assoc ivan :value value)]
                                                                       [:crux.tx/delete eid])
                                                                     vts)]))
                                       txs)
                             first-vt (ffirst history)
                             last-tx-time (:crux.tx/tx-time (last res))]

                         (api/sync *api* last-tx-time nil)

                         (with-open [snapshot (kv/new-snapshot (:kv-store *api*))]
                           (t/is (= (for [[vt tx-idx value] history]
                                      [vt (get-in res [tx-idx :crux.tx/tx-id]) (c/new-id (when value
                                                                                           (assoc ivan :value value)))])

                                    (->> (idx/entity-history-seq-ascending snapshot eid first-vt last-tx-time)
                                         (map (juxt :vt :tx-id :content-hash)))))))

    ;; pairs
    ;; [[value vt ?end-vt] ...]
    ;; [[vt tx-idx value] ...]

    [[26 #inst "2019-11-26" #inst "2019-11-29"]]
    [[#inst "2019-11-26" 0 26]
     [#inst "2019-11-29" 0 nil]]

    ;; re-instates the previous value at the end of the range
    [[25 #inst "2019-11-25"]
     [26 #inst "2019-11-26" #inst "2019-11-29"]]
    [[#inst "2019-11-25" 0 25]
     [#inst "2019-11-26" 1 26]
     [#inst "2019-11-29" 0 25]]

    ;; delete a range
    [[25 #inst "2019-11-25"]
     [nil #inst "2019-11-26" #inst "2019-11-29"]]
    [[#inst "2019-11-25" 0 25]
     [#inst "2019-11-26" 1 nil]
     [#inst "2019-11-29" 0 25]]

    ;; shouldn't override the value at end-vt if there's one there
    [[25 #inst "2019-11-25"]
     [29 #inst "2019-11-29"]
     [26 #inst "2019-11-26" #inst "2019-11-29"]]
    [[#inst "2019-11-25" 0 25]
     [#inst "2019-11-26" 2 26]
     [#inst "2019-11-29" 1 29]]

    ;; should re-instate 28 at the end of the range
    [[25 #inst "2019-11-25"]
     [28 #inst "2019-11-28"]
     [26 #inst "2019-11-26" #inst "2019-11-29"]]
    [[#inst "2019-11-25" 0 25]
     [#inst "2019-11-26" 2 26]
     [#inst "2019-11-28" 2 26]
     [#inst "2019-11-29" 1 28]]

    ;; 26.1 should overwrite the full range
    [[28 #inst "2019-11-28"]
     [26 #inst "2019-11-26" #inst "2019-11-29"]
     [26.1 #inst "2019-11-26"]]
    [[#inst "2019-11-26" 2 26.1]
     [#inst "2019-11-28" 2 26.1]
     [#inst "2019-11-29" 0 28]]

    ;; 27 should override the latter half of the range
    [[25 #inst "2019-11-25"]
     [26 #inst "2019-11-26" #inst "2019-11-29"]
     [27 #inst "2019-11-27"]]
    [[#inst "2019-11-25" 0 25]
     [#inst "2019-11-26" 1 26]
     [#inst "2019-11-27" 2 27]
     [#inst "2019-11-29" 0 25]]

    ;; 27 should still override the latter half of the range
    [[25 #inst "2019-11-25"]
     [28 #inst "2019-11-28"]
     [26 #inst "2019-11-26" #inst "2019-11-29"]
     [27 #inst "2019-11-27"]]
    [[#inst "2019-11-25" 0 25]
     [#inst "2019-11-26" 2 26]
     [#inst "2019-11-27" 3 27]
     [#inst "2019-11-28" 3 27]
     [#inst "2019-11-29" 1 28]]))

;; TODO: This test just shows that this is an issue, if we fix the
;; underlying issue this test should start failing. We can then change
;; the second assertion if we want to keep it around to ensure it
;; keeps working.
(t/deftest test-corrections-in-the-past-slowes-down-bitemp-144
  (let [ivan {:crux.db/id :ivan :name "Ivan"}
        start-valid-time #inst "2019"
        number-of-versions 1000
        tx (api/submit-tx *api* (vec (for [n (range number-of-versions)]
                                       [:crux.tx/put (assoc ivan :version n) (Date. (+ (.getTime start-valid-time) (inc (long n))))])))
        ;; HACK using internal tx/await-tx til we properly deprecate 'sync'
        _ (tx/await-tx (:indexer *api*) tx 10000)]

    (with-open [snapshot (kv/new-snapshot (:kv-store *api*))]
      (let [baseline-time (let [start-time (System/nanoTime)
                                valid-time (Date. (+ (.getTime start-valid-time) number-of-versions))]
                            (t/testing "last version of entity is visible at now"
                              (t/is (= valid-time (-> (idx/entities-at snapshot [:ivan] valid-time (Date.))
                                                      (first)
                                                      :vt))))
                            (- (System/nanoTime) start-time))]

        (let [start-time (System/nanoTime)
              valid-time (Date. (+ (.getTime start-valid-time) number-of-versions))]
          (t/testing "no version is visible before transactions"
            (t/is (nil? (idx/entities-at snapshot [:ivan] valid-time valid-time)))
            (let [corrections-time (- (System/nanoTime) start-time)]
              ;; TODO: This can be a bit flaky. This assertion was
              ;; mainly there to prove the opposite, but it has been
              ;; fixed. Can be added back to sanity check when
              ;; changing indexes.
              #_(t/is (>= baseline-time corrections-time)))))))))

(t/deftest test-can-read-kv-tx-log
  (let [ivan {:crux.db/id :ivan :name "Ivan"}

        tx1-ivan (assoc ivan :version 1)
        tx1-valid-time #inst "2018-11-26"
        {tx1-id :crux.tx/tx-id
         tx1-tx-time :crux.tx/tx-time}
        (api/submit-tx *api* [[:crux.tx/put tx1-ivan tx1-valid-time]])
        _ (api/sync *api* tx1-tx-time nil)

        tx2-ivan (assoc ivan :version 2)
        tx2-petr {:crux.db/id :petr :name "Petr"}
        tx2-valid-time #inst "2018-11-27"
        {tx2-id :crux.tx/tx-id
         tx2-tx-time :crux.tx/tx-time}
        (api/submit-tx *api* [[:crux.tx/put tx2-ivan tx2-valid-time]
                              [:crux.tx/put tx2-petr tx2-valid-time]])
        _ (api/sync *api* tx2-tx-time nil)]

    (with-open [tx-log-context (db/new-tx-log-context (:tx-log *api*))]
      (let [log (db/tx-log (:tx-log *api*) tx-log-context nil)]
        (t/is (not (realized? log)))
        (t/is (= [{:crux.tx/tx-id tx1-id
                   :crux.tx/tx-time tx1-tx-time
                   :crux.tx.event/tx-events [[:crux.tx/put (c/new-id :ivan) (c/new-id tx1-ivan) tx1-valid-time]]}
                  {:crux.tx/tx-id tx2-id
                   :crux.tx/tx-time tx2-tx-time
                   :crux.tx.event/tx-events [[:crux.tx/put (c/new-id :ivan) (c/new-id tx2-ivan) tx2-valid-time]
                                             [:crux.tx/put (c/new-id :petr) (c/new-id tx2-petr) tx2-valid-time]]}]
                 log))))))

(defn- sync-submit-tx [node tx-ops]
  (let [submitted-tx (api/submit-tx node tx-ops)]
    (api/sync node (:crux.tx/tx-time submitted-tx) nil)
    submitted-tx))

(t/deftest test-can-apply-transaction-fn
  (let [exception (atom nil)
        latest-exception #(let [e @exception]
                            (reset! exception nil)
                            e)
        rethrow-latest-exception (fn []
                                   (throw (latest-exception)))]
    (with-redefs [tx/tx-fns-enabled? true
                  tx/log-tx-fn-error (fn [t & args]
                                       (reset! exception t))]
      (let [v1-ivan {:crux.db/id :ivan :name "Ivan" :age 40}
            v4-ivan (assoc v1-ivan :name "IVAN")
            update-attribute-fn {:crux.db/id :update-attribute-fn
                                 :crux.db.fn/body
                                 '(fn [db eid k f]
                                    [[:crux.tx/put (update (crux.api/entity db eid) k f)]])}]
        (sync-submit-tx *api* [[:crux.tx/put v1-ivan]
                               [:crux.tx/put update-attribute-fn]])
        (t/is (= v1-ivan (api/entity (api/db *api*) :ivan)))
        (t/is (= update-attribute-fn (api/entity (api/db *api*) :update-attribute-fn)))
        (t/is (nil? (latest-exception)))

        (let [v2-ivan (assoc v1-ivan :age 41)
              inc-ivans-age '{:crux.db/id :inc-ivans-age
                              :crux.db.fn/args [:ivan
                                                :age
                                                inc]}]
          (sync-submit-tx *api* [[:crux.tx/fn :update-attribute-fn inc-ivans-age]])
          (t/is (= v2-ivan (api/entity (api/db *api*) :ivan)))
          (t/is (= inc-ivans-age (api/entity (api/db *api*) :inc-ivans-age)))
          (t/is (nil? (latest-exception)))

          (t/testing "resulting documents are indexed"
            (t/is (= #{[41]} (api/q (api/db *api*)
                                    '[:find age :where [e :name "Ivan"] [e :age age]]))))

          (t/testing "exceptions"
            (t/testing "non existing tx fn"
              (sync-submit-tx *api* '[[:crux.tx/fn :non-existing-fn]])
              (t/is (= v2-ivan (api/entity (api/db *api*) :ivan)))
              (t/is (thrown?  NullPointerException (rethrow-latest-exception))))

            (t/testing "invalid arguments"
              (sync-submit-tx *api* '[[:crux.tx/fn :update-attribute-fn {:crux.db/id :inc-ivans-age
                                                                         :crux.db.fn/args [:ivan
                                                                                           :age
                                                                                           foo]}]])
              (t/is (= inc-ivans-age (api/entity (api/db *api*) :inc-ivans-age)))
              (t/is (thrown? clojure.lang.Compiler$CompilerException (rethrow-latest-exception))))

            (t/testing "invalid results"
              (sync-submit-tx *api* [[:crux.tx/put
                                      {:crux.db/id :invalid-fn
                                       :crux.db.fn/body
                                       '(fn [db]
                                          [[:crux.tx/foo]])}]])
              (sync-submit-tx *api* '[[:crux.tx/fn :invalid-fn]])
              (t/is (thrown-with-msg? clojure.lang.ExceptionInfo #"Spec assertion failed" (rethrow-latest-exception))))

            (t/testing "exception thrown"
              (sync-submit-tx *api* [[:crux.tx/put
                                      {:crux.db/id :exception-fn
                                       :crux.db.fn/body
                                       '(fn [db]
                                          (throw (RuntimeException. "foo")))}]])
              (sync-submit-tx *api* '[[:crux.tx/fn :exception-fn]])
              (t/is (thrown-with-msg? RuntimeException #"foo" (rethrow-latest-exception))))

            (t/testing "still working after errors"
              (let [v3-ivan (assoc v1-ivan :age 40)]
                (sync-submit-tx *api* '[[:crux.tx/fn :update-attribute-fn {:crux.db/id :dec-ivans-age
                                                                           :crux.db.fn/args [:ivan
                                                                                             :age
                                                                                             dec]}]])
                (t/is (nil? (latest-exception)))
                (t/is (= v3-ivan (api/entity (api/db *api*) :ivan)))))

            (t/testing "function ops can return other function ops"
              (let [returns-fn {:crux.db/id :returns-fn
                                :crux.db.fn/body
                                '(fn [db]
                                   '[[:crux.tx/fn :update-attribute-fn {:crux.db/id :upcase-ivans-name
                                                                        :crux.db.fn/args [:ivan
                                                                                          :name
                                                                                          clojure.string/upper-case]}]])}]
                (sync-submit-tx *api* [[:crux.tx/put returns-fn]])
                (sync-submit-tx *api* [[:crux.tx/fn :returns-fn]])
                (t/is (nil? (latest-exception)))
                (t/is (= v4-ivan (api/entity (api/db *api*) :ivan)))))

            (t/testing "repeated 'merge' operation behaves correctly"
              (let [v5-ivan (merge {:height 180}
                                   {:hair-style "short"}
                                   v4-ivan
                                   {:mass 60})
                    merge-fn {:crux.db/id :merge-fn
                                :crux.db.fn/body
                                '(fn [db eid m]
                                   [[:crux.tx/put (merge (crux.api/entity db eid) m)]])}
                    merge-1 '{:crux.db/id :merge-1
                                 :crux.db.fn/args [:ivan
                                                   {:mass 60
                                                    :hair-style "short"}]}
                    merge-2 '{:crux.db/id :merge-2
                                 :crux.db.fn/args [:ivan
                                                   {:height 180}]}]
                (sync-submit-tx *api* [[:crux.tx/put merge-fn]])
                (sync-submit-tx *api* [[:crux.tx/fn :merge-fn merge-1]])
                (sync-submit-tx *api* [[:crux.tx/fn :merge-fn merge-2]])
                (t/is (nil? (latest-exception)))
                (t/is (= v5-ivan (api/entity (api/db *api*) :ivan)))))

            (t/testing "can access current transaction as dynamic var"
              (sync-submit-tx *api*
                              [[:crux.tx/put
                                {:crux.db/id :tx-metadata-fn
                                 :crux.db.fn/body
                                 '(fn [db]
                                    [[:crux.tx/put {:crux.db/id :tx-metadata :crux.tx/current-tx crux.tx/*current-tx*}]])}]])
              (let [submitted-tx (sync-submit-tx *api* '[[:crux.tx/fn :tx-metadata-fn]])]
                (t/is (nil? (latest-exception)))
                (t/is (= {:crux.db/id :tx-metadata
                          :crux.tx/current-tx (assoc submitted-tx :crux.tx.event/tx-events [[:crux.tx/fn (str (c/new-id :tx-metadata-fn))]])}
                         (api/entity (api/db *api*) :tx-metadata)))))))))))

(t/deftest tx-log-evict-454 []
  (sync-submit-tx *api* [[:crux.tx/put {:crux.db/id :to-evict}]])
  (sync-submit-tx *api* [[:crux.tx/cas {:crux.db/id :to-evict} {:crux.db/id :to-evict :test "test"}]])
  (sync-submit-tx *api* [[:crux.tx/evict :to-evict]])

  (with-open [tx-log-context (api/new-tx-log-context *api*)]
    (t/is (= (->> (api/tx-log *api* tx-log-context nil true)
                  (map :crux.api/tx-ops))
             [[[:crux.tx/put
                #:crux.db{:id #crux/id "6abe906510aa2263737167c12c252245bdcf6fb0",
                          :evicted? true}]]
              [[:crux.tx/cas
                #:crux.db{:id #crux/id "6abe906510aa2263737167c12c252245bdcf6fb0",
                          :evicted? true}
                #:crux.db{:id #crux/id "6abe906510aa2263737167c12c252245bdcf6fb0",
                          :evicted? true}]]
              [[:crux.tx/evict
                #crux/id "6abe906510aa2263737167c12c252245bdcf6fb0"]]]))))

(t/deftest nil-transaction-fn-457
  (when tx/tx-fns-enabled?
    (let [merge-fn {:crux.db/id :my-fn
                    :crux.db.fn/body '(fn [db] nil)}]

      (sync-submit-tx *api* [[:crux.tx/put merge-fn]])
      (sync-submit-tx *api* [[:crux.tx/fn
                              :my-fn
                              {:crux.db/id (java.util.UUID/randomUUID)
                               :crux.db.fn/args []}]
                             [:crux.tx/put {:crux.db/id :foo
                                            :bar :baz}]])

      (t/is (= {:crux.db/id :foo, :bar :baz}
               (api/entity (api/db *api*) :foo))))))

(t/deftest map-ordering-362
  (t/testing "cas is independent of map ordering"
    (sync-submit-tx *api* [[:crux.tx/put {:crux.db/id :foo, :foo :bar}]])
    (sync-submit-tx *api* [[:crux.tx/cas {:foo :bar, :crux.db/id :foo} {:crux.db/id :foo, :foo :baz}]])

    (t/is (= {:crux.db/id :foo, :foo :baz}
             (api/entity (api/db *api*) :foo))))

  (t/testing "entities with map keys can be retrieved regardless of ordering"
    (let [doc {:crux.db/id {:foo 1, :bar 2}}]
      (sync-submit-tx *api* [[:crux.tx/put doc]])

      (t/is (= doc (api/entity (api/db *api*) {:foo 1, :bar 2})))
      (t/is (= doc (api/entity (api/db *api*) {:bar 2, :foo 1})))))

  (t/testing "entities with map values can be joined regardless of ordering"
    (let [doc {:crux.db/id {:foo 2, :bar 4}}]
      (sync-submit-tx *api* [[:crux.tx/put doc]
                             [:crux.tx/put {:crux.db/id :baz, :joins {:bar 4, :foo 2}}]
                             [:crux.tx/put {:crux.db/id :quux, :joins {:foo 2, :bar 4}}]])

      (t/is (= #{[{:foo 2, :bar 4} :baz]
                 [{:foo 2, :bar 4} :quux]}
               (api/q (api/db *api*) '{:find [parent child]
                                       :where [[parent :crux.db/id _]
                                               [child :joins parent]]}))))))
