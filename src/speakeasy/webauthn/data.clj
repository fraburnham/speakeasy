(ns speakeasy.webauthn.data
  (:import [com.yubico.webauthn
            FinishAssertionOptions
            FinishRegistrationOptions
            RelyingParty
            StartAssertionOptions
            StartRegistrationOptions]
           [com.yubico.webauthn.data
            AuthenticatorSelectionCriteria
            ByteArray
            RelyingPartyIdentity
            ResidentKeyRequirement
            UserIdentity
            UserVerificationRequirement]
           [java.util Optional]))

(def authenticator-selection-criteria
  (.. AuthenticatorSelectionCriteria
      builder
      (residentKey (ResidentKeyRequirement/valueOf "PREFERRED"))
      (userVerification (UserVerificationRequirement/valueOf "REQUIRED"))
      build))

(defn relying-party-id [hostname]
  (.. RelyingPartyIdentity
      builder
      (id hostname)
      (name hostname)
      build))

(defn relying-party [redis relying-party-id-domain origin]
  (let [relying-party-id (relying-party-id relying-party-id-domain)
        relying-party (.. RelyingParty
                          builder
                          (identity relying-party-id)
                          (credentialRepository redis)
                          (origins #{origin})
                          build)]
    relying-party))

(defn start-registration-options [user-id]
  (.. StartRegistrationOptions
      builder
      ;; TODO: timeout?
      (user user-id)
      (authenticatorSelection
       (Optional/of authenticator-selection-criteria))
      build))

(defn finish-registration-options [start-registration-options public-key-cred]
  (.. FinishRegistrationOptions
      builder
      (request start-registration-options)
      (response public-key-cred)
      build))

(defn start-assertion-options [username]
  (if (empty? username)
    (.. StartAssertionOptions builder build)
    (.. StartAssertionOptions
        builder
        ;; TODO: user-handle?
        ;; TODO: require user verification
        (username (Optional/of username))
        build)))

(defn finish-assertion-options [auth-request public-key-cred]
  (.. FinishAssertionOptions
      builder
      (request auth-request)
      (response public-key-cred)
      build))

(defn user-identity [username display-name user-handle]
  (.. UserIdentity
      builder
      (name username)
      (displayName (or display-name username))
      (id (ByteArray. user-handle))
      build))
