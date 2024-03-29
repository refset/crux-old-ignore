(ns crux.docker
  (:require [crux.api :as crux]
            [crux.http-server :as srv]
            [clojure.java.io :as io]
            [integrant.core :as ig]
            [integrant.repl :as ir]
            [nrepl.server :as nrepl]
            [clojure.core.server :as prepl]
            [clojure.tools.cli :as cli])
  (:import java.io.Closeable))

(defmethod ig/init-key :crux/node [_ config]
  (crux/start-node config))

(defmethod ig/halt-key! :crux/node [_ ^Closeable node]
  (.close node))


(defmethod ig/init-key :crux/http-server [_ {:keys [crux/node crux/server-config]}]
  (let [cors-opts (:cors-access-control server-config)
        server-opts (cond-> server-config
                      (map? cors-opts) (assoc :cors-access-control (reduce into [] cors-opts)))]
    (srv/start-http-server node server-opts)))

(defmethod ig/halt-key! :crux/http-server [_ ^Closeable server]
  (.close server))

(def ig-config
  (let [{:keys [crux/node-opts crux/server-opts]} (read-string (slurp (io/file "/etc/crux.edn")))]
    {:crux/node node-opts
     :crux/http-server {:crux/node (ig/ref :crux/node)
                        :crux/server-config server-opts}}))

(def cli-opts
  [[nil "--nrepl" "Starts an nrepl for the container"]
   [nil "--prepl" "Starts an prepl for the container"]])

(defn parse-args [args]
  (let [{:keys [options]} (cli/parse-opts args cli-opts)]
    (cond
      (:nrepl options) (nrepl/start-server :bind "0.0.0.0", :port 7888)
      (:prepl options) (prepl/start-server {:accept 'clojure.core.server/io-prepl
                                            :address "0.0.0.0"
                                            :port 7888
                                            :name "Crux HTTP Prepl"}))))

(defn -main [& args]
  (parse-args args)
  (ir/set-prep! (constantly ig-config))
  (ir/go))
