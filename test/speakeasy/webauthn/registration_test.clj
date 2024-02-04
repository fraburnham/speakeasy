(ns speakeasy.webauthn.registration-test
  (:require [clojure.test :refer [deftest testing is are]]
            [speakeasy.middleware.system :as system]
            [speakeasy.redis :as redis]
            [speakeasy.webauthn.registration :as sut]
            [speakeasy.webauthn.relying-party :as rp])
  (:import [com.yubico.webauthn CredentialRepository]
           [com.yubico.webauthn.data ByteArray COSEAlgorithmIdentifier PublicKeyCredentialCreationOptions PublicKeyCredentialParameters]
           [com.yubico.webauthn.exception RegistrationFailedException]
           [java.util Base64 Optional]))

(defrecord RedisStore [store]
  redis/WebAuthnStore
  CredentialRepository

  (store-user-handle [_ _ _])
  (store-credential [_ user-handle result]
    (swap! store assoc user-handle result))
  (get-credential [_ _])
  (get-user-credential [_ _ _])
  (update-credential [_ _])

  (getCredentialIdsForUsername [_ _]
    (println "creds for username")
    #{})

  (getUserHandleForUsername [_ username]
    (if username
      (Optional/of (ByteArray. (.getBytes username)))
      (Optional/empty)))

  (getUsernameForUserHandle [_ handle]
    (if handle
      (Optional/of (String. (.getBytes handle)))
      (Optional/empty)))

  (lookup [_ _ _]
    (println "lookup")
    (Optional/empty))

  (lookupAll [_ _]
    (println "lookupAll")
    #{}))

(defn call-api
  ([api-fn body-params]
   (call-api api-fn body-params (->RedisStore nil)))

  ([api-fn body-params redis-store]
   (api-fn {::system/system {::redis/redis redis-store}
            :body-params body-params})))

(deftest sha256
  (testing "correctly generates sha256 hex output"
    (are [input expected] (= expected (sut/sha256 (.getBytes input)))
      "asdf" "f0e4c2f76c58916ec258f246851bea091d14d4247a2fc3e18694461b1816e13b"
      "this is only a test" "1661186b8e38e79f434e4549a2d53f84716cfff7c45d334bbc67c9d41d1e3be6"
      "more tests just in case" "6d863df04ca3e757b9e5f2eee92915a2c2e77c49d6d3effcbe7ae543276a880a")))

(deftest start
  (testing "No params"
    (reset! sut/registration-options-store {})
    (let [response (call-api sut/start {})]
      (testing "returns a registration options that a client can use for the ceremony"
        (is (= (:status response) 200))
        (let [rp (get-in response [:body "publicKey" "rp"])
              user (get-in response [:body "publicKey" "user"])
              auth-selection (get-in response [:body "publicKey" "authenticatorSelection"])]
          (is (= (user "name")
                 (user "displayName")
                 (String. (.decode (Base64/getDecoder) (.getBytes (user "id"))))))
          (is (= (auth-selection "residentKey") "preferred"))
          (is (= (auth-selection "userVerification") "required"))
          (is (= (get-in response [:body "publicKey" "attestation"]) "none"))
          (is (= (rp "name") "localhost"))
          (is (= (rp "id") "localhost"))))

      (testing "stores options needed to complete the ceremony"
        (is (= (count (keys @sut/registration-options-store)) 1)))))

  (testing "Username and display name passed"
    (let [response (call-api sut/start {:username "nightman" :display-name "DayMan"})]
      (testing "returns a registration options that a client can use for the ceremony"
        (is (= (:status response) 200))
        (let [rp (get-in response [:body "publicKey" "rp"])
              auth-selection (get-in response [:body "publicKey" "authenticatorSelection"])
              user (get-in response [:body "publicKey" "user"])]
          (is (= (auth-selection "residentKey") "preferred"))
          (is (= (auth-selection "userVerification") "required"))
          (is (= (get-in response [:body "publicKey" "attestation"]) "none"))
          (is (= (rp "name") "localhost"))
          (is (= (rp "id") "localhost"))
          (is (= (user "name") "nightman"))
          (is (= (user "displayName") "DayMan"))))

      (testing "stores options needed to complete the ceremony"
        (is (@sut/registration-options-store
             (String. (.decode (Base64/getDecoder) (.getBytes (get-in response [:body "publicKey" "user" "id"]))))))))))

(deftest complete
  (testing "registration failure due to RegistrationFailedException"
    (let [_ (reset! sut/registration-options-store {"user-handle" {:username "username" :opts "mock-opts"}})
          store (atom {})
          ceremony-result (fn [_ _ _] (throw (RegistrationFailedException. (IllegalArgumentException. "such failure"))))
          user-handle (redis/->b64 (.getBytes "user-handle"))
          response (call-api (sut/complete ceremony-result) {:user-handle user-handle :public-key-data "mock-public-key-data"} (->RedisStore store))]

      (testing "doesn't update the credential store"
        (is (empty? (keys @store))))

      (testing "removes user-handle from options store"
        (is (nil? (@sut/registration-options-store "user-handle"))))

      (testing "returns 401"
        (is (= (:status response) 401)))))

  (testing "registration success"
    (let [_ (reset! sut/registration-options-store {"user-handle" {:username "username" :opts "mock-opts"}})
          store (atom {})
          ceremony-result (fn [_ _ _] "mock-result")
          user-handle (redis/->b64 (.getBytes "user-handle"))
          response (call-api (sut/complete ceremony-result) {:user-handle user-handle :public-key-data "mock-public-key-data"} (->RedisStore store))]

      (testing "removes user-handle from options store"
        (is (nil? (@sut/registration-options-store user-handle))))

      (testing "returns 201"
        (is (= (:status response) 201)))

      (testing "returns cookie"
        (is (seq (get-in response [:cookies "speakeasy-token" :value]))))

      (testing "stores registered credential"
        (is (@store "user-handle"))))))

(defn build-public-key-credential-parameters [cose-id]
  (.. PublicKeyCredentialParameters
      builder
      (alg (.orElseThrow (COSEAlgorithmIdentifier/fromId cose-id)))
      build))

(deftest registration-ceremony
  (testing "Succeeds with known registration options and a captured registration body for a resident key"
    (let [store (atom {})
          user-handle "3b02dcc54ef8abd76d741fb0d9f2759061b862a95c0c5324dfdc1cb89fe49fe8"
          username user-handle
          display-name user-handle ;; This may be wrong...
          challenge "FD-A43J1hYn0deDuYcgOMpwEghvhZGKXnFMyE6PEH7E"
          challenge-bytes (.decode (Base64/getUrlDecoder) challenge)
          cred-id "YV-CkfpoooeiaZOesxFxDU0nOtD9IJDI2WKGXV5y67M"
          pkcco (.. PublicKeyCredentialCreationOptions
                    builder
                    (rp (rp/relying-party-id))
                    (user (rp/user-identity username display-name (.getBytes user-handle)))
                    (challenge (ByteArray. challenge-bytes))
                    (pubKeyCredParams (map build-public-key-credential-parameters [-7 -35 -36 -257 -258 -259]))
                    build)
          _ (swap! sut/registration-options-store assoc user-handle {:opts pkcco :username username})
          complete-request-body
          {:user-handle "M2IwMmRjYzU0ZWY4YWJkNzZkNzQxZmIwZDlmMjc1OTA2MWI4NjJhOTVjMGM1MzI0ZGZkYzFjYjg5ZmU0OWZlOA"
           :public-key-data {"type" "public-key"
                             "id" cred-id
                             "rawId" cred-id
                             "authenticatorAttachment" "cross-platform"
                             "response" {"clientDataJSON" "eyJ0eXBlIjoid2ViYXV0aG4uY3JlYXRlIiwiY2hhbGxlbmdlIjoiRkQtQTQzSjFoWW4wZGVEdVljZ09NcHdFZ2h2aFpHS1huRk15RTZQRUg3RSIsIm9yaWdpbiI6Imh0dHA6Ly9sb2NhbGhvc3Q6MzAwMCIsImNyb3NzT3JpZ2luIjpmYWxzZX0"
                                         "attestationObject" "o2NmbXRkbm9uZWdhdHRTdG10oGhhdXRoRGF0YViySZYN5YgOjGh0NBcPZHZgW4_krrmihjLHmVzzuoMdl2PFAAAA5QAAAAAAAAAAAAAAAAAAAAAAIGFfgpH6aKKHommTnrMRcQ1NJzrQ_SCQyNlihl1ecuuzpQECAyYgASFYIGPk2XZouqKWv97cvd9-b-iRs0qjr5Dy4zAfFpos30JlIlgg_47jxev_tw17u8wWx_vWigo6HzB4fa49rhdlySgvPyeha2NyZWRQcm90ZWN0Ag"
                                         "transports" ["usb"]}
                             "clientExtensionResults" {"credProps" {"rk" true}}}}
          response (call-api (sut/complete sut/ceremony-result) complete-request-body (->RedisStore store))]
      (is (= (:status response) 201))
      (is (seq (get-in response [:cookies "speakeasy-token" :value]))))))
