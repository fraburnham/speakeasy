(ns speakeasy.jwt
  (:import [com.auth0.jwt JWT]
           [com.auth0.jwt.algorithms Algorithm]
           [com.auth0.jwt.exceptions JWTVerificationException]
           [java.security SecureRandom]
           [java.time Instant]
           [java.time.temporal ChronoUnit]))

(defonce signing-secret
  (let [b (byte-array 128)]
    (.nextBytes (SecureRandom.) b)
    (Algorithm/HMAC512 b)))

(defn token
  ([expiry-minutes]
   (token expiry-minutes signing-secret))
  ([expiry-minutes signing-secret]
   ;; TODO: are there any other details worth caring about?
   (.. (JWT/create)
       (withIssuer "speakeasy")
       (withExpiresAt (.. (Instant/now)
                          (plus expiry-minutes (ChronoUnit/valueOf "MINUTES"))))
       (sign signing-secret))))

(defn decode [jwt]
  (.. (JWT/require signing-secret)
      (withIssuer "speakeasy")
      build
      (verify jwt)))

(defn valid? [jwt]
  (try
    (boolean (decode jwt))
    (catch JWTVerificationException _
      false)))
