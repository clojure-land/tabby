(ns tabby.net
  (:require [aleph.tcp :as tcp]
            [clojure.tools.logging :refer [warn info]]
            [gloss.core :as gloss]
            [gloss.io :as io]
            [taoensso.nippy :as nippy]
            [manifold.deferred :as d]
            [tabby.leader :as l]
            [manifold.stream :as s]
            [tabby.client-state :as cs]
            [tabby.server :as server]
            [tabby.utils :as utils]))

(def ^:private protocol
  "The tabby protocol definition."
  (gloss/compile-frame
   (gloss/finite-block :uint16)
   (comp byte-streams/to-byte-buffer nippy/freeze)
   (comp nippy/thaw byte-streams/to-byte-array)))

(defn- stream-open? [x]
  (and x (not (s/closed? x))))

(def ^:private stream-closed? (complement stream-open?))

(defn- wrap-duplex-stream
  "wrap the stream in the protocol"
  [protocol s]
  (let [out (s/stream)]
    (s/connect
     (s/map #(io/encode protocol %) out)
     s)

    (s/splice
     out
     (io/decode-stream s protocol))))

(defn- current-time
  "current time in ms"
  []
  (long (/ (System/nanoTime) 1000000)))

(defn- delta-t
  "Returns the time difference between t and now.  Will be >= 0"
  [t]
  (max (- (current-time) t) 0))

(defn client
  "Tcp connection to the host:port combo provided.
   Returns a deferred."
  [host port]
  (assert host "host is nil")
  (assert port "port is nil")
  (d/chain (tcp/client {:host host, :port port})
           #(wrap-duplex-stream protocol %)))

(defn- connect-peer-socket!
  "Connects the peer messages to the :rx-stream"
  [state socket handshake]
  (info (:id @state) " accepting peer connection from: " (:src handshake))
  (swap! state
         (fn [state]
           (if (stream-closed? (get-in state [:peers (:src handshake) :socket]))
             (assoc-in state [:peers (:src handshake) :socket] socket)
             state)))

  (when (= :leader (:type @state))
    (swap! state l/broadcast-heartbeat))

  (s/connect socket (:rx-stream @state) {:downstream? false
                                         :upstream? true}))

(defn- connect-table-tennis-socket
  ":ping -> :pong"
  [state socket handshake]
  (d/loop []
    (-> (s/take! socket ::none)
        (d/chain
         (fn [msg]
           (when-not (= ::none msg)
             (when (= :ping msg)
               (s/put! socket :pong))
             (d/recur)))))))

(defn- connect-client-socket!
  "Dispatches socket messages to the states :rx-stream"
  [state socket handshake]
  (let [client-index (utils/gen-uuid)]
    (info (:id @state) "client connected: " client-index)
    (swap! state update :clients cs/create-client client-index socket)
    (s/connect (s/map #(assoc % :client-id client-index) socket)
               (:rx-stream @state)
               {:downstream? false})))

(defn- connection-handler
  "Returns a function that will handle the handshake for incoming connections."
  [state]
  (fn [s info]
    (d/let-flow
     [handshake (s/take! s)]
     (-> ((condp = (:type handshake)
            :peering-handshake connect-peer-socket!
            :table-tennis connect-table-tennis-socket
            :client-handshake connect-client-socket!
            (fn [&_]
              (warn "Invalid handshake: " (:type handshake))
              (s/close! s)))
          state s handshake)
         (d/catch (fn [ex]
                    (warn ex "Caught exception!")
                    (s/close! s)))))))

(defn connect-to-peer
  "deferred connect version, returns just the socket in a defered"
  [state [id peer]]
  (info (:id state) " connecting to peer: " id)
  (if (stream-open? (:socket peer))
    [id peer]
    (-> (d/let-flow [socket (client (:hostname peer) (:port peer))
                     _ (s/put! socket {:src (:id state) :type :peering-handshake})]
          (s/connect socket (:rx-stream state) {:downstream? false})
          [id (assoc peer :socket socket)])
        (d/catch (fn [ex]
                   (warn (:id state) "caught exception in connecting to: " id))))))

(defn- reconnect-to-peer! [state peer-id]
  (when-not (get-in @state [:peers peer-id :connect-pending])
    (swap! state assoc-in [:peers peer-id :connect-pending] true)
    (warn (:id @state) " reconnecting to " peer-id)
    (d/future
      (-> (d/chain
           (connect-to-peer @state [peer-id (get-in @state [:peers peer-id])])
           (fn [[peer-id peer-value]]
             (swap! state (fn [s]
                            (-> (assoc-in s [:peers peer-id] peer-value)
                                (dissoc [:peers peer-id :connect-pending]))))))
          (d/catch (fn [ex]
                     (swap! state update-in [:peers peer-id] dissoc :connect-pending)
                     (warn ex "[" (:id @state) "] caught exception in reconnect-to-peer")))))))

(defn- send-peer-packet
  "sends a packet to the appropriate peer socket, will reconnect"
  [state p]
  ;;; TODO: refactor
  (let [peer-id (:dst p)
        socket (get-in state [:peers peer-id :socket])]
    (if (and socket (not (s/closed? socket)))
      (s/put! socket p)
      false)))

(defmacro dissoc-in [s ks k]
 `(update-in ~s [~@ks] dissoc ~k))

(defn- send-client-packet
  "send a packect to the client, won't reconnect"
  [state p]
  (let [client (:client-dst p)
        socket (get-in state [:clients client :socket])]
    (if (and socket (s/closed? socket))
      (dissoc-in state [:clients client] :socket)
      (do
        (s/put! socket (dissoc p :client-dst))
        state))))

(defn- send-pkt
  "dispatch method for sending packets"
  [state p]
  (cond
    (:dst p) (send-peer-packet state p)
    (:client-dst p) (send-client-packet state p)
    :else (do
            (warn (:id state) ": invalid pkt: " p)
            state)))

(defn- transmit!
  "sends all the currently queued packets"
  [state]
  (let [pkts (:tx-queue @state)]
    (swap! state assoc :tx-queue '())
    (doseq [p pkts]
      (when-not (send-pkt @state p))
          ;; (warn (:id @state) " trying to reconnect to peer: " (:dst (first pkts)))
      ;;(reconnect-to-peer state (:dst (first pkts)))
          )))

(defn- handle-rx-pkt!
  "appends the pkt and runs update"
  [state dt pkt]
  (swap! state
         (fn [s]
           (-> (update s :rx-queue conj pkt)
               (server/update-state dt))))
  true)

(defn- handle-timeout!
  "timeout of dt seconds, just run the update"
  [state dt]
  (swap! state server/update-state dt)
  true)

(def ^:private default-timeout 10)

(defn event-loop
  "Runs the event loop for a server instance.  Returns a deferred."
  [state timeout]
  (d/loop [t (current-time)]
    (when-not (empty? (:tx-queue @state))
      (transmit! state))

    (if-not (stream-open? (:rx-stream @state))
      :exit
      (-> (d/chain (s/try-take! (:rx-stream @state) ::none
                                (or timeout default-timeout) ::timeout)
                   (fn [msg]
                     (when (condp = msg
                             ::none false
                             ::timeout (handle-timeout! state (delta-t t))
                             (handle-rx-pkt! state (delta-t t) msg))
                       (d/recur (current-time)))))
          (d/catch (fn [ex]
                     (warn ex (:id @state) ": caught exception in event loop")
                     (s/close! (:rx-stream @state))))))))


(defn start-server!
  "Starts the server listening."
  [server port]
  (swap! server assoc :election-timeout (utils/random-election-timeout @server))
  (tcp/start-server
   (fn [s info]
     ((connection-handler server) (wrap-duplex-stream protocol s) info))
   {:port port}))

(def ^:private rx-buffer-size 5) ; no data for this random choice

(defn create-server
  "Creates a network instance of the server."
  [server port]
  (let [s (if (instance? clojure.lang.Atom server)
            server
            (atom server))]
    (swap! s assoc :rx-stream (s/stream (or (:rx-buffer-size @s) rx-buffer-size)))
    (swap! s assoc :server-socket (start-server! s port))
    s))

(defn stop-server
  "Stops the server listening socket and rx stream"
  [server]
  (assert (not (instance? clojure.lang.Atom server)))

  (-> server
      (update :server-socket (fn [^java.io.Closeable s]
                               (when s
                                 (.close s))
                               nil))
      (update :peers utils/mapf (fn [x]
                                  (when-let [socket (:socket x)]
                                    (s/close! socket))
                                  (dissoc x :socket)))
      (update :rx-stream (fn [x]
                           (when x
                             (s/close! x))
                           nil))))
