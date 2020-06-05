(ns ampie.seen-links
  (:require [ampie.db :refer [db]]
            [ampie.url :as url]
            [ampie.tabs.core :refer [open-tabs]]
            [ampie.visits.db :as visits.db]
            [taoensso.timbre :as log]
            ["dexie" :default Dexie]))

(defn find-where-saw-urls [urls]
  (->
    (.-seenLinks @db)
    (.where "childUrl")
    (.anyOf urls)
    (.and (fn [seen-link]
            (not=
              (url/get-domain (aget seen-link "childUrl"))
              (url/get-domain (aget seen-link "parentUrl")))))
    (.toArray
      (fn [arr]
        (->> (js->clj arr :keywordize-keys true)
          (reduce
            (fn [accum {:keys [childUrl parentUrl]}]
              (update accum childUrl
                #(conj (or % []) parentUrl)))
            {}))))))

;; Add the urls from the links seq to the set of links seen at the given url.
;; Succeeds only when the url in the tab with tab-id is page-url, otherwise
;; does nothing.
;; TODO: if the url doesn't match, search through tab history for it.
(defn add-seen-links [tab-id page-url links]
  (let [visit-hash      (-> tab-id (@@open-tabs) :visit-hash)
        unique-links    (-> links set seq)
        objects-to-save (mapv (fn [url] {:childUrl  url
                                         :parentUrl page-url})
                          unique-links)]
    (->
      (visits.db/get-visit-by-hash visit-hash)
      (.then
        (fn [{:keys [url]}]
          (when (= url page-url)
            (-> (.-seenLinks @db)
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
          (log/error "Didn't add" (.. e -failures -length)
            "entries to seenLinks"))))))
