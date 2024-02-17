(ns speakeasy.webauthn.data-test
  (:require [clojure.test :refer [deftest testing is are]]
            [speakeasy.webauthn.data :as sut])
    (:import [com.yubico.webauthn AssertionRequest CredentialRepository]
             [com.yubico.webauthn.data
              AuthenticatorResponse
              ByteArray
              ClientExtensionOutputs
              PublicKeyCredential
              PublicKeyCredentialCreationOptions
              PublicKeyCredentialRequestOptions
              UserIdentity]))

(defrecord RedisStore [redis-config]
  CredentialRepository
  (getCredentialIdsForUsername [_ _])
  (getUserHandleForUsername [_ _])
  (getUsernameForUserHandle [_ _])
  (lookup [_ _ _])
  (lookupAll [_ _]))

(defrecord AuthResp []
  AuthenticatorResponse
  (getAuthenticatorData [_])
  (getClientData [_])
  (getClientDataJSON [_])
  (getParsedAuthenticatorData [_]))

(defrecord ClientExOut []
  ClientExtensionOutputs
  (getExtensionIds [_] #{}))


(defonce mock-user (.. UserIdentity
                       builder
                       (name "user")
                       (displayName "display")
                       (id (ByteArray. (.getBytes "id")))
                       build))

(defonce public-key-cred (.. PublicKeyCredential
                             builder
                             (id (ByteArray. (.getBytes "id")))
                             (response (->AuthResp))
                             (clientExtensionResults (->ClientExOut))
                             build))

(deftest relying-party
  (testing "relying-party builds RelyingPartyIdentiy correctly"
    (let [rpid (.getIdentity (sut/relying-party (->RedisStore {})))]
      (is (= (.getId rpid) "localhost"))
      (is (= (.getName rpid) "localhost"))))

  (testing "relying-party builds RelyingParty correctly"
    (let [rp (sut/relying-party (->RedisStore {}))]
      (is (= (.getOrigins rp) #{"http://localhost:3000" "http://localhost"})))))

(deftest start-registration-options
  (let [start-reg-opts (sut/start-registration-options mock-user)]

    (testing "start-registration-options sets user-id correctly"
      (is (= (.getUser start-reg-opts) mock-user)))

    (testing "start-registartion-options sets auth selection criteria correctly"
      (let [auth-selection (.. start-reg-opts getAuthenticatorSelection orElseThrow)]
        (is (= (.. auth-selection getResidentKey orElseThrow getValue)
               "preferred"))
        (is (= (.. auth-selection getUserVerification orElseThrow getValue)
               "required"))
        (is (not (.. auth-selection getAuthenticatorAttachment isPresent)))))))

(deftest finish-registraiton-options
  (let [rpid (.getIdentity (sut/relying-party (->RedisStore {})))
        start-reg-opts (.. PublicKeyCredentialCreationOptions
                           builder
                           (rp rpid)
                           (user mock-user)
                           (challenge (ByteArray. (.getBytes "asdF")))
                           (pubKeyCredParams [])
                           build)
        finish-reg-opts (sut/finish-registration-options start-reg-opts public-key-cred)]

    (testing "finish-registration-options sets request field correctly"
      (is (= (.getRequest finish-reg-opts) start-reg-opts)))

    (testing "finish-registration-options sets response field correctly"
      (is (= (.getResponse finish-reg-opts) public-key-cred)))))

(deftest start-assertion-options
  (testing "start-assertion-options correctly sets username when present"
    (let [start-assertion-opts (sut/start-assertion-options "username")]
      (is (= (.. start-assertion-opts getUsername orElseThrow) "username"))))

  (testing "start-assertion-options skips username when empty or nil"
    (are [opts] (not (.. opts getUsername isPresent))
      (sut/start-assertion-options nil)
      (sut/start-assertion-options ""))))

(deftest finish-assertion-options
  (let [pkcr-opts (.. PublicKeyCredentialRequestOptions
                      builder
                      (challenge (ByteArray. (.getBytes "challenging")))
                      build)
        auth-request (.. AssertionRequest
                         builder
                         (publicKeyCredentialRequestOptions pkcr-opts)
                         build)
        finish-assertion-opts (sut/finish-assertion-options auth-request public-key-cred)]

    (testing "finish-assertion-options sets request field correctly"
      (is (= (.getRequest finish-assertion-opts) auth-request)))

    (testing "finish-assertion-options sets response field correctly"
      (is (= (.getResponse finish-assertion-opts) public-key-cred)))))

(deftest user-identity
  (testing "`user-idenity` correctly builds object"
    (let [ui (sut/user-identity "username" "display-name" (.getBytes "username"))]
      (is (= (.getName ui) "username"))
      (is (= (String. (.getBytes (.getId ui))) "username"))
      (is (= (.getDisplayName ui) "display-name")))))
