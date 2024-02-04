(ns speakeasy.webauthn.authentication
  (:require [clojure.data.json :as json]
            [speakeasy.jwt :as jwt]
            [speakeasy.middleware.system :as system]
            [speakeasy.redis :as redis]
            [speakeasy.webauthn.relying-party :as rp])
  (:import [com.yubico.webauthn.data PublicKeyCredential]
           [com.yubico.webauthn.exception AssertionFailedException]
           [java.security SecureRandom MessageDigest]))

(defonce authentication-requests-store (atom {}))

(defn sha256 [bytes]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") bytes)]
    (apply str (map (partial format "%02x") digest))))

(defn random-handle []
  (let [b (byte-array 128)]
    (.nextBytes (SecureRandom.) b)
    (sha256 b)))

(defn start [{{:keys [username]} :body-params :as request}]
  (try
    (let [redis (::redis/redis (::system/system request))
          relying-party (rp/relying-party redis)
          user-handle (.orElse (.getUserHandleForUsername redis username)
                               (random-handle))
          auth-request (->> (rp/start-assertion-options username)
                            (.startAssertion relying-party))]
      (swap! authentication-requests-store assoc user-handle auth-request)
      {:status 200 :body {:user-handle user-handle
                          :opts (->> auth-request
                                     (.toCredentialsGetJson)
                                     (json/read-str))}})
    (catch Exception e
      ;; TODO: Surely there is a middleware for this error logging...
      (println e)
      {:status 500})))

(defn ceremony-result [redis public-key-data auth-request]
  (let [relying-party (rp/relying-party redis)
        public-key-cred (PublicKeyCredential/parseAssertionResponseJson (json/write-str public-key-data))
        assertion-options (rp/finish-assertion-options auth-request public-key-cred)
        result (.finishAssertion relying-party assertion-options)]
    {:success? (.isSuccess result)
     :result result}))

(defn complete [auth-ceremony-result]
  (fn [{{:keys [user-handle public-key-data]} :body-params :as request}]
    (try
      (let [redis (::redis/redis (::system/system request))
            auth-request (get @authentication-requests-store user-handle)
            _ (swap! authentication-requests-store dissoc user-handle)
            {:keys [success? result]} (auth-ceremony-result redis public-key-data auth-request)]
        (if success?
          (do
            (redis/update-credential redis result)
            {:status 200
             :cookies {"speakeasy-token" {:value (jwt/token 60) :secure true :http-only true :path "/"}}})
          {:status 401}))
      (catch AssertionFailedException e
        (println e)
        {:status 401})
      (catch Exception e
        (println e)
        {:status 500}))))

(defn check [request]
  (let [token (get-in request [:cookies "speakeasy-token" :value])]
    (if (and token (jwt/valid? token))
      {:status 200}
      {:status 401})))
