(ns ampie.content-script.content
  (:require [ampie.content-script.badge :as badge]
            [ampie.content-script.demo :as demo]
            [taoensso.timbre :as log]
            [ampie.content-script.info-bar :refer [info-bar-state]]
            [ampie.content-script.amplify :refer [amplify]]
            [ampie.content-script.visits-search :refer [google-results ddg-results]]
            ["webextension-polyfill" :as browser]
            [mount.core :as mount :refer [defstate]]))

(declare message-listener)

(defstate page-service
  :start (do (.. browser -runtime -onMessage (addListener message-listener))
             (assoc (badge/start (:show-info @info-bar-state))
               :reset-page (:reset-page @info-bar-state)))
  :stop (do (.. browser -runtime -onMessage (removeListener message-listener))
            (badge/stop @page-service)))

(defn message-listener [message]
  (let [message (js->clj message :keywordize-keys true)]
    (case (keyword (:type message))
      :url-updated  (do (mount/stop)
                        (mount/start
                          (mount/only
                            #{#'page-service
                              #'badge/seen-badges-ids #'badge/on-badge-remove
                              #'badge/existing-badges 'badge/visible-badges
                              #'google-results
                              #'ddg-results
                              ;; Info bar should start lazily because it is referenced
                              ;; in page-service.
                              ;; #'info-bar-state
                              #'amplify})))
      :amplify-page ((:amplify-page @amplify))
      (log/error "Unknown message type" message))))

(defn send-ampie-version []
  (demo/send-message-to-page
    {:type    :ampie-starting
     :version (.. browser -runtime (getManifest) -version)}))

(defn respond-to-ampie-version-message [^js evt]
  (when (and (= (.-source evt) js/window)
          (.-data evt)
          (= (.. evt -data -type) "get-ampie-version")
          (demo/is-ampie-domain? (.. js/document -location -href)))
    (send-ampie-version)))

(defn ^:dev/after-load init []
  ;; Both send the ampie version and set a listener to respond to
  ;; a request for ampie version, so that the page gets the ampie version
  ;; no matter the order in which content script and the page script ran.
  (send-ampie-version)
  (when (demo/is-ampie-domain? (.. js/document -location -href))
    (. js/window addEventListener "message" respond-to-ampie-version-message))
  ;; Don't attempt to load the background services in the content script
  (mount/start (mount/only
                 #{#'page-service
                   #'badge/seen-badges-ids #'badge/on-badge-remove
                   #'badge/existing-badges 'badge/visible-badges
                   #'google-results
                   #'ddg-results
                   ;; Info bar should start lazily because it is referenced
                   ;; in page service.
                   ;; #'info-bar-state
                   #'amplify})))

(defn ^:dev/before-load before-load []
  (. js/window removeEventListener "message" respond-to-ampie-version-message)
  (mount/stop))
