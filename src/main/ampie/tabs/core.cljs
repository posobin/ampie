(ns ampie.tabs.core
  (:require [ampie.visits :as visits]
            [ampie.visits.db :as visits.db]
            [ampie.db :refer [db]]
            [taoensso.timbre :as log]
            [mount.core])
  (:require-macros [mount.core :refer [defstate]]))

(defstate open-tabs :start (atom {}))

(defn js-tab->clj [tab-info & {:keys [keep-obj-id]
                               :or   {keep-obj-id false}}]
  (-> (js->clj tab-info :keywordize-keys true)
    (update :history-back reverse)
    (update :history-fwd reverse)
    (dissoc (when (not keep-obj-id)
              :objId))))

(defn add-new-visit-to-tab [tab-info visit-hash]
  (let [history     (:history-back tab-info)
        new-history (conj history visit-hash)]
    {:visit-hash   visit-hash
     :history-back new-history
     :history-fwd  ()}))

(defn generate-new-tab
  ([visit-hash] (generate-new-tab visit-hash (list visit-hash) ()))
  ([visit-hash history-back history-fwd]
   {:visit-hash   visit-hash
    :history-back history-back
    :history-fwd  history-fwd}))

(defn tab-in-focus! [tab-id]
  (visits/visit-in-focus! (:visit-hash (@@open-tabs tab-id))))

(defn no-tab-in-focus! [] (tab-in-focus! nil))

;; TODO accept the url and change the corresponding visit, since
;; the events may come in a different order the current visit in
;; @open-tabs may be different from the one at which the update
;; is targeted.
(defn update-tab-title
  "Changes the title of the currently active visit in the tab-id"
  ([tab-id title url] (update-tab-title tab-id title url false))
  ([tab-id title url second-time?]
   (let [{[current-hash prev-hash] :history-back} (@@open-tabs tab-id)]
     (.then
       (.all
         js/Promise
         [(visits.db/get-visit-by-hash current-hash)
          (visits.db/get-visit-by-hash prev-hash)])
       (fn [results-js]
         (let [[{current-url :url} {prev-url :url}] (js->clj results-js)]
           (cond
             (= current-url url)
             (visits.db/set-visit-title! current-hash title)

             (= prev-url url)
             (visits.db/set-visit-title! prev-hash title)

             #_ (not second-time?)
             #_ (js/setTimeout (fn [] (update-tab-title tab-id title url true))
                  500)

             (contains? @@open-tabs tab-id)
             (swap! @open-tabs assoc-in [tab-id :stored-title]
               {:url   url
                :title title})

             :else
             (log/error "Got a title update event for a non-existent tab"
               tab-id))))))))

(defn open-tab! [tab-id tab-info]
  (swap! @open-tabs assoc tab-id tab-info))

(defn update-tab! [tab-id tab-info]
  (swap! @open-tabs assoc tab-id tab-info))

(defn close-tab! [tab-id]
  (when-let [tab-info (@@open-tabs tab-id)]
    (swap! @open-tabs dissoc tab-id)
    (log/info "Removing tab:" tab-info)
    (.transaction @db "rw" (.-closedTabs @db)
      (fn []
        (.. @db -closedTabs (add (clj->js tab-info)))))))

;; Go through the last n closed tabs and see if there is one with the matching url.
;; If there is, restore it and return true. Otherwise do nothing and return false.
(defn maybe-restore-last-tab [tab-id url & {:keys [n]
                                            :or   {n 1}}]
  (letfn [(get-last-n-closed-tabs []
            (-> (.-closedTabs @db)
              (.reverse)
              (.limit n)
              (.toArray
                (fn [array]
                  (map #(js-tab->clj % :keep-obj-id true)
                    array)))))]
    (.transaction
      @db "rw" (.-visits @db) (.-closedTabs @db)
      (fn []
        (->
          (get-last-n-closed-tabs)

          ;; Get the latest visit associated with these tabs that matches the url,
          ;; return that visit and the associated tab.
          (.then
            (fn [last-tabs]
              (.then
                (visits.db/get-first-visit-with-url (map :visit-hash last-tabs) url
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
                  (-> (.-closedTabs @db)
                    (.where "objId") (.equals closed-tab-id)
                    (.delete)))
                (some? visit)))))))))
