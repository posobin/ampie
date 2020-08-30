(ns ampie.background.backend
  "Functions for communicating with the backend"
  (:require ["webextension-polyfill" :as browser]
            [taoensso.timbre :as log]
            [reagent.core :as r]
            [ajax.core :refer [GET POST HEAD PUT DELETE]]
            [mount.core]
            [clojure.string :as string]
            [ampie.db :refer [db]]
            [ampie.interop :as i]
            [clojure.set :as set]
            ["dexie" :default Dexie]
            [ampie.time :as time])
  (:require-macros [mount.core :refer [defstate]]))

(def ampie-version (.. browser -runtime (getManifest) -version))
(def api-url (if goog.DEBUG "http://localhost:5000" "https://api.ampie.app"))
(defn endpoint [& params] (string/join "/" (concat [api-url] params)))

(defn get-token
  "Returns a promise that resolves with the auth token
  or nil if the auth token is not set in the cookies."
  []
  (.then
    (if (.-cookies browser)
      (.. browser -cookies
        (get #js {:name "auth-token" :url
                  (if goog.DEBUG
                    "http://localhost/"
                    "https://api.ampie.app")}))
      (js/Promise.resolve nil))
    #(js->clj % :keywordize-keys true)))

(defn load-token [token] (.then (get-token) #(reset! token (:value %))))
(defstate auth-token :start (doto (r/atom nil) (load-token)))
(defn logged-in? [] (some? @@auth-token))
(defn on-logged-in
  "Calls f once the auth token changes to a new non-nil value.
  Will call f also if the auth token is already non-nil.
  Need to supply the key for the watcher, to be able to remove."
  [key f]
  (when @@auth-token (f))
  (add-watch @auth-token key
    (fn [_ _ old new]
      (when (and new (not= old new)) (f)))))
(defn remove-on-logged-in [key]
  (remove-watch @auth-token key))

(defn base-request-options []
  {:headers          {:Authorization (str "Token " @@auth-token)
                      :Ampie-Version ampie-version}
   :with-credentials true
   :format           :json
   :response-format  :json
   :keywords?        true})

(defn error->map [error]
  (let [{:keys [status response]} (js->clj error :keywordize-keys true)]
    (assoc response :fail true :status status)))

(defn request-user-info
  "Get user info from the ampie backend."
  [user-info-atom]
  (when (logged-in?)
    (js/Promise.
      (fn [resolve reject]
        (GET (endpoint "user-info")
          (assoc (base-request-options)
            :handler (fn [user-info]
                       (reset! user-info-atom user-info)
                       (.then
                         (.. browser -storage -local
                           (set (clj->js {:user-info user-info})))
                         #(resolve user-info)))
            :error-handler
            (fn [{:keys [status] :as e}]
              (when (= status 401)
                (reset! @auth-token nil)
                (reset! user-info-atom nil))
              (log/error "Couldn't get user's info" e)
              (reject e))))))))

(defn load-user-info [user-info-atom]
  (.then
    (.. browser -storage -local (get "user-info"))
    (fn [user-info]
      (let [user-info (-> (js->clj user-info :keywordize-keys true)
                        :user-info)]
        (when user-info (reset! user-info-atom user-info))
        (on-logged-in :user-info-atom #(request-user-info user-info-atom))))))

(defstate user-info :start (doto (r/atom nil) (load-user-info)))

(defn cookie-change-listener [^js change-info]
  (let [removed (.-removed change-info)
        cause   (.-cause change-info)
        value   (.. change-info -cookie -value)
        name    (.. change-info -cookie -name)
        domain  (.. change-info -cookie -domain)]
    (when (and (= name "auth-token") (or (when goog.DEBUG (= domain "localhost"))
                                       (= domain "ampie.app")
                                       (= domain "api.ampie.app")
                                       (= domain ".ampie.app")))
      (if (and removed (not= cause "overwrite"))
        (reset! @auth-token nil)
        (reset! @auth-token value)))))

(defstate cookie-watcher
  :start
  (when (.-cookies browser)
    (.. browser -cookies -onChanged (addListener cookie-change-listener)))
  :stop
  (when (.-cookies browser)
    (.. browser -cookies -onChanged (removeListener cookie-change-listener))))

(defn link-ids-to-info
  "Queries the server with the given link ids and returns a Promise that
  resolves with the information about each one. `links` has to be a seq of pairs
  [link-id origin] in the format that the backend's
  `ampie-backend.links/get-links-info` understands."
  [links]
  (js/Promise.
    (fn [resolve reject]
      (POST (endpoint "links/get-links-info")
        (assoc (base-request-options)
          :params {:links (map #(update % :id js/parseInt) links)}
          :handler #(resolve (js->clj % :keywordize-keys true))
          :error-handler
          #(reject (error->map %)))))))

(defn get-links-pages-info
  "Takes a seq of link ids and returns a seq with
  the titles of the corresponding web pages."
  [link-ids]
  (let [link-ids (map js/parseInt link-ids)]
    (.then
      (js/Promise.
        (fn [resolve reject]
          (POST (endpoint "links/get-links-pages-info")
            (assoc (base-request-options)
              :params {:link-ids link-ids}
              :error-handler #(reject (error->map %))
              :handler #(resolve (js->clj % :keywordize-keys true))))))
      (fn [result]
        (map (->> (:links-info result)
               (map #(vector (:link-id %) %))
               (into {}))
          link-ids)))))

(defn get-tweets
  "Returns the hydrated tweet objects as returned by twitter.
  `ids` should contain tweet ids."
  [ids]
  (.then
    (js/Promise.all
      (for [ids (partition 100 100 nil ids)]
        (js/Promise.
          (fn [resolve]
            (POST (endpoint "twitter/get-tweets-by-ids")
              (assoc (base-request-options)
                :params {:ids ids}
                :handler #(resolve (-> %
                                     (js->clj :keywordize-keys true)
                                     :tweets))))))))
    #(apply concat %)))

(defn load-url-info
  "Requests url info from the ampie server, returns a Promise
  that resolves with a map {:property-name value},
  for now only the :title property is returned."
  [url]
  (js/Promise.
    (fn [resolve reject]
      (POST (endpoint "load-url-info")
        (assoc (base-request-options)
          :params {:url url}
          :handler #(resolve (js->clj % :keywordize-keys true))
          :error-handler #(reject (error->map %)))))))

(defn send-weekly-links
  "Sends the given links to the backend to create a weekly links post.
  Returns a Promise that resolves with the url of the resulting page,
  or with a map describing the error."
  [links week-of]
  (js/Promise.
    (fn [resolve reject]
      (POST (endpoint "weekly-links")
        (assoc (base-request-options)
          :params {:links links :week-of week-of}
          :handler
          (fn [result]
            (let [week-of (-> result (js->clj :keywordize-keys true) :week-of)]
              (resolve
                (str "https://ampie.app/weekly/"
                  (:username @@user-info) "/" week-of))))
          :error-handler #(reject (error->map %)))))))

(defn can-complete-weekly? []
  (let [dow              (.getDay (js/Date.))
        current-week     (-> (js/Date.)
                           time/get-start-of-week
                           time/js-date->yyyy-MM-dd)
        last-filled-week (first (:weekly-links-posts @@user-info))]
    (and @@user-info
      (not= current-week last-filled-week)
      (zero? dow))))

(defn amplify-page [page-info]
  (js/Promise.
    (fn [resolve reject]
      (POST (endpoint "visit")
        (assoc (base-request-options)
          :params {:visit-info page-info}
          :handler #(resolve (js->clj % :keywordize-keys true))
          :error-handler #(reject (error->map %)))))))

(defn update-amplified-page [{:keys [submission-tag] :as updated-page-info}]
  (js/Promise.
    (fn [resolve reject]
      (if submission-tag
        (PUT (endpoint "visit")
          (assoc (base-request-options)
            :params {:visit-info     updated-page-info
                     :submission-tag submission-tag}
            :handler #(resolve (js->clj % :keywordize-keys true))
            :error-handler #(reject (error->map %))))
        (reject {:fail true :message "Amplify the page before sending update queries."})))))

(defn delete-amplified-page [submission-tag]
  (js/Promise.
    (fn [resolve reject]
      (if submission-tag
        (DELETE (endpoint "visit")
          (assoc (base-request-options)
            :params {:submission-tag submission-tag}
            :handler #(resolve (js->clj % :keywordize-keys true))
            :error-handler #(reject (error->map %))))
        (reject {:fail true :message "Page hasn't been amplified yet."})))))
