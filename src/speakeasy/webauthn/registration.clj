(ns speakeasy.webauthn.registration
  (:require [clojure.data.json :as json]
            [speakeasy.config :as config]
            [speakeasy.jwt :as jwt]
            [speakeasy.middleware.system :as system]
            [speakeasy.redis :as redis]
            [speakeasy.webauthn.data :as data])
  (:import [com.yubico.webauthn.data PublicKeyCredential]
           [com.yubico.webauthn.exception RegistrationFailedException]
           [java.security SecureRandom MessageDigest]
           [java.util Base64]))

(defonce registration-options-store (atom {}))

(defn sha256 [bytes]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") bytes)]
    (apply str (map (partial format "%02x") digest))))

(defn random-handle []
  (let [b (byte-array 128)]
    (.nextBytes (SecureRandom.) b)
    (sha256 b)))

;; TODO: https://www.w3.org/TR/2021/REC-webauthn-2-20210408/#sctn-username-enumeration
;; for registration ceremonies (that don't need to use email to id the user) reject email shaped inputs completely
(defn start [{{:keys [display-name username]} :body-params :as request}]
  (try
    (let [redis (::redis/redis (::system/system request))
          user-handle (random-handle)
          username (or username user-handle)
          relying-party (data/relying-party redis (get-in request [::system/system ::config/config ::config/hostname]))
          user-id (data/user-identity username display-name (.getBytes user-handle))
          registration-options (->> (data/start-registration-options user-id)
                                    (.startRegistration relying-party))]

      (swap! registration-options-store
             assoc
             user-handle
             {:opts registration-options :username username})

      {:status 200 :body (->> registration-options
                              (.toCredentialsCreateJson)
                              json/read-str)})
    (catch Exception e
      ;; TODO: logging middleware
      (println e)
      {:status 500})))

(defn ceremony-result [redis hostname reg-options public-key-data]
  (let [public-key-cred (PublicKeyCredential/parseRegistrationResponseJson (json/write-str public-key-data))]
    (.finishRegistration
     (data/relying-party redis hostname)
     (data/finish-registration-options reg-options public-key-cred))))

(defn complete [ceremony-result]
  (fn [{{:keys [user-handle public-key-data]} :body-params :as request}]
    (let [user-handle (String. (.decode (Base64/getDecoder) (.getBytes user-handle)))
          redis (::redis/redis (::system/system request))
          reg-options (get-in @registration-options-store [user-handle :opts])
          username (get-in @registration-options-store [user-handle :username])]
      (swap! registration-options-store dissoc user-handle)
      (try
        (let [registration-result (ceremony-result redis (get-in request [::system/system ::config/config ::config/hostname]) reg-options public-key-data)]

          (redis/store-user-handle redis username user-handle)
          (redis/store-credential redis user-handle registration-result)
          {:status 201
           :cookies {"speakeasy-token" {:value (jwt/token (get-in request [::system/system ::config/config ::config/jwt-timeout-mins]))
                                        :secure true
                                        :http-only true
                                        :path "/"}}})

        (catch RegistrationFailedException e
          ;; TODO: logging
          (println e)
          {:status 401})
        (catch Exception e
          (println e)
          {:status 500})))))
