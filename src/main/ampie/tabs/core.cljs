(ns ampie.tabs.core
  (:require [ampie.visits :as visits]
            [ampie.visits.db :as visits.db]
            [ampie.db :refer [db]]
            [ampie.interop :as i]
            [taoensso.timbre :as log]
            [mount.core])
  (:require-macros [mount.core :refer [defstate]]))

(defstate open-tabs :start (atom {}))

(defn js-tab->clj [tab-info & {:keys [keep-obj-id]
                               :or   {keep-obj-id false}}]
  (-> (i/js->clj tab-info)
    (update :history-back reverse)
    (update :history-fwd reverse)
    (dissoc (when (not keep-obj-id)
              :obj-id))))

(defn add-new-visit-to-tab [tab-info visit-hash]
  (let [history     (:history-back tab-info)
        origin-hash (:origin-hash tab-info)
        new-history (conj history visit-hash)]
    {:visit-hash   visit-hash
     :origin-hash  origin-hash
     :history-back new-history
     :history-fwd  ()}))

(defn generate-new-tab
  ([visit-hash origin-hash]
   (generate-new-tab visit-hash origin-hash (list visit-hash) ()))
  ([visit-hash origin-hash history-back history-fwd]
   {:visit-hash   visit-hash
    :origin-hash  origin-hash
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
  "Looks at the current and previous visit in `tab-id`, chooses
  the one that matches the url, and updates its title to `title`.
  If neither of the two match, saves the title to `:stored-title`
  in the tab."
  [tab-id title url]
  (let [{[current-hash prev-hash] :history-back} (@@open-tabs tab-id)]
    (.then
      (js/Promise.all
        (if prev-hash
          (array
            (visits.db/get-visit-by-hash current-hash)
            (visits.db/get-visit-by-hash prev-hash))
          (array (visits.db/get-visit-by-hash current-hash))))
      (fn [results-js]
        (let [[{current-url :url} {prev-url :url}] (i/js->clj results-js)]
          (cond
            (= current-url url)
            (visits.db/set-visit-title! current-hash title)

            (= prev-url url)
            (visits.db/set-visit-title! prev-hash title)

            (contains? @@open-tabs tab-id)
            (swap! @open-tabs assoc-in [tab-id :stored-title]
              {:url   url
               :title title})

            :else
            (log/info "Got a title update event for a non-existent tab"
              tab-id)))))))

(defn open-tab! [tab-id tab-info]
  (swap! @open-tabs assoc tab-id tab-info))

(defn update-tab! [tab-id tab-info]
  (log/info "Updating tab" tab-id tab-info)
  (swap! @open-tabs assoc tab-id tab-info))

(defn close-tab! [tab-id]
  (when-let [tab-info (@@open-tabs tab-id)]
    (swap! @open-tabs dissoc tab-id)
    (log/info "Removing tab:" tab-info)
    (.transaction @db "rw" (.-closedTabs @db)
      (fn []
        (.. @db -closedTabs (add (i/clj->js tab-info)))))))

(defn maybe-restore-last-tab
  "Go through the last n closed tabs and see if there is one with
  the matching url. If there is, restore it to `open-tabs`
  and return true. Otherwise do nothing and return false."
  [tab-id url & {:keys [n] :or {n 1}}]
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
        (-> (get-last-n-closed-tabs)

          ;; Get the latest visit associated with these tabs that matches the url,
          ;; return that visit and the associated tab.
          (.then
            (fn [last-tabs]
              (.then
                (visits.db/get-first-visit-with-url (map :visit-hash last-tabs) url
                  :just-hash false)
                (fn [{visit-hash :visit-hash :as visit}]
                  [visit
                   (first (filter #(= (:visit-hash %) visit-hash)
                            last-tabs))]))))

          ;; Open the tab with that visit and remove it from the closed tabs store
          ;; Return whether there was a matching tab.
          (.then
            (fn [[visit {closed-tab-id :obj-id :as tab-info-with-id}]]
              (let [tab-info (dissoc tab-info-with-id :obj-id)]
                (when (some? visit)
                  (open-tab! tab-id tab-info)
                  (-> (.-closedTabs @db)
                    (.where "objId") (.equals closed-tab-id)
                    (.delete)))
                (some? visit)))))))))

(defn update-visit-url!
  "Function to be called when the visit url changes, probably because
  of redirect (otherwise create a new child visit!). Updates `open-tabs`
  and the `visits` db to reflect the new url, also sets the title if
  there is a title update pending in `stored-title` in the tab."
  [tab-id visit-hash new-url]
  (let [{:keys [stored-title] :as tab-info} (@@open-tabs tab-id)]
    (log/info tab-info)
    (log/info tab-id visit-hash stored-title)
    (visits.db/set-visit-url! visit-hash new-url)
    (when (= (:url stored-title) new-url)
      (visits.db/set-visit-title! visit-hash (:title stored-title)))
    (swap! @open-tabs update tab-id dissoc :stored-title)))
