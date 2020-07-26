(ns ampie.background.core
  (:require [ampie.background.messaging :as background.messaging]
            [ampie.links]
            [ampie.background.backend]
            [ampie.background.link-cache-sync]
            [ampie.tabs.monitoring :as tabs.monitoring]
            ["webextension-polyfill" :as browser]
            [mount.core :as mount]
            [clojure.string :as string]
            [taoensso.timbre :as log]))

(defonce active-tab-interval-id (atom nil))

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
        (when (or (= (.-reason details) "install")
                (and (= (.-reason details) "update")
                  (string/starts-with? (.-previousVersion details) "1")))
          (.. browser -tabs
            (create #js {:url (.. browser -runtime (getURL "hello.html"))}))))))

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
