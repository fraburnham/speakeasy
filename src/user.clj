(ns user
  (:require [com.stuartsierra.component :as component]
            [speakeasy.config :as config]
            [speakeasy.core :as core]
            [speakeasy.webauthn.authentication :as auth]
            [speakeasy.webauthn.registration :as reg]
            [taoensso.carmine :as car]
            [speakeasy.redis :as redis]))

(defn start []
  ;; TODO: don't start if there is a system already running!
  (reset! core/system (component/start (core/generate-system))))

(defn stop []
  (swap! core/system component/stop))

(defn reload []
  (stop)
  (start))

#_(defonce test-redis (redis/map->RedisStore {:uri "redis://localhost:6379" :pool (car/connection-pool {})}))
