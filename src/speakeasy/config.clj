(ns speakeasy.config
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [com.stuartsierra.component :as component]))

;; TODO: spec for config definition
(def config-map
  {::file {::env-var "SPEAKEASY_EDN_FILE"
           ::default "speakeasy.edn"}

   ::relying-party-id {::env-var "SPEAKEASY_RELYING_PARTY_ID"
                       ::config-location [:relying-party-id]
                       ::default "localhost"}

   ::port {::env-var "SPEAKEASY_PORT"
           ::env-parser #(Integer/parseInt %)
           ::config-location [:port]
           ::default 3000}

   ::env {::env-var "SPEAKEASY_ENV"
          ::env-parser keyword
          ::config-location [:env]
          ::default :dev}

   ::redis-url {::env-var "SPEAKEASY_REDIS_URL"
                ::config-location [:redis-url]
                ::default "redis://localhost:6379"}

   ::jwt-timeout-mins {::env-var "SPEAKEASY_JWT_TIMEOUT_MINS"
                       ::env-parser #(Long/parseLong %)
                       ::config-location [:jwt-timeout-mins]
                       ::default 60}})

(defn deep-contains? [m ks]
  (if (empty? ks)
    true
    (if (contains? m (first ks))
      (deep-contains? (m (first ks)) (rest ks))
      false)))

(defn resolve-value [env-map config-file-map {:keys [::env-var ::config-location ::default ::env-parser] :as location-info}]
  (let [env-parser (or env-parser identity)
        env-val (if (and (contains? location-info ::env-var)
                         (contains? env-map env-var))
                  (env-parser (get env-map env-var))
                  ::unset)
        config-val (if (and (contains? location-info ::config-location)
                            (deep-contains? config-file-map config-location))
                     (get-in config-file-map config-location)
                     ::unset)]
    (cond
      (not= ::unset env-val) env-val
      (not= ::unset config-val) config-val
      :else (or default
                (throw (ex-info "Unable to resolve config value" {::location-info location-info}))))))

(defn get-config-file [env-map config-map]
  (let [config-file-path (resolve-value env-map {} (::file config-map))]
    (if (.exists (io/file config-file-path))
        (edn/read-string (slurp config-file-path))
        {})))

(defn resolve-values [config-map]
  (let [env-map (System/getenv)
        config-file-map (get-config-file env-map config-map)]
    (->> (map (fn [[k v]]
                [k (resolve-value env-map config-file-map v)])
              config-map)
         (into {}))))

(defrecord Config [config-map]
  component/Lifecycle
  (start [_] (resolve-values config-map))
  (stop [_] {}))
