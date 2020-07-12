(ns ampie.pages.popup
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [taoensso.timbre :as log]
            [ampie.components.visit :as components.visit]
            [ampie.components.basics :as b]
            [ampie.db :refer [db]]
            [ampie.visits :as visits]
            [ampie.visits.db :as visits.db]
            [ampie.time :as time]
            [ampie.settings :refer [settings]]
            [ampie.background.backend :refer [user-info] :as backend]
            ["webextension-polyfill" :as browser]
            [mount.core :as mount]))

(defonce state (r/atom {}))

(defn login-notice []
  [:div.login-notice "Sign up at "
   [:a (b/ahref-opts "https://ampie.app/register") "ampie.app"]])

(defn show-badges []
  (let [show-badges (:show-badges @@settings)]
    [:div.show-badges
     [:a {:href "#" :on-click (fn [evt]
                                (.preventDefault evt)
                                (swap! @settings update :show-badges not))}
      (if show-badges "Disable" "Enable")] " badges"
     (when show-badges
       [:div.ampie-badge.demo-badge
        [:div.ampie-badge-icon]])]))

(defn blacklisted-urls []
  [:div.blacklisted-urls])
(defn blacklisted-domains [])

(defn settings-form []
  [:div.settings-form
   [show-badges]
   [blacklisted-urls]
   [blacklisted-domains]])

(defn cache-status []
  [:div.cache-status])

(defn settings-page []
  [:div.settings
   [cache-status]
   [settings-form]])

(defn popup-page []
  [:div.popup-page
   (when-not (backend/logged-in?)
     [login-notice])
   [:div.content {:class (when (:link-cache-status @state) "large-footer")}
    [:h3 "This page in history"]
    [:div.history-container
     (for [visit (:past-visits-origins @state)]
       ^{:key (:visit-hash visit)}
       [components.visit/visit {:visit visit}])]]
   [:div.footer
    (when-let [link-cache-status (:link-cache-status @state)]
      [:div.row link-cache-status])
    [:div.row
     [:a.href (b/ahref-opts (.. browser -runtime (getURL "history.html")))
      "History"]
     (when (backend/can-complete-weekly?)
       [:a.href (b/ahref-opts (.. browser -runtime (getURL "weekly-links.html")))
        "Weekly links"])
     [settings-page]]]])

(defn load-origin-visits []
  (->
    ;; currentWindow is the window where the code is currently executing,
    ;; which is what we need.
    (.. browser -tabs (query #js {:active true :currentWindow true}))
    (.then #(js->clj % :keywordize-keys true))
    (.then (fn [[{url :url}]]
             (visits.db/get-past-visits-to-the-url url 100)))
    (.then #(map :origin %))
    (.then #(visits.db/get-visits-info %))
    (.then
      (fn [visits]
        (swap! state assoc :past-visits-origins visits)
        (doseq [[index visit] (map-indexed vector visits)]
          (visits/load-children-visits state [:past-visits-origins index]))))))

(defn set-download-status []
  (-> (.. browser -storage -local (get "link-cache-status"))
    (.then #(->> (js->clj % :keywordize-keys true)
              :link-cache-status
              (swap! state assoc :link-cache-status)))))

(defn ^:dev/after-load init []
  (mount/start)
  (load-origin-visits)
  (set-download-status)
  (js/setInterval set-download-status 500)
  (rdom/render [popup-page]
    (. js/document getElementById "popup-content")))
