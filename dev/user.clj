(ns user
  (:require [tabby.server :as server]
            [tabby.client :as client]
            [tabby.utils :as utils]
            [tabby.cluster :as cluster]
            [clojure.tools.logging :refer :all]
            [tabby.local-net :as local-net]
            [clojure.tools.namespace.repl :refer [refresh]]))

(defn cluster-maker
  "Makes a cluster"
  []
  (local-net/create-network-cluster 10 8090))

(def cluster
  (cluster-maker))

(defn- local-client []
  (client/make-http-client [{:host "127.0.0.1" :port 8090 :http-port 9090}
                            {:host "127.0.0.1" :port 8091 :http-port 9091}
                            {:host "127.0.0.1" :port 8092 :http-port 9092}]))

(defn- remote-client []
  (assoc (client/make-network-client [{:host "192.168.64.23" :port 31620}
                                      {:host "192.168.64.23" :port 32227}
                                      {:host "192.168.64.23" :port 30865}])
         :host-override "192.168.64.23"))

(def klient (local-client))

(defn unatom [x]
  (if (instance? clojure.lang.Atom x)
    @x
    x))

(defmacro setc [& body]
  `(alter-var-root #'cluster
                   (fn ~@body)))

(defn init []
  (setc [c] (cluster/init-cluster c 3)))

(defn step [dt]
  (setc [c] (cluster/step-cluster c dt)))

(defn servers []
  (:servers cluster))

(defn start []
  (info "----------------------------------------------------")
  (setc [c] (cluster/start-cluster c)))

(defn stop []
  (setc [c] (cluster/stop-cluster c)))

(defn go
  []
  (init)
  (start)
  :ready)

(defn to-name [x]
  (if (instance? String x)
    x
    (str x ".localnet:" x)))

(defn kill
  [& args]
  (alter-var-root #'cluster
                  (fn [c]
                    (reduce (fn [c i]
                              (cluster/kill-server c (to-name i)))
                            c args))))

(defn rez
  "not done yet"
  [& args]
  (alter-var-root #'cluster
                  (fn [c]
                    (reduce #(cluster/rez-server %1 (to-name %2))
                            c args))))
(defn reset []
  (try
    (stop)
    (alter-var-root #'cluster (fn [s] (cluster-maker)))
    (alter-var-root #'klient (fn [s] (local-client)))
    (refresh :after 'user/go)
    (catch Exception e
      (warn e "caught exception in reset!!!"))))

(defn set-value [key value]
  (client/set-value! klient key value))

(defn get-value [key]
  (client/get-value! klient key))

(defn compare-and-swap [key new old]
  (client/compare-and-swap! klient key new old))

(defn server-at [key]
  (unatom (get (:servers cluster) (to-name key))))

(defn types []
  (map (fn [x]
         (-> (unatom x)
             (select-keys [:type :id :stopped :db :current-term :commit-index]))) (vals (:servers cluster))))

(defn logs []
  (map (fn [x]
         (-> (unatom x)
             (select-keys [:log :id :last-applied]))) (vals (:servers cluster))))

(defn find-leader []
  (reduce-kv (fn [_ k v]
               (if (= :leader (:type (unatom v)))
                 (reduced [k v]) _)) nil (:servers cluster)))

(defn leader-clients []
  (:clients (unatom (second (find-leader)))))

(defn followers []
  (map first (filter (fn [[k v]]
                       (not= :leader (:type (unatom v)))) (:servers cluster))))

(defn kill-random-follower []
  (let [id (first (shuffle (followers)))]
    (kill id)
    id))

(defn kill-and-rez-anyone []
  (let [id (first (first (shuffle (seq (:servers cluster)))))]
    (kill id)
    (Thread/sleep 300)
    (rez id)
    nil))

(defn kill-leader []
  (kill (first (find-leader))))

(defn kill-and-rez-leader []
  (let [[id _] (find-leader)]
    (println "Leader is: " id)
    (warn "+====== KILLING LEADER: " id " =======+")
    (kill id)
    (Thread/sleep 600)
    (warn "+====== REZZING OLD LEADER: " id " ========+")
    (rez id)
    nil))

(defn kill-and-rez-follower []
  (let [id (kill-random-follower)]
    (Thread/sleep 300)
    (rez id)
    nil))

(defn while-not-leader []
  (future (loop [l (find-leader)]
            (Thread/yield)
            (if l
              l
              (recur (find-leader))))))

(defn testy []
  @(while-not-leader)
  (println "leader: " (first (find-leader)))
  (assert (= :ok (set-value :a "a")))
  (kill-random-follower)
  (assert (= :ok (set-value :b "b"))))

(defn types []
  (map (fn [[x y]] [x (select-keys (unatom y) [:type :current-term :leader-id])]) (seq (:servers cluster))) )
