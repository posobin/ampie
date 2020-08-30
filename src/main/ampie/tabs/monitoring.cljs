(ns ampie.tabs.monitoring
  "Keeps track of all the tabs and transitions and
  saves them to indexeddb."
  (:require ["webextension-polyfill" :as browser]
            [ampie.tabs.core :as tabs :refer [open-tabs]]
            [ampie.visits :as visits]
            [ampie.visits.db :as visits.db]
            [ampie.tabs.event-handlers :as evt]
            [ampie.interop :as i]
            [ampie.url :as url]
            [mount.core])
  (:require-macros [mount.core :refer [defstate]]))

(defn check-active-tab []
  (-> (.. browser -windows (getLastFocused #js {:populate true}))
    (.then
      (fn [window]
        (let [{tabs-list :tabs focused :focused :as window}
              (i/js->clj window)
              {tab-id :id} (first (filter :active tabs-list))]
          (if focused
            (tabs/tab-in-focus! tab-id)
            (tabs/no-tab-in-focus!)))))))

(defn process-already-open-tabs [tabs]
  (let [tabs (i/js->clj tabs)]
    (doseq [{:keys [id url title] :as tab} tabs]
      (let [evt {:tab-id     id
                 :url        (url/normalize url)
                 :title      title
                 :time-stamp (.getTime (js/Date.))}]
        (if (contains? @@open-tabs id)
          (evt/went-back-or-fwd-in-tab evt)
          (evt/restored-tab evt))))))

(defn update-all-tab-titles []
  (.then
    (.. browser -tabs (query #js {}))
    (fn [tabs]
      (let [tabs          (->> (i/js->clj tabs)
                            (group-by :id))
            open-tabs     @@open-tabs
            latest-titles (for [[tab-id {visit-hash :visit-hash}]
                                open-tabs
                                :let  [{:keys [title url]} (first (tabs tab-id))]
                                :when title]
                            {:visit-hash visit-hash :title title :url url})]
        (.then (visits.db/get-visits-info (map :visit-hash latest-titles))
          (fn [visits]
            (doseq [[{current-url   :url
                      current-title :title
                      visit-hash    :visit-hash}
                     {url :url title :title}] (map vector visits latest-titles)
                    :when
                    (and visit-hash
                      (not (= current-title title))
                      (= (url/remove-anchor current-url) (url/remove-anchor url)))]
              (visits.db/set-visit-title! visit-hash title))))))))

(defn stop []
  (.. browser -tabs -onUpdated
    (removeListener evt/tab-updated))
  (.. browser -windows -onFocusChanged
    (removeListener evt/window-focus-changed))
  (.. browser -tabs -onRemoved
    (removeListener evt/tab-removed))
  (.. browser -webNavigation -onHistoryStateUpdated
    (removeListener evt/history-state-updated))
  (.. browser -webNavigation -onCommitted
    (removeListener evt/committed))
  (.. browser -webNavigation -onCreatedNavigationTarget
    (removeListener evt/created-navigation-target)))

(defn start []
  (.. browser -tabs -onUpdated
    (addListener evt/tab-updated))
  #_(.. browser -windows -onFocusChanged
      (addListener evt/window-focus-changed))
  #_(.. browser -tabs -onRemoved
      (addListener evt/tab-removed))
  #_(.. browser -webNavigation -onHistoryStateUpdated
      (addListener evt/history-state-updated))
  #_(.. browser -webNavigation -onCommitted
      (addListener evt/committed))
  #_(.. browser -webNavigation -onCreatedNavigationTarget
      (addListener evt/created-navigation-target))
  #_(js/setInterval update-all-tab-titles 1000))

(defstate service
  :start (start)
  :stop (do (js/clearInterval @service)
            (stop)))
