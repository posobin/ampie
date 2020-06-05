(ns ampie.seen-links
  (:require [ampie.db :refer [db]]
            [ampie.url :as url]
            [ampie.tabs.core :refer [open-tabs]]
            [ampie.visits.db :as visits.db]
            [taoensso.timbre :as log]
            ["dexie" :default Dexie]))

(defn find-where-saw-urls
  "Returns a Promise that resolves with a vector of vectors of visits,
  one vector for each given url - visits during which saw that url."
  [urls]
  (log/trace "find-where-saw-urls" urls)
  (-> (.-seenLinks @db)
    (.bulkGet (clj->js urls))
    (.then
      (fn [arr]
        (let [;; Vector of vectors of visit-hashes for those urls
              visit-hashes  (->> (js->clj arr :keywordize-keys true)
                              (map (comp #(map first %) :seenAt)))
              merged-hashes (reduce #(into %1 %2) #{} visit-hashes)]
          (-> (.-visits @db)
            (.bulkGet (clj->js merged-hashes))
            (.then
              (fn [visits]
                (log/info "Got visits" (js->clj visits :keywordize-keys true))
                (let [visits (reduce
                               #(assoc %1 (:visitHash %2) %2)
                               {}
                               (js->clj visits :keywordize-keys true))]
                  (map #(map visits %) visit-hashes))))))))))

(defn add-seen-links
  "Adds the urls from the urls seq to the set of links seen at the given visit,
  the timestamp for the added links is set to timestamp."
  [urls visit-hash timestamp]
  (let [unique-urls (-> urls set seq)]
    (-> (.-seenLinks @db)
      (.bulkGet (clj->js unique-urls))
      (.then
        (fn [previously-seen]
          (let [previously-seen
                (map #(-> % (js->clj :keywordize-keys true) :seenAt)
                  previously-seen)
                ;; Function that adds the new visit hash to the saved ones,
                ;; truncating if there are more than 10 links.
                add-visit
                (fn [previous new]
                  ;; Don't insert the same visit twice
                  (if (some #(= (:first new) (:first %)) previous)
                    previous
                    ;; Keep the number of sightings at 10
                    (if (>= (count previous) 10)
                      (subvec (conj (or previous []) new) 1)
                      (conj (or previous []) new))))

                new-seen-at
                (map add-visit
                  (js->clj previously-seen)
                  (repeat [visit-hash timestamp]))

                to-insert (map #(hash-map :url %1 :seenAt %2)
                            unique-urls new-seen-at)]
            (-> (.-seenLinks @db)
              (.bulkPut (clj->js to-insert))))))
      ;; Need to catch to put at least some of the links into DB.
      ;; (Dexie will commit after exception if BulkError is caught)
      ;; Though don't know why this would happen, it doesn't throw on
      ;; the same key being already present in the DB. Maybe should
      ;; remove it and let the error bubble up? I need a global handler
      ;; then.
      (.catch
        (.-BulkError Dexie)
        (fn [e]
          (log/error "Couldn't add" (.. e -failures -length)
            "entries to seenLinks"))))))
