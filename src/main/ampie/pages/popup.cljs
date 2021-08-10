(ns ampie.pages.popup
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [ampie.components.visit :as components.visit]
            [ampie.components.basics :as b]
            [ampie.settings :refer [settings]]
            [ampie.background.backend :as backend]
            [ampie.background.messaging]
            [clojure.string :as string]
            ["webextension-polyfill" :as browser]
            [mount.core :as mount]))

(defonce state (r/atom {}))

(defn login-notice []
  [:div.login-notice "Sign up at "
   [:a (b/ahref-opts "https://ampie.app/register") "ampie.app"]])

(defn show-badges []
  (let [show-badges (:show-badges @@settings)]
    [:div.show-badges.setting
     [:div.header
      [:div.title "Badges"]
      [:div.toggle-wrapper
       [:div.toggle
        {:on-click (fn [evt]
                     (.preventDefault evt)
                     (swap! @settings update :show-badges not))}
        [:div.option.enabled {:class (when-not show-badges :off)} "Enabled"]
        [:div.option.disabled "Disabled"]]]]
     [:div.description
      "Badges are the little ampersands that appear near potentially interesting links"
      [:div.ampie-badge.demo-badge
       [:div.ampie-badge-icon]] "."]]))

(defn auto-show-domain-links []
  (let [show-domain-links (:auto-show-domain-links @@settings)]
    [:div.show-domain-links.setting
     [:div.header
      [:div.title "Auto-open infobar"]
      [:div.toggle-wrapper
       [:div.toggle
        {:on-click (fn [evt]
                     (.preventDefault evt)
                     (swap! @settings update :auto-show-domain-links not))}
        [:div.option.enabled {:class (when-not show-domain-links :off)} "Enabled"]
        [:div.option.disabled "Disabled"]]]]
     [:div.description
      "Automatically open the infobar in the bottom right corner the first time you "
      "visit a new domain if there are conversations about some pages on the domain."]]))

(defn enable-amplify-dialog []
  (let [enabled (:amplify-dialog-enabled @@settings)]
    [:div.enable-amplify-dialog.setting
     [:div.header
      [:div.title "Amplify suggestion"]
      [:div.toggle-wrapper
       [:div.toggle
        {:on-click (fn [evt]
                     (.preventDefault evt)
                     (swap! @settings update :amplify-dialog-enabled not))}
        [:div.option.enabled {:class (when-not enabled :off)} "Enabled"]
        [:div.option.disabled "Disabled"]]]]
     [:div.description
      "Show a small pop-up suggesting that you amplify the page after "
      "you have spent two minutes on it. "
      "You can change the amplify keyboard shortcut in the "
      [:a {:href "#"
           :on-click
           #(.. browser -tabs
              (create #js
                {:url
                 (if (string/includes? (.. js/window -navigator -userAgent) "Firefox")
                   "https://bug1303384.bmoattachments.org/attachment.cgi?id=9051647"
                   "chrome://extensions/shortcuts")}))}
       "browser settings"] "."]]))

(defn hn-enabled []
  (let [enabled (:hn-enabled @@settings)]
    [:div.hn-enabled.setting
     [:div.header
      [:div.title "Hacker News"]
      [:div.toggle-wrapper
       [:div.toggle
        {:on-click (fn [evt]
                     (.preventDefault evt)
                     (swap! @settings update :hn-enabled not))}
        [:div.option.enabled {:class (when-not enabled :off)} "Enabled"]
        [:div.option.disabled "Disabled"]]]]
     [:div.description
      "Do you want to see links from the orange website?"]]))

(defn mac? [] (clojure.string/starts-with? (.-platform js/navigator) "Mac"))
(defn windows? [] (clojure.string/starts-with? (.-platform js/navigator) "Win32"))

