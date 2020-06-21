(ns ampie.seen-urls
  (:require [ampie.db :refer [db]]
            [ampie.url :as url]
            [ampie.tabs.core :refer [open-tabs]]
            [ampie.visits.db :as visits.db]
            [ampie.interop :as i]
            [taoensso.timbre :as log]
            ["dexie" :default Dexie]))

(defn find-where-saw-nurls
  "Returns a Promise that resolves with a vector of vectors of visits,
  one vector for each given normalized url - visits during which saw that url."
  [normalized-urls]
  (log/trace "find-where-saw-nurls" normalized-urls)
  (-> (.-seenUrls @db)
    (.bulkGet (i/clj->js normalized-urls))
    (.then
      (fn [arr]
        (let [;; Vector of vectors of visit-hashes for those urls
              visit-hashes  (->> (i/js->clj arr)
                              (map (comp #(map first %) :seen-at)))
              merged-hashes (reduce #(into %1 %2) #{} visit-hashes)]
          (-> (.-visits @db)
            (.bulkGet (i/clj->js merged-hashes))
            (.then
              (fn [visits]
                (log/info "Got visits" (i/js->clj visits))
                (let [visits (reduce
                               #(assoc %1 (:visit-hash %2) %2)
                               {}
                               (i/js->clj visits))]
                  (map #(map visits %) visit-hashes))))))))))

(defn add-seen-nurls
  "Adds the normalized urls from the `normalized-urls` seq to the set of links
  seen at the given visit, the timestamp for the added links is set to timestamp."
  [normalized-urls visit-hash timestamp]
  (let [unique-urls (-> normalized-urls set seq)]
    (-> (.-seenUrls @db)
      (.bulkGet (i/clj->js unique-urls))
      (.then
        (fn [previously-seen]
          (let [previously-seen (map #(-> % (i/js->clj) :seen-at)
                                  previously-seen)
                ;; Function that adds the new visit hash to the saved ones,
                ;; truncating if there are more than 10 links.
                add-visit
                (fn [previous new]
                  ;; Don't insert the same visit twice
                  (when-not (some #(= (first new) (first %)) previous)
                    ;; Keep the number of sightings at 10
                    (if (>= (count previous) 10)
                      (subvec (conj (or previous []) new) 1)
                      (conj (or previous []) new))))

                new-seen-at (map add-visit
                              previously-seen
                              (repeat [visit-hash timestamp]))

                to-insert
                (->> (map #(hash-map :normalized-url %1 :seen-at %2)
                       unique-urls new-seen-at)
                  (filter :seen-at))]
            to-insert)))
      (.then
        (fn [to-insert]
          (-> (.-seenUrls @db)
            (.bulkPut (i/clj->js to-insert)))))
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
            "entries to seenUrls, e.g."
            (js->clj (aget (.-failures e) 0))))))))
