(ns ampie.background.backend
  "Tools for communicating with the backend"
  (:require ["webextension-polyfill" :as browser]
            [mount.core])
  (:require-macros [mount.core :refer [defstate]]))

(defn get-token
  "Returns a promise that resolves with the auth token
  or nil if auth token is not set in the cookies."
  []
  (.then
    (.. browser -cookies
      (get #js {:name "auth-token" :url "http://localhost/"}))
    #(js->clj % :keywordize-keys true)))

(defn update-token [token]
  (.then (get-token) #(reset! token (:value %))))

(defstate auth-token
  :start (doto (atom nil)
           (update-token)))
