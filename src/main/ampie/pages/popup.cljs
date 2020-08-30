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
            [ampie.background.messaging]
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

(defn auto-show-domain-links []
  (let [show-domain-links (:auto-show-domain-links @@settings)]
    [:div.show-domain-links
     [:a {:href     "#"
          :on-click (fn [evt]
                      (.preventDefault evt)
                      (swap! @settings update :auto-show-domain-links not))}
      (if show-domain-links "Don't show" "Show")] " interesting links "
     "on a new domain."]))

(defn enable-amplify-dialog []
  (let [enabled (:amplify-dialog-enabled @@settings)]
    [:<>
     [:div.enable-amplify-dialog
      [:a {:href     "#"
           :on-click (fn [evt]
                       (.preventDefault evt)
                       (swap! @settings update :amplify-dialog-enabled not))}
       (if enabled "Disable" "Enable")] " amplify dialog."]
     [:div "Change the amplify keyboard shortcut in "
      [:a {:href "#"
           :on-click
           #(.. browser -tabs
              (create #js
                {:url "chrome://extensions/shortcuts"}))}
       "settings"] "."]]))

(defn blacklisted-urls []
  ;; Creating a separate atom because updating settings very quickly might be
  ;; problematic due to local storage updates triggering an infinite loop of
  ;; updates.
  (let [urls (r/atom (:blacklisted-urls @@settings))]
    (fn []
      [:div.blacklisted-urls
       [:p "Don't show the amplify popup on pages whose url contains:"]
       (for [[index url] (map-indexed vector (conj @urls ""))]
         ^{:key index}
         [:div.blacklisted-row
          [:input {:type        "text"
                   :placeholder "Add url substring"
                   :on-change
                   (fn [e]
                     (swap! urls
                       #(->> (assoc % index (.. e -target -value))
                          (filter seq)
                          vec))
                     (swap! @settings assoc :blacklisted-urls @urls))
                   :value       url}]])])))

(defn settings-form []
  [:div.settings-form
   [:h3 "Ampie"]
   [:p "To load the tweets / HN submissions / friend's shares of the page, click on the bar in the lower right corner."]
   [show-badges]
   [auto-show-domain-links]
   [enable-amplify-dialog]
   [blacklisted-urls]])

(defn settings-page []
  [:div.settings
   [:h3 ""]
   [settings-form]])

(defn popup-page []
  (let [page (@state :page :settings)]
    [:div.popup-page
     (when-not (backend/logged-in?)
       [login-notice])
     [:div.content {:class (when (:link-cache-status @@settings) "large-footer")}
      (case page
        :home
        [:<> [:h3 "This page in history"]
         [:div.history-container
          (for [visit (:past-visits-origins @state)]
            ^{:key (:visit-hash visit)}
            [components.visit/visit {:visit visit}])]]
        :settings
        [settings-page])]
     [:div.footer
      (when-let [link-cache-status (:link-cache-status @@settings)]
        [:div.row link-cache-status])
      [:div.row
       #_[:a (b/ahref-opts (.. browser -runtime (getURL "history.html")))
          "History"]
       (when (backend/can-complete-weekly?)
         [:a.href (b/ahref-opts (.. browser -runtime (getURL "weekly-links.html")))
          "Weekly links"])
       #_[:a {:href "#" :on-click #(swap! state assoc :page
                                     (case page :settings :home :home :settings))}
          (case page :home "Settings" :settings "Back")]
       [:a {:href "#" :on-click ampie.background.messaging/amplify-current-tab}
        "Amplify this page"]
       [:a.href (b/ahref-opts "https://forms.gle/CdAhxhu9ym2mQjgX6")
        "Give feedback"]]]]))

(defn load-origin-visits []
  (->
    ;; currentWindow is the window where the code is currently executing,
    ;; which is what we need.
    (.. browser -tabs (query #js {:active true :currentWindow true}))
    (.then #(js->clj % :keywordize-keys true))
    (.then (fn [[{url :url}]]
             (visits.db/get-past-visits-to-the-url url 10)))
    (.then #(map :origin %))
    (.then #(visits.db/get-visits-info %))
    (.then
      (fn [visits]
        (swap! state assoc :past-visits-origins visits)
        (doseq [[index visit] (map-indexed vector visits)]
          (visits/load-children-visits state [:past-visits-origins index]))))))

#_(defn set-download-status []
    (-> (.. browser -storage -local (get "link-cache-status"))
      (.then #(->> (js->clj % :keywordize-keys true)
                :link-cache-status
                (swap! state assoc :link-cache-status)))))

(defn ^:dev/after-load init []
  (mount/start (mount/only #{'ampie.background.backend/user-info
                             'ampie.background.backend/cookie-watcher
                             'ampie.background.backend/auth-token
                             'ampie.settings/settings
                             'ampie.db/db}))
  (load-origin-visits)
  #_(set-download-status)
  #_(js/setInterval set-download-status 500)
  (rdom/render [popup-page]
    (. js/document getElementById "popup-content")))
