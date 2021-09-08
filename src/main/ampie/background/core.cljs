(ns ampie.background.core
  (:require [ampie.background.messaging :as background.messaging]
            [ampie.db :refer [db]]
            [ampie.macros :refer [then-fn]]
            [ampie.links]
            [ampie.settings]
            [ampie.background.backend]
            [ampie.background.link-cache-sync]
            [ampie.background.analytics]
            [ampie.tabs.monitoring :as tabs.monitoring]
            ["webextension-polyfill" :as browser]
            [mount.core :as mount :refer [defstate]]
            [clojure.string :as string]
            [taoensso.timbre :as log]))

(defonce active-tab-interval-id (atom nil))

(defn handle-shortcut [command]
  (case command
    "amplify_page"
    (background.messaging/amplify-current-tab)
    "open_page_context"
    (-> (.. browser -tabs (query #js {:active true :currentWindow true}))
      (.then #(js->clj % :keywordize-keys true))
      (.then (fn [[{url :url}]]
               (background.messaging/open-page-context {:url url} nil))))
    "upvote_page"
    (background.messaging/upvote-current-tab)
    "downvote_page"
    (background.messaging/downvote-current-tab)))

(defstate shortcut-handler
  :start (.. browser -commands -onCommand
           (addListener handle-shortcut))
  :stop (.. browser -commands -onCommand
          (removeListener handle-shortcut)))

(defn ^:dev/before-load remove-listeners []
  (log/info "Removing listeners")

  (mount/stop)

  (. js/window clearInterval @active-tab-interval-id)
  (reset! active-tab-interval-id nil)

  (tabs.monitoring/stop))


(defn ^:dev/after-load init []
  (mount/start)

  (.. browser -runtime -onInstalled
    (addListener
      (fn [^js details]
        (let [previous-version
              (->> (re-matches #"(\d+).(\d+)(?:.(\d+)(?:.(\d+))?)?"
                     (or (.-previousVersion details) "0.0.0.0"))
                rest
                (mapv (fnil js/parseInt "0")))]
          (js/console.log "Previuos ampie version "
            (string/join "." previous-version))
          (when (and (= (.-reason details) "update")
                  (neg? (compare previous-version [2 4 0 0])))
            (when-not goog.DEBUG
              (mount/stop)
              (-> (.. browser -storage -local (get "blacklisted-urls"))
                (then-fn [^js blacklisted-urls]
                  (-> (.. browser -storage -local (clear))
                    (then-fn []
                      (.. browser -storage -local
                        (set #js {"blacklisted-urls" blacklisted-urls})))))
                (then-fn [] (-> (.-links @db) (.clear)))
                (then-fn [] (mount/start))))))

        (when (or (= (.-reason details) "install")
                (and (= (.-reason details) "update")
                  (not (string/starts-with? (.-previousVersion details)
                         "2.4."))))
          (.. browser -tabs
            (create #js {:url (.. browser -runtime (getURL "update.html"))}))))))

  #_(.. browser -tabs
      (query #js {} process-already-open-tabs))

  (when (some? @active-tab-interval-id)
    (. js/window clearInterval @active-tab-interval-id)
    (reset! active-tab-interval-id nil))

  (reset! active-tab-interval-id
    (. js/window setInterval
      tabs.monitoring/check-active-tab
      1000))

  (tabs.monitoring/start))
