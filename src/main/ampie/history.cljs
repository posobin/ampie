(ns ampie.history
  (:require ["dexie" :default Dexie]))

(def open-tabs (atom {}))
(def closed-tabs (atom ()))
(def visits (atom {}))
(def visited-urls (atom {}))
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

(defn generate-visit-hash [visit]
  (let [{time-stamp :first-opened
         url        :url
         parent     :parent} visit]
    (hash [time-stamp url parent])))

(defn get-visit-by-hash [visit-hash]
  (if (nil? visit-hash)
    (js/Promise.resolve nil)
    (-> (.-visits db)
        (.get visit-hash)
        (.then js-visit->clj))))

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
        (.. db -visits (add (clj->js visit-with-hash))))))
  (when (some? (:parent visit))
    (swap! visits update-in [(:parent visit) :children]
           #(conj % visit-hash)))
  (swap! visits assoc visit-hash visit)
  (swap! visited-urls update (:url visit)
         (fn [visits] (conj (or visits []) visit-hash))))

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
                         array)))))

          (get-last-visit-with-url [visit-hashes]
            (.then
              (-> (.-visits db)
                  (.where "visitHash")
                  (.anyOf (clj->js visit-hashes))
                  (.and #(= (.-url %) url))
                  ;; Reverse comes before sortBy in Dexie
                  (.reverse) (.sortBy "first-opened"))
              #(-> (aget % 0)
                   (js-visit->clj :keep-visit-hash true))))]
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
                (get-last-visit-with-url (map :visit-hash last-tabs))
                (fn [{visit-hash :visitHash :as visit}]
                  (println "last tabs:" last-tabs)
                  (println "found visit:" visit)
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

(defn evt->visit
  ([evt] (evt->visit evt nil))
  ([evt parent-hash]
   {:url          (:url evt)
    :first-opened (:timeStamp evt)
    :time-spent   0
    :children     []
    :parent       parent-hash}))

(defn generate-new-tab
  ([visit-hash] (generate-new-tab visit-hash (list visit-hash) ()))
  ([visit-hash history-back history-fwd]
   {:visit-hash   visit-hash
    :history-back history-back
    :history-fwd  history-fwd}))

(defn add-new-visit-to-tab [tab-info visit-hash]
  (let [history (:history-back tab-info)
        new-history (conj history visit-hash)]
    {:visit-hash   visit-hash
     :history-back new-history
     :history-fwd  ()}))
