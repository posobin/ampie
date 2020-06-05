(ns ampie.background.core
  (:require [ampie.background.messaging :as background.messaging]
            [ampie.tabs.monitoring :as tabs.monitoring]
            ["webextension-polyfill" :as browser]
            [mount.core :as mount]
            [taoensso.timbre :as log]))

(defonce active-tab-interval-id (atom nil))

(defn ^:dev/before-load remove-listeners []
  (log/info "Removing listeners")

  (mount/stop)

  (. js/window clearInterval @active-tab-interval-id)
  (reset! active-tab-interval-id nil)

  (tabs.monitoring/stop)
  (background.messaging/stop))


(defn ^:dev/after-load init []
  (log/info "Hello from the background world!")
  (mount/start)

  #_(.. browser -tabs (create #js {:url "history.html"}))

  #_(.. browser -tabs
      (query #js {} process-already-open-tabs))

  (when (some? @active-tab-interval-id)
    (. js/window clearInterval @active-tab-interval-id)
    (reset! active-tab-interval-id nil))

  (reset! active-tab-interval-id
    (. js/window setInterval
      tabs.monitoring/check-active-tab
      1000))

  (tabs.monitoring/start)
  (background.messaging/start))
