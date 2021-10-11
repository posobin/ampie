(ns ampie.content-script.content
  (:require [ampie.content-script.badge :as badge]
            [ampie.content-script.demo :as demo]
            [taoensso.timbre :as log]
            [ampie.content-script.info-bar :refer [info-bar-state]]
            [ampie.content-script.sidebar :refer [sidebar-state]]
            [ampie.content-script.amplify :refer [amplify]]
            [ampie.content-script.visits-search :refer [tags-adder]]
            [ampie.interop :as i]
            [cljs.reader]
            [clojure.string]
            ["webextension-polyfill" :as browser]
            [mount.core :as mount :refer [defstate]]))

(declare message-listener)

(defstate page-service
  :start (do (.. browser -runtime -onMessage (addListener message-listener))
             #_(assoc (badge/start (:show-info @info-bar-state))
                 :reset-page (:reset-page @info-bar-state)))
  :stop (do (.. browser -runtime -onMessage (removeListener message-listener))
            #_(badge/stop @page-service)))

(defn try-update-auth-token! []
  (when (demo/is-ampie-domain? (.. js/document -location -href))
    (let [auth-token (-> (. js/localStorage getItem "ampie-user")
                       cljs.reader/read-string
                       :auth-token)]
      (when-not (clojure.string/blank? auth-token)
        (.. browser -runtime
          (sendMessage (clj->js {:type       :update-auth-token
                                 :auth-token auth-token})))))))

(defn start-services! []
  (try-update-auth-token!)
  (mount/start
    (mount/only
      #{#'page-service
        ;; #'badge/seen-badges-ids #'badge/on-badge-remove
        ;; #'badge/existing-badges 'badge/visible-badges
        #'tags-adder
        #'sidebar-state
        ;; Info bar should start lazily because it is referenced
        ;; in page-service.
        ;; #'info-bar-state
        #'amplify})))

(defn message-listener [message]
  (let [message (js->clj message :keywordize-keys true)]
    (case (keyword (:type message))
      :popup-opened  (demo/send-message-to-page {:type "ampie-popup-opened"})
      :url-updated   (do (mount/stop)
                         (start-services!))
      :amplify-page  ((:amplify-page @amplify))
      :upvote-page   ((:vote-on-page @amplify) true)
      :downvote-page ((:vote-on-page @amplify) false)
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
  ;; Don't attempt to load background services in the content script
  (start-services!))

(defn ^:dev/before-load before-load []
  (. js/window removeEventListener "message" respond-to-ampie-version-message)
  (mount/stop))
