(ns ampie.visits.db
  (:require [ampie.db :refer [db]]
            [taoensso.timbre :as log]))

(defn js-visit->clj [visit & {:keys [keep-visit-hash]
                              :or   {keep-visit-hash false}}]
  (-> (js->clj visit :keywordize-keys true)
    (dissoc (when (not keep-visit-hash)
              :visitHash))))

(defn get-visit-by-hash [visit-hash & {:keys [keep-visit-hash]
                                       :or   {keep-visit-hash false}}]
  (if (nil? visit-hash)
    (js/Promise.resolve nil)
    (-> (.-visits @db)
      (.get visit-hash)
      (.then #(js-visit->clj % :keep-visit-hash keep-visit-hash)))))

(defn add-new-visit! [visit-hash visit]
  (.transaction
    @db "rw" (.-visits @db)
    (fn []
      ;; Update parent's children list
      (when (some? (:parent visit))
        (-> (.-visits @db)
          (.where "visitHash")
          (.equals (:parent visit))
          (.modify (fn [parent-visit]
                     (set! (.-children parent-visit)
                       (-> ((js->clj parent-visit) "children")
                         (conj visit-hash)
                         clj->js))))))
      ;; Add the new visit to visits
      (let [visit-with-hash (assoc visit :visitHash visit-hash)]
        (.. @db -visits (add (clj->js visit-with-hash)))))))

;; Returns a clojure sequence of visits corresponding to visit-hashes.
;; Done in one request to the database.
(defn get-visits-info [visit-hashes]
  (-> (.-visits @db)
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
    (-> (.-visits @db)
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

(defn get-last-n-root-visits
  "Returns the last n root visits added to the database"
  [n]
  (->
    (.-visits @db)
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
    (.-visits @db)
    (.where "url")
    (.equals url)
    (.reverse)
    (.limit n)
    (.toArray)))

;; Update the entry for the given visit in the @db by incrementing
;; its timeSpent by time-delta.
(defn increase-visit-time! [visit-hash time-delta]
  (->
    (.-visits @db)
    (.where "visitHash")
    (.equals visit-hash)
    (.modify
      (fn [visit]
        (let [current-time (aget visit "timeSpent")]
          (aset visit "timeSpent" (+ current-time time-delta)))))))

(defn set-visit-title! [visit-hash title]
  (->
    (.-visits @db)
    (.update visit-hash #js {:title title})))
