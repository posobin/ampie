(ns ampie.background.backend
  "Functions for communicating with the backend"
  (:require ["webextension-polyfill" :as browser]
            [taoensso.timbre :as log]
            [reagent.core :as r]
            [ajax.core :refer [GET POST HEAD PUT DELETE]]
            [mount.core]
            [clojure.string :as string]
            [ampie.time :as time])
  (:require-macros [mount.core :refer [defstate]]))

(defn is-safari? []
  (boolean
    (re-find
      #"(?i)^((?!chrome|android).)*safari"
      (.-userAgent js/navigator))))

(def ampie-version (.. browser -runtime (getManifest) -version))
(def api-url (if goog.DEBUG "https://localhost:5001" "https://api.ampie.app"))
(defn endpoint [& params] (string/join "/" (concat [api-url] params)))

(defn- get-token
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

(defn- load-token! [token] (.then (get-token) #(reset! token (:value %))))
(defstate auth-token
  :start (let [atom (r/atom nil)]
           ;; We can't read HTTP only cookies on safari, this will be populated
           ;; when user opens ampie.app and extension reads the auth token
           ;; from the local storage.
           (when-not (is-safari?) (load-token! atom))
           atom))
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

(defn set-auth-token!
  "Sets the auth token to `new-value` and returns false if the new value
  is the same as the old one."
  [new-value]
  (let [[old new] (reset-vals! @auth-token new-value)]
    (not= old new)))

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
  "Get user info from the ampie backend and cache it in local storage.
  Rejects with :not-logged-in if the auth token is not loaded."
  [user-info-atom]
  (if (logged-in?)
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
              (reject e))))))
    (js/Promise.reject :not-logged-in)))

(defn get-user-info-from-ls
  "Returns a promise that resolves with the user info object
  cached in local storage. Can be nil."
  []
  (-> (.. browser -storage -local (get "user-info"))
    (.then #(js->clj % :keywordize-keys true))
    (.then #(get % :user-info))))

(defn load-user-info [user-info-atom]
  (.then
    (get-user-info-from-ls)
    (fn [user-info]
      (when user-info (reset! user-info-atom user-info))
      (on-logged-in :user-info-atom #(request-user-info user-info-atom)))))

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
                :params {:tweet-ids ids}
                :handler #(resolve (-> %
                                     (js->clj :keywordize-keys true)
                                     :tweets))))))))
    #(apply concat %)))

(defn get-parent-thread
  "Returns the hydrated tweet objects as returned by twitter.
  The tweets are the thread of parents for the given tweet id,
  including the tweet itself."
  [tweet-id]
  (js/Promise.
    (fn [resolve reject]
      (POST (endpoint "twitter/get-parent-thread")
        (assoc (base-request-options)
          :params {:tweet-id tweet-id}
          :handler #(resolve (-> %
                               (js->clj :keywordize-keys true)
                               :tweets))
          :error-handler #(reject (error->map %)))))))

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
    false
    #_(and @@user-info
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

(defn search-friends-visits [query]
  (js/Promise.
    (fn [resolve reject]
      (GET (endpoint "visits" "search")
        (assoc (base-request-options)
          :params {:query query}
          :handler #(resolve (js->clj % :keywordize-keys true))
          :error-handler #(reject (error->map %)))))))

(defn search-result-clicked []
  (js/Promise.
    (fn [resolve reject]
      (POST (endpoint "log/search-result-clicked")
        (assoc (base-request-options)
          :handler    #(resolve (js->clj % :keywordize-keys true))
          :error-handler #(reject (error->map %)))))))

(defn search-visit-clicked []
  (js/Promise.
    (fn [resolve reject]
      (POST (endpoint "log/search-visit-clicked")
        (assoc (base-request-options)
          :handler    #(resolve (js->clj % :keywordize-keys true))
          :error-handler #(reject (error->map %)))))))

(defn problem-getting-cache [cache-key error-text]
  (js/Promise.
    (fn [resolve reject]
      (POST (endpoint "problem-getting-cache")
        (assoc (base-request-options)
          :params        {:cache-key  cache-key
                          :error-text error-text}
          :handler       #(resolve (js->clj % :keywordize-keys true))
          :error-handler #(reject (error->map %)))))))

(defn setting-updated [key value]
  (js/Promise.
    (fn [resolve reject]
      (POST (endpoint "log/setting-updated")
        (assoc (base-request-options)
          :params        {:key key :value value}
          :handler       #(resolve (js->clj % :keywordize-keys true))
          :error-handler #(reject (error->map %)))))))

(defn get-my-last-visits []
  (js/Promise.
    (fn [resolve reject]
      (GET (endpoint "visits/get-users-last-visits")
        (assoc (base-request-options)
          :params        {:username (:username  @@user-info)}
          :handler       #(resolve (js->clj % :keywordize-keys true))
          :error-handler #(reject (error->map %)))))))

(defn get-my-last-url-votes [until]
  (js/Promise.
    (fn [resolve reject]
      (GET (endpoint "url-votes/get-users-votes")
        (assoc (base-request-options)
          :params        (when until {:until until})
          :handler       #(resolve (js->clj % :keywordize-keys true))
          :error-handler #(reject (error->map %)))))))

(defn get-partial-url-context
  "Returns the social context for the given url from the given origin."
  [url origin]
  (js/Promise.
    (fn [resolve reject]
      (POST (endpoint "links/get-url-occurrences-details")
        (assoc (base-request-options)
          :params        {:url    url
                          :origin origin
                          :src    :sidebar}
          :handler       #(resolve (js->clj % :keywordize-keys true))
          :error-handler #(reject (error->map %)))))))

(defn get-url-context
  "Returns all the social context for the given url."
  [url]
  (js/Promise.
    (fn [resolve reject]
      (POST (endpoint "links/get-url-occurrences-details-all-origins")
        (assoc (base-request-options)
          :params        {:url url
                          :src :sidebar}
          :handler       #(resolve (js->clj % :keywordize-keys true))
          :error-handler #(reject (error->map %)))))))

(defn get-urls-overview
  "Returns the overviews context for the given urls: counts for each origin
  and page details."
  [urls fast-but-incomplete src]
  (js/Promise.
    (fn [resolve reject]
      (POST (endpoint "links/get-urls-overview")
        (assoc (base-request-options)
          :params        {:urls                urls
                          :fast-but-incomplete fast-but-incomplete
                          :src                 src}
          :handler       #(resolve (js->clj % :keywordize-keys true))
          :error-handler #(reject (error->map %)))))))

(defn send-analytics-data
  "Sends the given analytics-data object to the analytics endpoint on the backend.
  Rejects if the request fails."
  [analytics-data]
  (js/Promise.
    (fn [resolve reject]
      (POST (endpoint "log/extension-analytics")
        (assoc (base-request-options)
          :params        {:data analytics-data}
          :handler       #(resolve (js->clj % :keywordize-keys true))
          :error-handler #(reject (error->map %)))))))

(defn send-feedback
  "Sends the given contents string to the feedback endpoint on the backend.
  Rejects if the request fails."
  [contents]
  (js/Promise.
    (fn [resolve reject]
      (POST (endpoint "log/feedback")
        (assoc (base-request-options)
          :params        {:contents contents}
          :handler       #(resolve (js->clj % :keywordize-keys true))
          :error-handler #(reject (error->map %)))))))
