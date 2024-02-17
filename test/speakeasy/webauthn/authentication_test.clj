(ns speakeasy.webauthn.authentication-test
  (:require [clojure.test :refer [deftest testing is are]]
            [speakeasy.config :as config]
            [speakeasy.jwt :as jwt]
            [speakeasy.middleware.system :as system]
            [speakeasy.redis :as redis]
            [speakeasy.webauthn.authentication :as sut])
  (:import [com.yubico.webauthn AssertionRequest CredentialRepository RegisteredCredential]
           [com.yubico.webauthn.data ByteArray PublicKeyCredentialRequestOptions]
           [com.yubico.webauthn.exception AssertionFailedException]
           [java.util Base64 Optional]))

(defrecord RedisStore [store]
  redis/WebAuthnStore
  CredentialRepository

  (store-user-handle [_ _ _])
  (store-credential [_ _ _])
  (get-credential [_ _])
  (get-user-credential [_ _ _])
  (update-credential [_ result]
    (swap! store conj result))

  (getCredentialIdsForUsername [_ _]
    #{})

  (getUserHandleForUsername [_ username]
    (if username
      (Optional/of (ByteArray. (.getBytes "user-handle")))
      (Optional/empty)))

  (getUsernameForUserHandle [_ handle]
    (if handle
      (Optional/of (String. (.getBytes "username")))
      (Optional/empty)))

  (lookup [_ cred-id user-handle]
    (let [cred-map {:cose [-91, 1, 2, 3, 38, 32, 1, 33, 88, 32,
                           -50, -97, -105, -46, -71, 84, -69, 108,
                           71, -56, 14, 47, 15, -1, -1, -10, -27,
                           53, 113, 2, 114, 34, -109, -112, 119,
                           -124, 111, -18, -50, -18, -59, -114, 34,
                           88, 32, 6, 17, -84, 45, -59, 34, -5,
                           -45, -21, -1, 10, -57, -55, 10, 64, 84,
                           -10, 69, -100, 45, 70, -7, -121, 121,
                           47, -113, -91, 50, -127, -61, -126,
                           121],
                    :type "public-key"
                    :transport-ids '("usb")
                    :sig-count 1
                    :discoverable? true
                    :user-verified? true
                    :cred-id "ZaAMP1KbNQQa-9gNZ1g-0bfPyAPXvuRF-PVqdxztZRs="}]
      (Optional/of
       (.. RegisteredCredential
           builder
           (credentialId cred-id)
           (userHandle user-handle)
           (publicKeyCose (ByteArray. (byte-array (:cose cred-map))))
           (signatureCount (:sig-count cred-map))
           build))))

  (lookupAll [_ _]
    #{}))

(defn call-api
  ([api-fn body-params]
   (call-api api-fn body-params (->RedisStore nil)))

  ([api-fn body-params redis-store]
   (api-fn {::system/system {::config/config {::config/hostname "localhost"}
                             ::redis/redis redis-store}
            :body-params body-params})))

(deftest start
  (testing "`start` returns a json that clients can use to start a ceremony"
    (testing "no username"
      (let [response (call-api sut/start {})
            opts (:opts (:body response))]
        (is (= (:status response) 200))
        (is (not= (:user-handle (:body response)) ""))
        (is (= (get-in opts ["publicKey" "rpId"]) "localhost"))))

    (testing "with username"
      (let [response (call-api sut/start {:username "username"})
            opts (:opts (:body response))]
        (is (= (:status response) 200))
        (is (= "user-handle" (:user-handle (:body response))))
        (is (= (get-in opts ["publicKey" "rpId"]) "localhost")))))

  (testing "`start` correctly stores auth start options when no username is present and multiple users start a ceremony"
    (reset! sut/authentication-requests-store {})
    (let [n 10]
      (dotimes [_ n]
        (call-api sut/start {}))

      (is (= (count (keys @sut/authentication-requests-store)) n))
      (is (= n (count (distinct (vals @sut/authentication-requests-store)))))))

  (testing "`start` correctly stores auth start options when multiple users start a ceremony and some have usernames while some don't"
    (reset! sut/authentication-requests-store {})
    (call-api sut/start {})
    (call-api sut/start {:username "username"})
    (is (= (count (keys @sut/authentication-requests-store)) 2))
    (is (= 2 (count (distinct (vals @sut/authentication-requests-store)))))))

(deftest complete
  (testing "auth succeeds"
    (reset! sut/authentication-requests-store {"user-handle" "auth-request-data-mock"})
    (let [complete (sut/complete (fn [_ _ _ _] {:success? true :data "some-data"}))
          store (atom [])
          response (call-api complete {:user-handle "user-handle" :public-key-data "mock-data-this-would-be-a-json"} (->RedisStore store))]

      (testing "and returns 200"
        (is (= (:status response) 200)))

      (testing "and returns a cookie"
        (is (seq (get-in response [:cookies "speakeasy-token" :value]))))

      (testing "and updates a database store"
        (is (= (count @store) 1)))

      (testing "and updates the `authentication-request-store`"
        (is (nil? (@sut/authentication-requests-store "user-handle"))))))

  (testing "auth fails"
    (let [complete (sut/complete (fn [_ _ _ _] {:success? false}))
          response (call-api complete {:user-handle "user-handle" :public-key-data "mock-data-this-would-be-a-json"})]

      (testing "and returns 401"
        (is (= (:status response) 401)))

      (testing "and doesn't set a cookie"
        (is (empty? (get-in response [:cookies "speakeasy-token" :value]))))))

  (testing "auth fails due to AssertionFailedException"
    (let [complete (sut/complete (fn [_ _ _ _] (throw (AssertionFailedException. "OH NO"))))
          response (call-api complete {:user-handle "user-handle" :public-key-data "mock-data-this-would-be-a-json"})]

      (testing "and returns 401"
        (is (= (:status response) 401)))

      (testing "and doesn't set a cookie"
        (is (empty? (get-in response [:cookies "speakeasy-token" :value])))))))

(deftest check
  (testing "`check` rejects invalid tokens"
    (is (= 401 (:status (sut/check nil))))

    (are [cookie] (= 401 (:status (sut/check {:cookies {"speakeasy-token" {:value cookie}}})))
      nil
      "some-invalid-thing"))

  (testing "`check` accepts valid tokens"
    (is (= 200 (:status (sut/check {:cookies {"speakeasy-token" {:value (jwt/token 1)}}}))))))

(deftest authentication-ceremony
  (testing "Succeeds with a known AssertionRequest and captured completion request body from a resident key"
    (let [store (atom [])
          user-handle "020b609c9683cc24b2c8a0c79128a1ae230e10fc458f730990c5ab6eb5fe1e98"
          cred-id "ZaAMP1KbNQQa-9gNZ1g-0bfPyAPXvuRF-PVqdxztZRs" ;;TODO: make this cred-id and the stored cred-id the same?
          challenge "0k8P-JMojz6kLGplg2O8LkwJ1c8SFbFIPCvw6v_YGmQ"
          challenge-bytes (.decode (Base64/getUrlDecoder) challenge)
          pkcro (.. PublicKeyCredentialRequestOptions builder (challenge (ByteArray. challenge-bytes)) (rpId "localhost") build)
          auth-request (.. AssertionRequest builder (publicKeyCredentialRequestOptions pkcro) build)
          _ (swap! sut/authentication-requests-store assoc user-handle auth-request)
          complete-request-body
          {:user-handle user-handle
           :public-key-data {"type" "public-key"
                             "id" cred-id
                             "rawId" cred-id
                             "authenticatorAttachment" "cross-platform"
                             "response" {"clientDataJSON" "eyJ0eXBlIjoid2ViYXV0aG4uZ2V0IiwiY2hhbGxlbmdlIjoiMGs4UC1KTW9qejZrTEdwbGcyTzhMa3dKMWM4U0ZiRklQQ3Z3NnZfWUdtUSIsIm9yaWdpbiI6Imh0dHA6Ly9sb2NhbGhvc3Q6MzAwMCIsImNyb3NzT3JpZ2luIjpmYWxzZX0"
                                         "authenticatorData" "SZYN5YgOjGh0NBcPZHZgW4_krrmihjLHmVzzuoMdl2MFAAAARQ"
                                         "signature" "MEUCIA49PF7wgZzalpW4VSbi3PBwaL0xmw9H5Flj0alex-ydAiEA7TrxbbA9ppcc7RJgI9U4pFixwBoH54j5cvbo4dP4UQY"
                                         "userHandle" "ZjYwMjMxYjBmMjc4MGRjZTgzZDk0YWVjNWU0NzM2NzhlYzY4MTVjMWEwYmU3NmRmNzBjYzA5YTA4MjQ5NjNjZA"}
                             "clientExtensionResults" {}}}
          response (call-api (sut/complete sut/ceremony-result) complete-request-body (->RedisStore store))]
      (is (= (:status response) 200))
      (is (seq (get-in response [:cookies "speakeasy-token" :value]))))))
