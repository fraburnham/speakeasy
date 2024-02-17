(ns speakeasy.redis
  (:require [com.stuartsierra.component :as component]
            [speakeasy.config :as config]
            [taoensso.carmine :as car])
  (:import [com.yubico.webauthn CredentialRepository RegisteredCredential]
           [com.yubico.webauthn.data AuthenticatorTransport ByteArray PublicKeyCredentialDescriptor PublicKeyCredentialType]
           [java.util Base64 Optional]))

;; TODO: protocols should live alone
(defprotocol WebAuthnStore
  (store-user-handle [this username user-handle])
  
  (store-credential [this user-handle public-key-credential-descriptor])

  (get-credential [this cred-id])

  (get-user-credential [this user-handle cred-id])

  (update-credential [this auth-result]))

(defprotocol Base64Encode
  (->b64 [this] "Encode this as base64"))

(extend-protocol Base64Encode
  ;; https://ask.clojure.org/index.php/1963/improve-support-for-extending-protocols-primitive-arrays
  ;; https://clojure.atlassian.net/browse/CLJ-1381
  ;; Bytes extend must come first
  (Class/forName "[B")
  (->b64 [b]
    (String. (.encode (Base64/getUrlEncoder) b)))
  
  String
  (->b64 [s]
    (->b64 (.getBytes s))))

(defn set-credential-value [key-format]
  (fn [key value]
    (car/set (format key-format key) value)))

;; TODO: credential parser that knows which functions to run to encode/decode each key from redis
;; keeping them next to each other should make it easy to keep them up to date

(defrecord RedisStore [config redis-config]
  component/Lifecycle
  WebAuthnStore
  CredentialRepository

  ;; -----
  ;; component/Lifecycle
  ;; -----

  (start [component]
    (-> (assoc-in component [:redis-config :pool] (car/connection-pool {}))
        (assoc-in [:redis-config :uri] (::config/redis-url config))))

  (stop [component]
    (.close (:pool (:redis-config component)))
    (assoc-in component [:redis-config :pool] nil))

  ;; -----
  ;; WebAuthnStore
  ;; -----

  (store-user-handle [_ username user-handle]
    (car/wcar
     redis-config
     (car/set (str "username/" user-handle) username)
     (car/set (str "user-handle/" username) user-handle)))

  (store-credential [_ user-handle registration-result]
    (let [key-desc (.getKeyId registration-result)
          cred-id (->b64 (.getBytes (.getId key-desc)))
          redis-key-fmt (str "credential/%s/" cred-id)
          set-val (set-credential-value redis-key-fmt)]
      (car/wcar
       redis-config

       (set-val :cose (.getBytes (.getPublicKeyCose registration-result)))
       (set-val :type (.getId (.getType key-desc)))
       (set-val :transport-ids (map #(.getId %) (.orElse (.getTransports key-desc) nil)))
       (set-val :sig-count (.getSignatureCount registration-result))
       (set-val :discoverable? (.orElse (.isDiscoverable registration-result) nil))
       (set-val :user-verified? (.isUserVerified registration-result))

       (car/sadd (str "credentials/" user-handle) cred-id))))

  (get-credential [_ cred-id]
    (let [redis-key-fmt (str "credential/%s/" cred-id)
          [cose
           type
           transport-ids
           sig-count
           discoverable?
           user-verified?]
          (car/wcar
           redis-config
           (car/get (format redis-key-fmt :cose))
           (car/get (format redis-key-fmt :type))
           (car/get (format redis-key-fmt :transport-ids))
           (car/get (format redis-key-fmt :sig-count))
           (car/get (format redis-key-fmt :discoverable?))
           (car/get (format redis-key-fmt :user-verified?)))]

      (if (and cose type)
        {::cose cose
         ::type type
         ::transport-ids transport-ids
         ::sig-count (Integer/parseInt sig-count)
         ::discoverable? discoverable?
         ::user-verified? user-verified?
         ::cred-id cred-id}
        nil)))

  (get-user-credential [this user-handle cred-id]
    (let [user-creds-list (car/wcar
                           redis-config
                           (car/smembers (str "credentials/" user-handle)))]
      (if (contains? (into #{} user-creds-list) cred-id)
        (get-credential this cred-id)
        nil)))

  (update-credential [_ auth-result]
    ;; proabbly have to read and write the object, eh? since it's all on one key
    (let [cred (.getCredential auth-result)
          cred-id (->b64 (.getBytes (.getCredentialId cred)))
          redis-key-fmt (str "credential/%s/" cred-id)]
      ;; TODO: user-handle is available on the cred, verify the ownership before updating
      (car/wcar
       redis-config
       (car/set (format redis-key-fmt :sig-count)
                (.getSignatureCount cred)))))

  ;; -----
  ;; CredentialRepository
  ;; -----

  (getCredentialIdsForUsername [this username]
    (let [user-handle (car/wcar redis-config (car/get (str "user-handle/" username)))
          cred-ids (car/wcar redis-config
                             (car/smembers (str "credentials/" user-handle)))]
      (->> (map #(get-user-credential this user-handle %) cred-ids)
           (map
            (fn [cred-id cred-map]
              (let [transports (::transports cred-map)]
                (.. PublicKeyCredentialDescriptor
                    builder
                    (id (ByteArray. (.decode (Base64/getUrlDecoder) cred-id)))
                    (transports (if transports
                                  (->> (map #(AuthenticatorTransport/valueOf %) transports)
                                       (into #{})
                                       (Optional/of))
                                  (Optional/empty)))
                    ;; (.getId (PublicKeyCredentialType/valueOf "PUBLIC_KEY")) => "public-key"
                    ;; (PublicKeyCredentialType/valueOf "public-key") => IllegalArgumentException No enum constant
                    ;; Why not return from .getId the thing that will work to create a new object?
                    (type (PublicKeyCredentialType/valueOf "PUBLIC_KEY"))
                    build)))
            cred-ids)
           (into #{}))))

  (getUserHandleForUsername [_ username]
    (let [user-handle (car/wcar
                       redis-config
                       (car/get (str "user-handle/" username)))]
      (if user-handle
        (Optional/of (ByteArray. (.getBytes user-handle)))
        (Optional/empty))))

  (getUsernameForUserHandle [_ user-handle]
    (let [user-handle (String. (.getBytes user-handle))
          username (car/wcar
                    redis-config
                    (car/get (str "username/" user-handle)))]
      (if username
        (Optional/of username)
        (Optional/empty))))

  (lookup [this raw-cred-id raw-user-handle]
    (let [cred-id (->b64 (.getBytes raw-cred-id)) ;; TODO: is this re-encoding still needed? was it ever needed? the raw-cred-id is already b64 it seems
          user-handle (String. (.getBytes raw-user-handle))
          cred-map (get-user-credential this user-handle cred-id)]
      (if cred-map
        (Optional/of
         (.. RegisteredCredential
             builder
             (credentialId raw-cred-id)
             (userHandle raw-user-handle)
             (publicKeyCose (ByteArray. (::cose cred-map)))
             (signatureCount (::sig-count cred-map))
             build))
        (Optional/empty))))

  (lookupAll [this raw-cred-id]
    (let [cred-id (->b64 (.getBytes raw-cred-id))
          cred-map (get-credential this cred-id)]
      (if cred-map
        #{(.. RegisteredCredential ; TODO: pull this builder out as a fn?
              builder
              (credentialId raw-cred-id)
              (userHandle (ByteArray. (.getBytes "Why disclose user handle here?")))
              (publicKeyCose (ByteArray. (::cose cred-map)))
              (signatureCount (::sig-count cred-map))
              build)}
        #{}))))

