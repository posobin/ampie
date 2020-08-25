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

(defn- get-updated-entries
  "Takes the links received from the server with normalized urls as keys,
  takes the entries for the relevant nurls, merges the updates and returns
  a promise that resolves with the result. Doesn't change the db."
  [new-nurl->seen-at]
  (-> ;; Get relevant entries from the db
    (.-links @db) (.bulkGet (clj->js (keys new-nurl->seen-at)))
    (.then (fn [current-entries]
             (let [;; Make a map {nurl -> {id -> info}}
                   current-nurl->seen-at
                   (->> (i/js->clj current-entries)
                     (map #(vector (:normalized-url %)
                             (into {} (:seen-at %))))
                     (into {}))]
               ;; Make a map of changes to the above map
               (reduce
                 (fn [result [nurl seen-at]]
                   (let [current (current-nurl->seen-at nurl)
                         merged  (merge current seen-at)]
                     (if-not (= current  merged)
                       (assoc result nurl merged)
                       result)))
                 {} new-nurl->seen-at))))))

(defn- save-links
  "Takes a map nurl -> seen-at, rewrites entries in the links table
  with the given values. `cache-key` is the name used for error
  reporting."
  [nurl->seen-at cache-key]
  (log/info "Saving" (count nurl->seen-at) "links")
  (-> (.-links @db)
    (.bulkPut
      (i/clj->js
        (map (fn [[nurl sources]] {:normalized-url nurl
                                   :count          (count sources)
                                   :seen-at        sources})
          nurl->seen-at)))
    (.catch (.-BulkError Dexie)
      (fn [e]
        (js/console.log e)
        (log/error "Couldn't add" (.. e -failures -length)
          "entries to urls for cache" cache-key ", e.g."
          (js->clj (aget (.-failures e) 0)))))))

(defn update-link-cache
  "Downloads the given cache from `cache-url` and saves all the links from it
  to the DB. Returns the promise that resolves with nil once all the downloading
  and saving is finished."
  [cache-key cache-url]
  (letfn [;; Transform the plain vector cache as downloaded into a map
          ;; {normalized url -> {id -> source-info}}
          (cache->map [{:keys [field-names links]}]
            (let [keywordized-field-names (map keyword field-names)]
              (->> links
                (map #(into {} (map vector keywordized-field-names %)))
                (reduce
                  (fn [result link]
                    (assoc-in result
                      [(:normalized-url link) (:id link)]
                      (into {}
                        (remove (comp nil? val)
                          (dissoc link :id :normalized-url)))))
                  {}))))]
    (js/Promise.
      (fn [resolve reject]
        (GET cache-url
          {:response-format :json :keywords? true
           :error-handler
           (fn [{:keys [status status-text]}]
             (.. browser -storage -local
               (set #js {:link-cache-status
                         (str "Coudn't download cache " cache-key
                           ": " status-text " Will try again in 30 minutes.")}))
             (reject status-text))
           ;; Not using :progress-handler because it works only for upload
           ;; events.
           :handler
           (fn [cache]
             (log/info "Downloaded")
             (let [nurl->seen-at (cache->map cache)
                   total-count   (count nurl->seen-at)
                   recursive-chain
                   (fn recursive-chain [parts-nurl->seen-at]
                     (-> (get-updated-entries
                           (first parts-nurl->seen-at))
                       (.then #(save-links % cache-key))
                       (.then
                         (fn [_]
                           (.. browser -storage -local
                             (set #js {:link-cache-status
                                       (str "Unpacked "
                                         (- (quot total-count 1000)
                                           (count parts-nurl->seen-at))
                                         "/" (quot total-count 1000) " of "
                                         cache-key)}))
                           (if (seq (rest parts-nurl->seen-at))
                             (recursive-chain (rest parts-nurl->seen-at))
                             (resolve))))))]
               (log/info "Received cache" cache-key "saving")
               (recursive-chain
                 (partition-all 1000 nurl->seen-at))))})))))

