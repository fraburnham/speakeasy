(ns speakeasy.server
  (:require [com.stuartsierra.component :as component]
            [ring.adapter.jetty :as jetty]
            [speakeasy.config :as config]))

(defrecord Server [app config server]
  component/Lifecycle

  (start [component]
    (assoc component :server (jetty/run-jetty app {:port (::config/port config)
                                                   :join? (not= :dev (::config/env config))})))

  (stop [{:keys [server] :as component}]
    (.stop server)
    (dissoc component :server)))

