(ns ampie.content-script.content
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [ampie.interop :as i]
            [ampie.content-script.badge :as badge]
            [taoensso.timbre :as log]
            [ampie.content-script.info-bar :refer [info-bar-state]]
            [ampie.content-script.amplify :refer [amplify]]
            ["webextension-polyfill" :as browser]
            [mount.core :as mount :refer [defstate]]))

(defn message-listener [message]
  (let [message (js->clj message :keywordize-keys true)]
    (case (keyword (:type message))
      :url-updated  (do (mount/stop) (mount/start))
      :amplify-page ((:amplify-page @amplify))
      (log/error "Unknown message type" message))))

(defstate page-service
  :start (do (.. browser -runtime -onMessage (addListener message-listener))
             (assoc (badge/start (:show-info @info-bar-state))
               :reset-page (:reset-page @info-bar-state)))
  :stop (do (.. browser -runtime -onMessage (removeListener message-listener))
            (badge/stop @page-service)))

(defn ^:dev/after-load init []
  (mount/start))

(defn ^:dev/before-load before-load []
  (mount/stop))
