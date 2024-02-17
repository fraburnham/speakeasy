(ns speakeasy.webauthn.html
  (:require [hiccup2.core :as h]))

(def form
  [:div {:class "bg-white shadow p-4"}
   [:div {:class "mt-2 text-center gap-2 d-grid"}
    [:button {:type "button" :class "btn btn-primary" :id "register"} "Register"]]
   [:hr]
   [:div {:class "mt-2 text-center gap-2 d-grid"}
    [:button {:type "button" :class "btn btn-primary" :id "passkey"} "Passkey"]]])

(def head
  [:head
   [:link {:rel "stylesheet"
           :href "https://cdn.jsdelivr.net/npm/bootstrap@5.3.2/dist/css/bootstrap.min.css"
           :integrity "sha384-T3c6CoIi6uLrA9TneNEoa7RxnatzjcDSCmG1MXxSR1GAsXEV/Dwwykc2MPK8M2HN"
           :crossorigin "anonymous"}]
   [:script {:src "/speakeasy/resources/js/app.bundle.js"}]])

(def page
  (str
   (h/html
       head
       [:body {:class "bg-light"}
        [:div#container
         [:div {:class "registration-container d-flex justify-content-center mt-5"}
          form]]])))
