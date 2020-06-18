(ns ampie.background.backend
  "Functions for communicating with the backend"
  (:require ["webextension-polyfill" :as browser]
            [taoensso.timbre :as log]
            [reagent.core :as r]
            [ajax.core :refer [GET POST HEAD]]
            [mount.core]
            [clojure.string :as string]
            [ampie.db :refer [db]]
            [ampie.interop :as i]
            [clojure.set :as set]
            ["dexie" :default Dexie])
  (:require-macros [mount.core :refer [defstate]]))

(def api-url (if goog.DEBUG "http://localhost:5000" "https://api.ampie.app"))
(defn endpoint [& params] (string/join "/" (concat [api-url] params)))

(defn get-token
  "Returns a promise that resolves with the auth token
  or nil if the auth token is not set in the cookies."
  []
  (.then
    (if (.-cookies browser)
      (.. browser -cookies
        (get #js {:name "auth-token" :url (if goog.DEBUG
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
      (when new (f)))))
(defn remove-on-logged-in [key]
  (remove-watch @auth-token key))

(defn base-request-options []
  {:headers          {:Authorization (str "Token " @@auth-token)}
   :with-credentials true
   :format           :json
   :response-format  :json
   :keywords?        true})

(defn request-user-info
  "Get user info from the ampie backend."
  [user-info-atom]
  (when (logged-in?)
    (GET (endpoint "user-info")
      (assoc (base-request-options)
        :handler (fn [user-info]
                   (swap! user-info-atom user-info)
                   (.. browser -storage -local
                     (set (clj->js {:user-info user-info}))))
        :error-handler
        (fn [{:keys [status] :as e}]
          (when (= status 401)
            (swap! @auth-token nil)
            (swap! user-info-atom nil))
          (log/error "Couldn't get user's info"
            e))))))

(defn load-user-info [user-info-atom]
  (.then
    (.. browser -storage -local (get "user-info"))
    (fn [user-info]
      (let [user-info (-> (js->clj user-info :keywordize-keys true)
                        :user-info)]
        (log/info user-info)
        (when user-info (reset! user-info-atom user-info))
        (on-logged-in :user-info-atom #(request-user-info user-info-atom))))))

(defstate user-info :start (doto (r/atom nil) (load-user-info)))

(defn cookie-change-listener [change-info]
  (let [removed (.-removed change-info)
        cause   (.-cause change-info)
        value   (.. change-info -cookie -value)
        name    (.. change-info -cookie -name)
        domain  (.. change-info -cookie -domain)]
    (when (and (= name "auth-token") (or (when goog.DEBUG (= domain "localhost"))
                                       (= domain "ampie.app")
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

(defn update-link-cache
  "Downloads the given cache from `cache-url` and saves all the links from it
  to the DB. Returns the promise that resolves with nil once all the downloading
  and saving is finished."
  [cache-key cache-url]
  (letfn [(get-updated-entries [new-nurl->seen-at]
            (-> ;; Get relevant entries from the db
              (.-links @db) (.bulkGet (clj->js (keys new-nurl->seen-at)))
              (.then (fn [current-entries]
                       (let [;; Make a map nurl -> vector of [id source]
                             current-nurl->seen-at
                             (->> (i/js->clj current-entries)
                               (map #(vector (:normalized-url %)
                                       (set (:seen-at %))))
                               (into {}))]
                         ;; Make a map of changes to the above map
                         (reduce
                           (fn [result [nurl seen-at]]
                             (let [current (current-nurl->seen-at nurl)
                                   union   (set/union seen-at current)]
                               (if (> (count union) (count current))
                                 (assoc result nurl union)
                                 result)))
                           {} new-nurl->seen-at))))))
          (save-links [nurl->seen-at]
            (-> (.-links @db)
              (.bulkPut
                (i/clj->js
                  (map (fn [[nurl sources]] {:normalized-url nurl
                                             :seen-at        sources})
                    nurl->seen-at)))
              (.catch (.-BulkError Dexie)
                (fn [e]
                  (log/error "Couldn't add" (.. e -failures -length)
                    "entries to urls, e.g."
                    (js->clj (aget (.-failures e) 0)))))))
          ;; Transform the plain vector cache as downloaded into a map
          ;; normalized url -> [[id source]+]
          (cache->map [{:keys [field-names links]}]
            (let [keywordized-field-names (map keyword field-names)]
              (->> links
                (map #(into {} (map vector keywordized-field-names %)))
                (reduce
                  (fn [result link]
                    (update result (:normalized-url link)
                      #(conj (or % #{}) [(:id link) (:seen-at link)])))
                  {}))))]
    (js/Promise.
      (fn [resolve]
        (GET cache-url
          {:response-format :json :keywords? true
           ;; Not using :progress-handler because it works only for upload
           ;; events.
           :handler
           (fn [cache]
             (let [nurl->seen-at (cache->map cache)]
               (log/info "Received cache" cache-key "saving")
               (.then
                 (js/Promise.all
                   (for [nurl->seen-at (partition 10000 10000 nil nurl->seen-at)]
                     (-> (get-updated-entries nurl->seen-at)
                       (.then save-links))))
                 (fn [_] (resolve)))))})))))

(defn check-and-update-link-caches
  "Queries the server to get latest cache urls, gets their etags and if they
  don't match the ones stored, downloads the caches, updates the links db and
  the cache info (last updated timestamp and etag)."
  []
  (-> (js/Promise.
        (fn [resolve]
          (GET (endpoint "links/get-cache-urls")
            (assoc (base-request-options)
              :handler #(resolve (js->clj % :keywordize-keys true))))))
    (.then (fn [server-caches]
             (doseq [[cache-key url] server-caches]
               (-> (js/Promise.all
                     [(let [storage-key (str "link-cache-" (name cache-key))]
                        (.then (.. browser -storage -local (get storage-key))
                          #(-> (js->clj % :keywordize-keys true)
                             ((keyword storage-key)))))
                      (js/Promise.
                        (fn [resolve]
                          (HEAD url
                            {:response-format identity
                             :handler
                             (fn [response]
                               (resolve (-> (.getResponseHeaders response)
                                          (js->clj :keywordize-keys true)
                                          :etag)))})))])
                 (.then
                   (fn [[previous-info new-etag]]
                     (when-not (= (:etag previous-info) new-etag)
                       (log/info "Downloading cache" cache-key "from" url)
                       (.then (update-link-cache cache-key url)
                         (constantly new-etag)))))
                 (.then
                   (fn [new-etag]
                     (when new-etag
                       (log/info "Cache saved to the local DB")
                       (.. browser -storage -local
                         (set (clj->js {(str "link-cache-" (name cache-key))
                                        {:last-updated (js/Date.now)
                                         :etag         new-etag}}))))))))))))
