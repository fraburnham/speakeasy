(ns speakeasy.core
  (:gen-class)
  (:require [com.stuartsierra.component :as component]
            [muuntaja.core]
            [speakeasy.middleware.system :as system]
            [speakeasy.redis :as redis]
            [speakeasy.server :as server]
            [speakeasy.webauthn.authentication :as authentication]
            [speakeasy.webauthn.html :as html]
            [speakeasy.webauthn.registration :as registration]
            [reitit.coercion.spec]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as coercion]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [ring.middleware.cookies :as cookies]))

(defonce system (atom {}))

(defn app []
  ;; TODO: don't let stacktraces get to the client
  (ring/ring-handler
   (ring/router
    ["/speakeasy"
     ["" (fn [_] {:status 200
                  :body html/page})]

     [["/resources/*" (ring/create-resource-handler)]

      ["/check" authentication/check]

      ["/authenticate" {:tags #{:register}}
       ["/start" {:post {:handler authentication/start}}]

       ["/complete" {:post {:parameters {:body {:user-handle string?}}
                            :handler (authentication/complete authentication/ceremony-result)}}]]

      ["/register" {:tags #{:register}}
       ["/start" {:post {:handler registration/start}}]

       ["/complete" {:post {:parameters {:body {:user-handle string?}}
                            :handler (registration/complete registration/ceremony-result)}}]]]]

    {:data {:coercion reitit.coercion.spec/coercion
            :muuntaja muuntaja.core/instance
            :middleware [(system/attach system)
                         muuntaja/format-middleware
                         parameters/parameters-middleware
                         coercion/coerce-request-middleware
                         coercion/coerce-response-middleware
                         cookies/wrap-cookies]}})

   (ring/create-default-handler)))

(defn generate-system [join-server?]
  (component/system-map
   ::server/server (server/new-component 3000 join-server? (app))
   ;; TODO: I think to start this prod style the server will have to depend on redis (since the server will cause us to join)
   ::redis/redis (redis/new-component "redis://localhost:6379")))

(defn -main [& _]
  (reset! system (component/start (generate-system true))))
