(ns ampie.visits.db
  (:require [ampie.db :refer [db]]
            [taoensso.timbre :as log]
            [ampie.url :as url]
            [ampie.interop :as i]))

(defn js-visit->clj [visit & {:keys [keep-visit-hash]
                              :or   {keep-visit-hash false}}]
  (-> (i/js->clj visit)
    (dissoc (when (not keep-visit-hash)
              :visit-hash))))

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
          (.modify (fn [^js parent-visit]
                     (set! (.-children parent-visit)
                       (-> (i/js->clj parent-visit)
                         :children
                         (conj visit-hash)
                         i/clj->js))))))
      ;; Add the new visit to visits
      (let [visit-with-hash (assoc visit :visit-hash visit-hash)]
        (.. @db -visits (add (i/clj->js visit-with-hash)))))))

(defn get-visits-info
  "Returns a clojure sequence of visits corresponding to visit-hashes.
  Done in one request to the database."
  [visit-hashes]
  (-> (.-visits @db)
    (.where "visitHash")
    (.anyOf (i/clj->js visit-hashes))
    (.toArray
      (fn [js-visits]
        (->> (i/js->clj js-visits)
          (map (fn [visit] [(:visit-hash visit) visit]))
          (into {}))))
    (.then #(mapv % visit-hashes))))

(defn get-first-visit-with-url
  "Returns a Promise that resolves to a visit object with the first hash
  in the list that has the given url. If no visit matches, resolves to nil."
  [visit-hashes url & {:keys [just-hash]
                       :or   {just-hash true}}]
  (->
    (-> (.-visits @db)
      (.where "visitHash")
      (.anyOf (i/clj->js visit-hashes))
      (.and #(= (.-url %) url))
      (.toArray))
    (.then
      #(->> (i/js->clj %)
         (map (fn [visit] [(:visit-hash visit) visit]))
         (into {})))
    (.then
      (fn [hash-to-visit]
        (-> (filter #(contains? hash-to-visit %) visit-hashes)
          first
          ((if just-hash identity hash-to-visit)))))))

(defn get-last-n-origin-visits
  "Finds the n visits with most recent child visit before the
  `before` timestamp and that don't appear in the `previously-seen`
  set of visit hashes, returns a map {:origin-visits :last-timestamp},
  where origin-visits are hydrated visits and last-timestamp is the timestamp
  on the last visit accessed (that visit may not have been an origin visit.).
  `before` may be nil."
  ([n] (get-last-n-origin-visits n nil nil))
  ([n before previously-seen]
   (let [found-origins-list (atom [])
         last-timestamp     (atom nil)
         found-origins-set  (atom #{})]
     (-> (.-visits @db)
       (.where "firstOpened")
       (.below (or before ##Inf))
       ;; Relying on the output of `below` to be sorted.
       (.reverse)
       (.until (fn [_] (>= (count @found-origins-list) n)))
       (.each (fn [visit]
                (let [{:keys [origin first-opened]} (js-visit->clj visit)]
                  (when-not (or (contains? @found-origins-set origin)
                              (contains? previously-seen origin))
                    (swap! found-origins-set conj origin)
                    (swap! found-origins-list conj origin)
                    (reset! last-timestamp first-opened)))))
       (.then #(get-visits-info @found-origins-list))
       (.then (fn [result] {:origin-visits  result
                            :last-timestamp @last-timestamp}))))))

(defn get-past-visits-to-the-url
  "Returns a js promise that resolves with the clojure list of
  visit hashes of length at most n, identifying the last n visits
  to the given url."
  [url n]
  (-> (.-visits @db)
    (.where "url")
    (.equals url)
    (.reverse)
    (.limit n)
    (.toArray i/js->clj)))

(defn get-past-visits-to-the-nurl
  "Returns a js promise that resolves with the clojure list of
  visit hashes of length at most n, identifying the last n visits
  to the given normalized url."
  [nurl n]
  (-> (.-visits @db)
    (.where "normalizedUrl")
    (.equals nurl)
    (.reverse)
    (.limit n)
    (.toArray i/js->clj)))

(defn increase-visit-time!
  "Update the entry for the given visit in the @db by incrementing
  its timeSpent by time-delta. "
  [visit-hash time-delta]
  (-> (.-visits @db)
    (.where "visitHash")
    (.equals visit-hash)
    (.modify
      (fn [^js visit]
        (let [current-time (aget visit "timeSpent")]
          (set! (.-timeSpent visit) (+ current-time time-delta)))))))

(defn set-visit-title! [visit-hash title]
  (-> (.-visits @db)
    (.update visit-hash #js {:title title})))

(defn set-visit-url! [visit-hash url]
  (-> (.-visits @db)
    (.update visit-hash
      #js {:url           url
           :normalizedUrl (url/normalize url)})))

(defn delete-visit-keeping-children!
  "Deletes the given visit (not visit hash!) from the db
  and adjusts the parent visit if necessary. Doesn't modify the
  children visits or the seen urls table."
  [{:keys [parent visit-hash] :as visit}]
  (-> (.-visits @db)
    (.where "visitHash")
    (.equals parent)
    (.modify
      (fn [^js parent-visit]
        (set! (.-children parent-visit)
          (->> (.-children parent-visit)
            js->clj
            (filterv #(not= % visit-hash))
            clj->js)))))
  (-> (.-visits @db)
    (.delete visit-hash)))

(defn get-long-visits-since [timestamp min-duration]
  (-> (.-visits @db)
    (.where "firstOpened")
    (.above timestamp)
    (.filter #(>= (.-timeSpent ^js %) min-duration))
    (.reverse)
    (.sortBy "timeSpent")
    (.then i/js->clj)))

(defn get-visits-with-nprefix [nprefix limit]
  (-> (.-visits @db)
    (.where "normalizedUrl")
    (.startsWith nprefix)
    (.limit limit)
    (.toArray i/js->clj)))
