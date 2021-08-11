(ns ampie.background.link-cache-sync
  (:require [ampie.background.backend :as backend]
            [ampie.background.demo :as demo]
            [ampie.links :as links]
            [ampie.db :refer [db]]
            [ampie.interop :as i]
            [ampie.macros :refer [|vv]]
            [ajax.core :refer [GET POST]]
            [cljs.pprint]
            [taoensso.timbre :as log]
            ["dexie" :default Dexie]
            ["webextension-polyfill" :as browser]
            ["jsonparse" :as JSONParser]
            [mount.core :refer [defstate]]))

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
                     (map (fn [entry]
                            (vector (:normalized-url entry)
                              (reduce-kv #(assoc %1 (name %2) %3)
                                {} (:seen-at entry)))))
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

(comment
  (doseq [[nurl name]
          [["com.eugenewei/blog/2019/2/19/status-as-a-service" 'staas-links]
           ["com.eugenewei/blog/2018/5/21/invisible-asymptotes"
            'invisible-asymptotes-links]]]
    (.then
      (get-updated-entries {nurl {:a :b}})
      (fn [result]
        (let [filtered (update result nurl dissoc :a)]
          (cljs.pprint/pprint
            `(def ~name ~filtered)))))))

(defn- save-links
  "Takes a map nurl -> seen-at, rewrites entries in the links table
  with the given values. `cache-key` is the name used for error
  reporting."
  [nurl->seen-at cache-key]
  (log/info "Saving" (count nurl->seen-at) "links")
  (if (seq nurl->seen-at)
    (-> (.-links @db)
      (.bulkPut
        (i/clj->js
          (map (fn [[nurl sources]] {:normalized-url nurl
                                     :score
                                     (links/compute-seen-at-score sources)
                                     :seen-at        sources})
            nurl->seen-at)))
      (.catch (.-BulkError Dexie)
        (fn [e]
          (js/console.log e)
          (log/error "Couldn't add" (.. e -failures -length)
            "entries to urls for cache" cache-key ", e.g."
            (js->clj (aget (.-failures e) 0))))))
    (js/Promise.resolve)))

(defn update-link-cache
  "Downloads the given cache from `cache-url` and saves all the links from it
  to the DB. Returns the promise that resolves with nil once all the downloading
  and saving is finished."
  [cache-key cache-url]
  (|vv
    (. (js/fetch cache-url) then)
    (fn [response])
    (if-not (.-ok response)
      (do
        (backend/problem-getting-cache
          cache-key (str "status=" (.-status response)))
        (js/Promise.reject)))
    (let [reader         (.. response -body getReader)
          parser         (JSONParser.)
          field-names    (atom nil)
          links-buffer   (atom {})
          unpacked-count (atom 0)
          add-buffer-to-db
          (fn add-buffer-to-db []
            (let [[buffer _] (reset-vals! links-buffer {})]
              (if (seq buffer)
                ((fn process-buffer-parts [[keys-part & rest]]
                   (-> (get-updated-entries (select-keys buffer keys-part))
                     (.then #(save-links % cache-key))
                     (.then (fn [_]
                              (.. browser -storage -local
                                (set #js {:link-cache-status
                                          (str "Unpacked " @unpacked-count
                                            " link batches from " cache-key)}))))
                     (.then
                       (fn [] (when (seq rest) (process-buffer-parts rest))))
                     (.catch (fn [error]
                               (backend/problem-getting-cache
                                 cache-key
                                 (str "type=save-problem; "
                                   (.toString error)))
                               (js/Promise.reject error)))))
                 (partition-all 1000 (keys buffer)))
                (js/Promise.resolve))))]
      (|vv
        (set! (.-onValue parser))
        (fn [val])
        (this-as this)
        (let [path (mapv #(.-key %) (.-stack this))
              path (conj (if (seq path) (subvec path 1) []) (.-key this))])
        (cond (= path ["field-names"])
              (reset! field-names (mapv keyword (array-seq val)))

              (and (= (first path) "links") (= (count path) 2))
              (let [link (->> (vec val)
                           (map vector @field-names)
                           (filter second)
                           (into {}))]
                (swap! links-buffer assoc-in
                  [(:normalized-url link) (str (:id link))]
                  (dissoc link :normalized-url :id)))))
      ((fn ff []
         (.then (.read reader)
           (fn [res]
             (if (and (not (.-done res))
                   ;; Unpack at most a 1000 batches
                   (< @unpacked-count 1000))
               (.then (do (.write parser (.-value res))
                          (if (>= (count @links-buffer) 1000)
                            (do (swap! unpacked-count inc)
                                (add-buffer-to-db))
                            (js/Promise.resolve)))
                 ff)
               (add-buffer-to-db)))))))))

(defn- update-cache-with-next-diff
  "Finds the diff for the given `cache-key`, downloads and applies it.
  Returns nil if no update available, the new cache key otherwise.
  Doesn't touch the list of downloaded caches."
  [cache-key]
  (log/info "Checking for updates to the cache" cache-key)
  (-> (->>
        (assoc (backend/base-request-options)
          :params {:old-cache-key cache-key}
          :handler
          #(resolve (js->clj % :keywordize-keys true))
          :error-handler
          (fn [{:keys [status-text]}]
            (log/error "Couldn't download cache diff:"
              status-text)
            (resolve nil)))
        (POST (backend/endpoint "links/get-next-cache-diff"))
        (fn [resolve])
        (js/Promise.))
    (.then (fn [{:keys [new-key url]}]
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
  "Queries the server to get latest cache urls, downloads the caches,
  updates the links db and the cache info
  (last updated timestamp, previous versions)."
  []
  (-> (js/Promise.
        (fn [resolve]
          (GET (backend/endpoint "links/get-cache-urls")
            (assoc (backend/base-request-options)
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
                          (throw error)))
                      (.then #(iterate-over-response (rest server-caches))))
                    (iterate-over-response (rest server-caches))))))))))))
(def last-downloaded-timestamp (atom nil))

(defn update-friends-visits
  "Requests the latest pages amplified by friends, saves them to the links db."
  []
  (letfn [(get-page [until]
            (->>
              (GET (backend/endpoint "visits/get-friends-last-visits")
                (assoc (backend/base-request-options)
                  :params (when until {:until until})
                  :handler #(resolve (:visits (js->clj % :keywordize-keys true)))
                  :error-handler
                  #(reject (backend/error->map %))))
              (fn [resolve reject]) (js/Promise.)))

          (visit->local-link [{:keys [user-tag username reaction comment
                                      created-at]}]
            (merge
              {:source       "vf"
               :v-user-tag   user-tag
               :v-username   username
               :v-created-at created-at}
              (when reaction {:reaction reaction})
              (when (some? comment) {:has-comment true})))

          (page->local-format [page]
            (->> page
              (map #(vector (str (:link-id %)) (visit->local-link %)
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
                    (filter #(<= @last-downloaded-timestamp (:created-at %))
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
                              ;; The ids will be keywords, cast to str with name
                              (map (comp name first))
                              (into #{}))
                            ;; Don't recurse if we have one of the links
                            ;; in the cache already.
                            stop-download (some existing-ids
                                            ;; Link ids from the server are ints
                                            (map (comp str :link-id) page))]
                        ;; Stop if the page is too small or we reached
                        ;; a previously seen link. Curent logic will cause
                        ;; problems if there are hundreds visits per second though.
                        (if-not (or stop-download (< (count page) 100))
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

(def last-downloaded-vote-timestamp (atom nil))

(defn update-my-votes
  "Requests the latest pages the current user has voted on, saves them to the links db."
  []
  (letfn [(url-vote->local-link [{:url-vote/keys [comment upvote? created-at]}]
            (cond-> {:source     "+-"
                     :upvote?    upvote?
                     :created-at created-at}
              comment (assoc :has-comment true)))

          (page->local-format [page]
            (->> page
              (map #(vector (str (:link/id %)) (url-vote->local-link %)
                      (:link/normalized %)))
              (group-by last)
              (map (fn [[nurl seen-at]]
                     [nurl (->> (map pop seen-at) (into {}))]))
              (into {})))

          (load-next-page [page depth accum last-timestamp]
            (js/console.log "load-next-page" page)
            (let [latest-timestamp-on-page   (-> page first :url-vote/created-at)
                  earliest-timestamp-on-page (-> page last :url-vote/created-at)
                  filtered-page
                  (if @last-downloaded-vote-timestamp
                    (filter #(<= @last-downloaded-vote-timestamp (:url-vote/created-at %))
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
                              ;; The ids will be keywords, cast to str with name
                              (map (comp name first))
                              (into #{}))
                            ;; Don't recurse if we have one of the links
                            ;; in the cache already.
                            stop-download (some existing-ids
                                            ;; Link ids from the server are ints
                                            (map (comp str :link/id) page))]
                        ;; Stop if the page is too small or we reached
                        ;; a previously seen link. Curent logic will cause
                        ;; problems if there are hundreds votes per second though.
                        (if-not (or stop-download (< (count page) 100))
                          (-> (backend/get-my-last-url-votes earliest-timestamp-on-page)
                            (.then :url-votes)
                            (.then #(load-next-page % (inc depth)
                                      (merge-with merge accum
                                        page-in-local-format)
                                      (max last-timestamp
                                        latest-timestamp-on-page))))
                          [(merge-with merge accum page-in-local-format)
                           (max last-timestamp latest-timestamp-on-page)])))))
                [accum last-timestamp])))]
    (-> (backend/get-my-last-url-votes nil)
      ;; Just :url-votes wasn't working for some reason, the value was passing
      ;; through unchanged. Maybe because (fn? :url-votes) is false so
      ;; the js interop doesn't quite work?
      (.then #(:url-votes %))
      (.then #(load-next-page % 0 nil nil))
      (.then
        (fn [[links last-timestamp]]
          (if (seq links)
            (-> (get-updated-entries links)
              (.then save-links)
              (.then
                (fn []
                  (swap! last-downloaded-vote-timestamp
                    #(max (or % 0) last-timestamp)))))
            (log/info "No new URL votes")))))))

(defn load-demo-data! []
  (-> (get-updated-entries (demo/staas-links))
    (.then #(save-links % :demo-staas))
    (.then #(get-updated-entries (demo/invisible-asymptotes-links)))
    (.then #(save-links % :demo-invisible-asymptotes))))

#_(defstate link-cache-sync
    :start (letfn [(run-cache-check [time]
                     (when @@link-cache-sync (js/clearTimeout @@link-cache-sync))
                     (log/info "Running cache check...")
                     (-> (check-and-update-link-caches)
                       (.then
                         (fn []
                           (log/info "Cache check complete")
                           (log/info "Updating friends visits")
                           (update-friends-visits)))
                       (.then
                         (fn []
                           (log/info "Friends visits updated")
                           (log/info "Updating user's URL votes")
                           ;; TODO(page_votes)
                           #_(update-my-votes)))
                       (.finally
                         (fn [_]
                           (log/info "User's URL votes updated")
                           (let [timeout (js/setTimeout run-cache-check time time)]
                             (reset! @link-cache-sync timeout))))))]
             (load-demo-data!)
             (when-not false ;;goog.DEBUG
               (js/setTimeout
                 (fn [] (backend/on-logged-in :link-cache-sync
                          #(run-cache-check (* 10 60 1000))))
                 5000))
             (atom nil))
    :stop (do (when @@link-cache-sync
                (js/clearTimeout @@link-cache-sync)
                (reset! @link-cache-sync nil))
              (backend/remove-on-logged-in :link-cache-sync)))
