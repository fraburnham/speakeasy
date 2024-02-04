(ns speakeasy.server
  (:require [com.stuartsierra.component :as component]
            [ring.adapter.jetty :as jetty]))

(defrecord Server [port join? app server]
  component/Lifecycle

  (start [component]
    (assoc component :server (jetty/run-jetty app {:port port :join? join?})))

  (stop [{:keys [server] :as component}]
    (.stop server)
    (dissoc component :server)))

(defn new-component [port join? app]
  (->Server port join? app nil))
