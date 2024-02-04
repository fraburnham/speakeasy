(ns user
  (:require [com.stuartsierra.component :as component]
            [speakeasy.core :as core]
            [speakeasy.webauthn.authentication :as auth]
            [speakeasy.webauthn.registration :as reg]
            [taoensso.carmine :as car]
            [speakeasy.redis :as redis]))

(defn start []
  ;; TODO: don't start if there is a system already running!
  (reset! core/system (component/start (core/generate-system false))))

(defn stop []
  (swap! core/system component/stop))

(defn reload []
  (stop)
  (start))

(defonce test-redis (redis/->RedisStore {:uri "redis://localhost:6379" :pool (car/connection-pool {})}))
