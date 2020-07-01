(ns ampie.visits
  (:require ["dexie" :default Dexie]
            [ampie.db :refer [db]]
            [ampie.url :as url]
            [ampie.visits.db :as visits.db]
            [taoensso.timbre :as log]))

;; Stores a map for the current visit: the active visit hash and
;; since when the user has been on that page.
;; {:visit-hash :start-time}
(defonce current-visit-info (atom nil))

;; TODO choose hash function, prepend timestamp in front of the hash
(defn generate-visit-hash [visit]
  (let [{:keys [first-opened url parent]} visit]
    (hash [first-opened url parent])))

(defn evt->visit
  ([evt] (evt->visit evt nil nil))
  ([evt parent-hash origin-hash]
   (assert (or (some? origin-hash) (nil? parent-hash)))
   (let [result
         {:url            (:url evt)
          :normalized-url (:normalized-url evt)
          :first-opened   (:time-stamp evt)
          :title          (:title evt)
          :time-spent     0
          :children       []
          :parent         parent-hash
          :origin         origin-hash}
         hash      (generate-visit-hash result)
         with-hash (assoc result :visit-hash hash)]
     (if origin-hash
       (assoc with-hash :origin origin-hash)
       (assoc with-hash :origin hash)))))

(defn visit-in-focus! [new-visit-hash]
  ;; Not worrying about race conditions for now, they may only affect stuff for only
  ;; a couple of seconds.
  (let [{:keys [visit-hash start-time]} @current-visit-info
        time-delta                      (-> (- (.getTime (js/Date.))
                                              start-time)
                                          (/ 1000)
                                          (int))]
    (when (not= new-visit-hash visit-hash)
      (log/trace "Switched visit" new-visit-hash "from" visit-hash)
      (when (some? visit-hash)
        (visits.db/increase-visit-time! visit-hash time-delta))
      (reset! current-visit-info
        {:visit-hash new-visit-hash
         :start-time (.getTime (js/Date.))}))))
