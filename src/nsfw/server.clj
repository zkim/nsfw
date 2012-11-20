(ns nsfw.server
  "Easily manage jetty instances.

   Example:
   (def server (atom nil))
   (start-server server (fn [req] {:body \"hi\"}) {:port 4321})

   ;; Change to port 4000 and join. Will shut down server bound to
   ;; 4321.
   (start-server server (fn [req] {:body \"lo\"}) {:port 4000 :join? true})"
  (:require [ring.adapter.jetty :as jetty]))


;; # Server

(def server-defaults {:port 8080
                      :join? false
                      :max-threads 100
                      :min-threads 10})

(defn start-server
  "Start a jetty server. Opts include:
   {:port        8080
    :join?       false  ; Join server thread?
    :max-threads 100    ; Request thredpool cap.
    :min-threads 10}   
"
  [server-atom entry-point & [opts]]
  (let [opts (merge server-defaults opts)]
    (when @server-atom
      (.stop @server-atom))
    (swap! server-atom
           (fn [& _] (jetty/run-jetty entry-point opts)))
    (doto (.getThreadPool @server-atom)
      (.setMaxThreads (:max-threads opts))
      (.setMinThreads (:min-threads opts)))
    server-atom))

(defn stop-server [server-atom]
  (when @server-atom
    (.stop @server-atom)))

(def restart-server start-server)

(def SERVERS (atom {}))

(defn restart [& opts]
  (let [opts (apply hash-map opts)
        name (get opts :name)
        entry-point (get opts :entry (fn [r] {:body "Whoops, you didn't pass in an :entry when you started the server."}))
        server (get @SERVERS
                    name
                    (atom nil))]
    (swap! SERVERS assoc name (start-server server entry-point opts))))

(defn stop [& name]
  (stop-server (get @SERVERS name)))

(defn stop-all []
  (doseq [s (vals @SERVERS)]
    (stop-server s)))