(defn badge-toggle-key []
  (let [changed (r/atom false)]
    (fn []
      (let [chosen-key (:badge-toggle-key @@settings)]
        [:div.badge-toggle-key.setting
         [:div.header
          [:div.title "Badge tooltip key"]
          [:div.toggle-wrapper
           (let [options      [{:key  "Alt"
                                :text (if (mac?) "⌥ Option" "Alt")}
                               {:key "Shift"}
                               {:key  "Meta"
                                :text (cond (mac?)     "⌘ Cmd"
                                            (windows?) "Win"
                                            :else      "Meta")}
                               {:key "Control" :text "Ctrl"}
                               {:key "disabled" :text "Disabled"}]
                 chosen-index (->> options
                                (map-indexed vector)
                                (filter #(= (-> % second :key) chosen-key))
                                first first)
                 margin-top   (let [m (* chosen-index 12)]
                                (str "-" (quot m 10) "." (mod m 10) "em"))]
             [:div.toggle
              {:on-click
               (fn [evt]
                 (.preventDefault evt)
                 (reset! changed true)
                 (swap! @settings update
                   :badge-toggle-key
                   (fn [old-key]
                     (or (->> (map vector options (rest options))
                           (filter #(= (-> % first :key) old-key))
                           first second :key)
                       (-> options first :key)))))}
              (for [[idx {:keys [key text]}] (map-indexed vector options)]
                ^{:key idx}
                [:div.option
                 {:class (if (= key "disabled") :disabled :enabled)
                  :style {:margin-top (when (zero? idx) margin-top)}}
                 (or text key)])])]]
         [:div.description
          "Hold this key down to show tooltips near interesting links. "
          (when @changed
            [:b "New key will work on a page once you have reloaded it."])]]))))

(defn blacklisted-urls []
  ;; Creating a separate atom because updating settings very quickly might be
  ;; problematic due to local storage updates triggering an infinite loop of
  ;; updates.
  (let [urls (r/atom (:blacklisted-urls @@settings))]
    (fn []
      [:div.blacklisted-urls.setting
       [:div.header
        [:div.title "Disable on"]]
       [:div.description
        "If a page URL contains one of these, ampie won't send it to the server, "
        "and you'll see no sidebar or amplify pop-ups."]
       [:div.blacklist
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
                    :value       url}]])]])))

(defn settings-form []
  [:div.settings-form
   [:h2 "Ampie settings"]
   ;; [show-badges]
   ;; [badge-toggle-key]
   ;; [auto-show-domain-links]
   [enable-amplify-dialog]
   ;; [hn-enabled]
   [blacklisted-urls]])

(defn settings-page []
  [:div.settings
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
        "Amplify current page"]
       [:a.href (b/ahref-opts "https://forms.gle/CdAhxhu9ym2mQjgX6")
        "Give feedback"]]]]))

#_(defn load-origin-visits []
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

(defn settings-updated
  [_key _reference old-state new-state]
  (doseq [[key _] (second (clojure.data/diff old-state new-state))]
    (when-not (= key :blacklisted-urls)
      (backend/setting-updated key (new-state key)))))

(defn ^:dev/after-load init []
  (mount/start (mount/only #{#'ampie.background.backend/user-info
                             #'ampie.background.backend/cookie-watcher
                             #'ampie.background.backend/auth-token
                             #'ampie.settings/settings
                             #'ampie.settings/settings-watcher}))
  (add-watch @settings :backend-notifier settings-updated)
  (.then (.. browser -tabs (query #js {:active true :currentWindow true}))
    (fn [[tab]]
      (.. browser -tabs
        (sendMessage (.-id tab)
          #js {:type "popup-opened"}))))
  #_(set-download-status)
  #_(js/setInterval set-download-status 500)
  ;; Need a delay because otherwise firefox doesn't load blacklisted urls in time
  (js/setTimeout
    (fn []
      (rdom/render [popup-page]
        (. js/document getElementById "popup-content")))
    500))

(defn ^:dev/before-load stop []
  (remove-watch @settings :backend-notifier)
  (mount/stop))
