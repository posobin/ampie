(ns ampie.pages.settings
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [ampie.db :refer [db]]
            [ampie.background.backend :as backend]
            [ampie.visits.db :as visits.db]
            [mount.core :as mount]
            [ampie.settings :refer [settings]]))

(defn login-notice []
  [:div.login-notice "You need to log in at "
   [:a {:href "https://ampie.app"} "ampie.app"]])

(defn greeting []
  [:div.greeting (str "Hi " (:username @@backend/user-info))])

(defn show-badges []
  (let [show-badges (:show-badges @@settings)]
    [:div.show-badges
     [:a {:href "#" :on-click (fn [evt]
                                (.preventDefault evt)
                                (swap! @settings update :show-badges not))}
      (if show-badges "Disable" "Enable")] " badges"
     (when show-badges
       [:div.ampie-badge.demo-badge
        [:div.ampie-badge-icon "&"]])]))

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
   (if-not (backend/logged-in?)
     [login-notice]
     [greeting])
   [cache-status]
   [settings-form]])

(defn ^:dev/after-load init []
  (mount/start)
  (rdom/render [settings-page]
    (. js/document getElementById "app")))

(defn ^:dev/before-load stop []
  (mount/stop))