(defn- update-cache-with-next-diff
  "Finds the diff for the given `cache-key`, downloads and applies it.
  Returns nil if no update available, the new cache key otherwise.
  Doesn't touch the list of downloaded caches."
  [cache-key]
  (log/info "Checking for updates to the cache" cache-key)
  (-> (->>
        (assoc (base-request-options)
          :params {:old-cache-key cache-key}
          :handler
          #(resolve (js->clj % :keywordize-keys true))
          :error-handler
          (fn [{:keys [status status-text]}]
            (do (log/error "Couldn't download cache diff:"
                  status-text)
                (resolve nil))))
        (POST (endpoint "links/get-next-cache-diff"))
        (fn [resolve])
        (js/Promise.))
    (.then (fn [{:keys [new-key url] :as response}]
             (if new-key
               (-> (update-link-cache new-key url)
                 (.then (constantly new-key))
                 (.catch
                   (fn [error]
                     (log/error "Couldn't load diff for cache" cache-key
                       ", new key" new-key error)
                     (throw error))))
               (js/Promise.resolve))))))

(defn update-caches-with-diffs
  "Goes through the saved caches, downloads diffs for them and applies diffs
  until the caches are at the newest version."
  []
  (letfn [(remove-and-add-ls [old-key new-key]
            (-> (.. browser -storage -local (get "link-caches-info"))
              (.then #(js->clj (aget % "link-caches-info") :keywordize-keys true))
              (.then (fn [{{:keys [past-keys] :or {past-keys []} :as old-value}
                           (keyword old-key)
                           :as link-caches-info}]
                       (let [new-value
                             (assoc old-value
                               :past-keys (conj past-keys old-key)
                               :downloaded-at (.getTime (js/Date.)))]
                         (-> (assoc link-caches-info new-key new-value)
                           (dissoc (keyword old-key))))))
              (.then
                (fn [new-link-caches-info]
                  (.. browser -storage -local
                    (set (clj->js {:link-caches-info new-link-caches-info})))))))
          (iterate-over-keys [link-caches-keys-queue]
            (when-let [cache-key (first link-caches-keys-queue)]
              (-> (update-cache-with-next-diff cache-key)
                (.then
                  (fn [new-cache-key]
                    (if (and new-cache-key (not= new-cache-key cache-key))
                      (.then (remove-and-add-ls cache-key new-cache-key)
                        (constantly (-> (rest link-caches-keys-queue)
                                      (conj new-cache-key))))
                      (rest link-caches-keys-queue))))
                (.then iterate-over-keys))))]
    (-> (.. browser -storage -local (get "link-caches-info"))
      ;; The keys are ids, so no keywordization
      (.then #(js->clj (aget % "link-caches-info") :keywordize-keys false))
      (.then #(iterate-over-keys (keys %))))))

(defn check-and-update-link-caches
  "Queries the server to get latest cache urls, gets their etags and if they
  don't match the ones stored, downloads the caches, updates the links db and
  the cache info (last updated timestamp and etag)."
  []
  (->
    (js/Promise.
      (fn [resolve]
        (GET (endpoint "links/get-cache-urls")
          (assoc (base-request-options)
            :handler #(resolve (js->clj % :keywordize-keys true))))))
    (.then
      (fn [server-caches]
        (-> ;; Reset link caches if told to do so by the server
          (if (:reset-link-caches server-caches)
            (-> (.. browser -storage -local (remove "link-caches-info"))
              (.then (fn [] (-> (.-links @db) (.clear)))))
            (js/Promise.resolve))
          ;; Update existing caches with diffs
          (.then update-caches-with-diffs)
          (.then (constantly (dissoc server-caches :reset-link-caches))))))
    (.then
      (fn iterate-over-response [server-caches]
        (-> (.. browser -storage -local (get "link-caches-info"))
          (.then #(js->clj (aget % "link-caches-info") :keywordize-keys false))
          (.then
            (fn [link-caches-info]
              (when-let [[cache-key url] (first server-caches)]
                (let [cache-key (name cache-key)]
                  (if-not (contains? link-caches-info cache-key)
                    (-> (.. browser -storage -local
                          (set #js {:link-cache-status
                                    (str "Downloading cache " cache-key)}))
                      (.then
                        (fn []
                          (log/info "Downloading cache" cache-key "from" url)
                          (update-link-cache cache-key url)))
                      (.then
                        (fn []
                          (log/info "Cache" cache-key "saved to the local DB")
                          (-> (.. browser -storage -local (get "link-caches-info"))
                            (.then #(js->clj (aget % "link-caches-info")
                                      :keywordize-keys true))
                            (.then
                              (fn [link-caches-info]
                                (.. browser -storage -local
                                  (set (clj->js
                                         {:link-caches-info
                                          (assoc link-caches-info cache-key
                                            {:past-keys     []
                                             :downloaded-at (.getTime
                                                              (js/Date.))})
                                          :link-cache-status nil}))))))))
                      (.catch
                        (fn [error]
                          (log/error error)
                          (throw error)
                          #_(.. browser -storage -local
                              (set (clj->js
                                     {:link-cache-status
                                      (str "Couldn't download link cache: " error)})))))
                      (.then #(iterate-over-response (rest server-caches))))
                    (iterate-over-response (rest server-caches))))))))))))

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
          :params {:links links}
          :handler #(resolve (js->clj % :keywordize-keys true))
          :error-handler
          #(reject (error->map %)))))))

(def last-downloaded-timestamp (atom nil))

(defn update-friends-visits
  "Requests the latest pages amplified by friends, saves them to the links db."
  []
  (letfn [(get-page [until]
            (->>
              (GET (endpoint "visits/get-friends-last-visits")
                (assoc (base-request-options)
                  :params (when until {:until until})
                  :handler #(resolve (:visits (js->clj % :keywordize-keys true)))
                  :error-handler
                  #(reject (error->map %))))
              (fn [resolve reject]) (js/Promise.)))

          (visit->local-link [{:keys [user-tag username reaction comment
                                      created-at normalized-url]
                               :as   visit}]
            (merge
              {:source       "vf"
               :v-user-tag   user-tag
               :v-username   username
               :v-created-at created-at}
              (when reaction {:reaction reaction})
              (when (some? comment) {:has-comment true})))

          (page->local-format [page]
            (->> page
              (map #(vector (:link-id %) (visit->local-link %)
                      (:normalized-url %)))
              (group-by last)
              (map (fn [[nurl seen-at]]
                     [nurl (->> (map pop seen-at) (into {}))]))
              (into {})))

          (load-next-page [page depth accum last-timestamp]
            (let [latest-timestamp-on-page   (-> page first :created-at)
                  earliest-timestamp-on-page (-> page last :created-at)
                  filtered-page
                  (if @last-downloaded-timestamp
                    (filter #(< @last-downloaded-timestamp (:created-at %))
                      page)
                    page)
                  page-in-local-format       (page->local-format filtered-page)]
              (if (seq filtered-page)
                (-> (.-links @db)
                  (.bulkGet (clj->js (keys page-in-local-format)))
                  (.then
                    (fn [current-entries]
                      (let [existing-ids
                            (->> (i/js->clj current-entries)
                              (mapcat :seen-at)
                              (map (comp js/parseInt name first))
                              (into #{}))
                            ;; Don't recurse if we have one of the links
                            ;; in the cache already.
                            stop-download (some existing-ids
                                            (map :link-id page))]
                        (if-not stop-download
                          (-> (get-page earliest-timestamp-on-page)
                            (.then #(load-next-page % (inc depth)
                                      (merge-with merge accum
                                        page-in-local-format)
                                      (max last-timestamp
                                        latest-timestamp-on-page))))
                          [(merge-with merge accum page-in-local-format)
                           (max last-timestamp latest-timestamp-on-page)])))))
                [accum last-timestamp])))]
    (-> (get-page nil)
      (.then #(load-next-page % 0 nil nil))
      (.then
        (fn [[links last-timestamp]]
          (if (seq links)
            (-> (get-updated-entries links)
              (.then save-links)
              (.then
                (fn []
                  (swap! last-downloaded-timestamp
                    #(max (or % 0) last-timestamp)))))
            (log/info "No new visits by friends")))))))

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
