(ns speakeasy.middleware.system)

(defn attach [system]
  (fn [handler]
    (fn [request]
      (handler (assoc request ::system @system)))))
