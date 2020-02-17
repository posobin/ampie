(ns ampie.history
  (:require ["dexie" :default Dexie]))

;; Using defonce so it is preserved on hot reloads of ampie
(defonce open-tabs (atom {}))
;; Stores a map for the current visit: the active visit hash and
;; since when the user has been on that page.
;; {:visit-hash :start-time}
(defonce current-visit-info (atom nil))
(def db nil (Dexie. "AmpieDB"))

;; Functions for managing visits and tabs

(defn js-tab->clj [tab-info & {:keys [keep-obj-id]
                               :or   {keep-obj-id false}}]
  (-> (js->clj tab-info :keywordize-keys true)
      (update :history-back reverse)
      (update :history-fwd reverse)
      (dissoc (when (not keep-obj-id)
                :objId))))

(defn js-visit->clj [visit & {:keys [keep-visit-hash]
                              :or   {keep-visit-hash false}}]
  (-> (js->clj visit :keywordize-keys true)
      (dissoc (when (not keep-visit-hash)
                :visitHash))))

;; TODO choose hash function, prepend timestamp in front of the hash
(defn generate-visit-hash [visit]
  (let [{time-stamp :firstOpened
         url        :url
         parent     :parent} visit]
    (hash [time-stamp url parent])))

(defn get-visit-by-hash [visit-hash & {:keys [keep-visit-hash]
                                       :or   {keep-visit-hash false}}]
  (if (nil? visit-hash)
    (js/Promise.resolve nil)
    (-> (.-visits db)
        (.get visit-hash)
        (.then #(js-visit->clj % :keep-visit-hash keep-visit-hash)))))

(defn add-new-visit! [visit-hash visit]
  (.transaction
    db "rw" (.-visits db)
    (fn []
      ;; Update parent's children list
      (when (some? (:parent visit))
        (-> (.-visits db)
            (.where "visitHash")
            (.equals (:parent visit))
            (.modify (fn [parent-visit]
                       (set! (.-children parent-visit)
                             (-> ((js->clj parent-visit) "children")
                                 (conj visit-hash)
                                 clj->js))))))
      ;; Add the new visit to visits
      (let [visit-with-hash (assoc visit :visitHash visit-hash)]
        (.. db -visits (add (clj->js visit-with-hash)))))))

(defn open-tab! [tab-id tab-info]
  (swap! open-tabs assoc tab-id tab-info))

(defn update-tab! [tab-id tab-info]
  (swap! open-tabs assoc tab-id tab-info))

(defn close-tab! [tab-id]
  (let [tab-info (@open-tabs tab-id)]
    (when tab-info
      (swap! open-tabs dissoc tab-id)
      (.transaction db "rw" (.-closedTabs db)
                    (fn []
                      (.. db -closedTabs (add (clj->js tab-info))))))))

;; Returns a clojure sequence of visits corresponding to visit-hashes.
;; Done in one request to the database.
(defn get-visits-info [visit-hashes]
  (-> (.-visits db)
      (.where "visitHash")
      (.anyOf (clj->js visit-hashes))
      (.toArray
        (fn [js-visits]
          (->> (js->clj js-visits :keywordize-keys true)
               (map (fn [visit] [(:visitHash visit)
                                 (dissoc visit :visitHash)]))
               (into {}))))
      (.then #(map % visit-hashes))))

;; Returns a Promise that resolves to a visit object with the first hash
;; in the list that has the given url. If no visit matches, resolves to nil.
(defn get-first-visit-with-url [visit-hashes url & {:keys [just-hash]
                                                    :or   {just-hash true}}]
  (->
    (-> (.-visits db)
        (.where "visitHash")
        (.anyOf (clj->js visit-hashes))
        (.and #(= (.-url %) url))
        (.toArray))
    (.then
      #(->> (js->clj % :keywordize-keys true)
            (map (fn [visit] [(:visitHash visit) visit]))
            (into {})))
    (.then
      (fn [hash-to-visit]
        (-> (filter #(contains? hash-to-visit %) visit-hashes)
            first
            ((if just-hash identity hash-to-visit)))))))

;; Go through the last n closed tabs and see if there is one with the matching url.
;; If there is, restore it and return true. Otherwise do nothing and return false.
(defn maybe-restore-last-tab [tab-id url & {:keys [n]
                                            :or   {n 1}}]
  (letfn [(get-last-n-closed-tabs []
            (-> (.-closedTabs db)
                (.reverse)
                (.limit n)
                (.toArray
                  (fn [array]
                    (map #(js-tab->clj % :keep-obj-id true)
                         array)))))]
    (.transaction
      db "rw" (.-visits db) (.-closedTabs db)
      (fn []
        (->
          (get-last-n-closed-tabs)

          ;; Get the latest visit associated with these tabs that matches the url,
          ;; return that visit and the associated tab.
          (.then
            (fn [last-tabs]
              (.then
                (get-first-visit-with-url (map :visit-hash last-tabs) url
                                          :just-hash false)
                (fn [{visit-hash :visitHash :as visit}]
                  [visit
                   (first (filter #(= (:visit-hash %) visit-hash)
                                  last-tabs))]))))

          ;; Open the tab with that visit and remove it from the closed tabs store
          ;; Return whether there was a matching tab.
          (.then
            (fn [[visit {closed-tab-id :objId :as tab-info-with-id}]]
              (let [tab-info (dissoc tab-info-with-id :objId)]
                (when (some? visit)
                  (open-tab! tab-id tab-info)
                  (-> (.-closedTabs db)
                      (.where "objId") (.equals closed-tab-id)
                      (.delete)))
                (some? visit)))))))))

(defn get-last-n-root-visits
  "Returns the last n root visits added to the database"
  [n]
  (->
    (.-visits db)
    (.orderBy "firstOpened")
    (.reverse)
    (.filter #(nil? (aget % "parent")))
    (.limit n)
    (.toArray (fn [array] (map js-visit->clj (array-seq array))))))

;; Returns a js promise that resolves with the clojure list of
;; visit hashes of length at most n, identifying the last n visits
;; to the given url.
(defn get-past-visits-to-the-url [url n]
  (->
    (.-visits db)
    (.where "url")
    (.equals url)
    (.reverse)
    (.limit n)
    (.toArray)))

(defn evt->visit
  ([evt] (evt->visit evt nil))
  ([evt parent-hash]
   {:url         (:url evt)
    :firstOpened (:timeStamp evt)
    :timeSpent   0
    :children    []
    :parent      parent-hash}))

(defn generate-new-tab
  ([visit-hash] (generate-new-tab visit-hash (list visit-hash) ()))
  ([visit-hash history-back history-fwd]
   {:visit-hash   visit-hash
    :history-back history-back
    :history-fwd  history-fwd}))

(defn add-new-visit-to-tab [tab-info visit-hash]
  (let [history     (:history-back tab-info)
        new-history (conj history visit-hash)]
    {:visit-hash   visit-hash
     :history-back new-history
     :history-fwd  ()}))

;; Update the entry for the given visit in the db by incrementing
;; its timeSpent by time-delta.
(defn increase-visit-time! [visit-hash time-delta]
  (->
    (.-visits db)
    (.where "visitHash")
    (.equals visit-hash)
    (.modify
      (fn [visit]
        (let [current-time (aget visit "timeSpent")]
          (aset visit "timeSpent" (+ current-time time-delta)))))))

(defn tab-in-focus [tab-id]
  ;; Not worrying about race conditions for now, they may only affect stuff for only
  ;; a couple of seconds.
  (let [{:keys [visit-hash start-time]} @current-visit-info
        time-delta     (-> (- (.getTime (js/Date.))
                              start-time)
                           (/ 1000)
                           (int))
        new-visit-hash (:visit-hash (@open-tabs tab-id))]
    (when (not= new-visit-hash visit-hash)
      (println "Switched visit" new-visit-hash "from" visit-hash)
      (when (some? visit-hash)
        (increase-visit-time! visit-hash time-delta))
      (reset! current-visit-info
              {:visit-hash new-visit-hash
               :start-time (.getTime (js/Date.))}))))

(defn no-tab-in-focus [] (tab-in-focus nil))

;; Add the urls from the links seq to the set of links seen at the given url.
;; Succeeds only when the url in the tab with tab-id is page-url, otherwise
;; does nothing.
;; TODO: if the url doesn't match, search through tab history for it.
(defn add-seen-links [tab-id page-url links]
  (let [visit-hash      (-> tab-id (@open-tabs) :visit-hash)
        unique-links    (-> links set seq)
        objects-to-save (mapv (fn [url] {:childUrl  url
                                         :parentUrl page-url})
                              unique-links)]
    (->
      (get-visit-by-hash visit-hash)
      (.then
        (fn [{:keys [url]}]
          (when (= url page-url)
            (-> (.-seenLinks db)
                (.bulkPut (clj->js objects-to-save))))))
      ;; Need to catch to put at least some of the links into DB.
      ;; (Dexie will commit after exception if BulkError is caught)
      ;; Though don't know why this would happen, it doesn't throw on
      ;; the same key being already present in the DB. Maybe should
      ;; remove it and let the error bubble up? I need a global handler
      ;; then.
      (.catch
        (.-BulkError Dexie)
        (fn [e]
          (println "Didn't add" (.. e -failures -length)
                   "entries to seenLinks"))))))

(defn find-where-saw-urls [urls]
  (->
    (.-seenLinks db)
    (.where "childUrl")
    (.anyOf urls)
    (.toArray
      (fn [arr]
        (->> (js->clj arr :keywordize-keys true)
             (reduce
               (fn [accum {:keys [childUrl parentUrl]}]
                 (update accum childUrl
                         #(conj (or % []) parentUrl)))
               {}))))))

(defn init-db []
  (-> (. db (version 1))
      (. stores
         #js {:visits     "&visitHash, url, firstOpened"
              :closedTabs "++objId"
              :seenLinks  "&[parentUrl+childUrl],childUrl"})))