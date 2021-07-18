(ns ampie.content-script.sidebar
  (:require [ampie.time]
            [ampie.content-script.sidebar.db :refer [db]]
            [ampie.content-script.sidebar.hn :as hn]
            [ampie.content-script.sidebar.hn-views :as hn-views]
            [ampie.content-script.sidebar.twitter-views :as twitter-views]
            [ampie.content-script.sidebar.twitter :refer [load-next-batch-of-tweets!]]
            [malli.core :as m]
            ["webextension-polyfill" :as browser]
            ["react-shadow-dom-retarget-events" :as retargetEvents]
            [ampie.macros :refer [then-fn]]
            [ampie.content-script.demo :refer [get-current-url send-message-to-page]]
            [reagent.dom :as rdom]
            [mount.core :as mount :refer [defstate]]))

(comment
  (m/validate [:= :a] :a)
  (-> @db :url->ui-state first)
  (-> @db :tweet-id->tweet)
  (-> @db :url->ui-state first second :twitter))

(defn load-page-info! [url]
  (send-message-to-page {:type :ampie-load-page-info :url url})
  (let [status (get-in @db [:url->context url :ampie/status])]
    (when-not (#{:loaded :loading} status)
      (swap! db assoc-in [:url->context url :ampie/status]
        :loading)
      (-> (.. browser -runtime
            (sendMessage (clj->js {:type :get-all-url-info
                                   :url  url})))
        (.then #(js->clj % :keywordize-keys true))
        (then-fn [{:keys [occurrences]}]
          (swap! db assoc-in [:url->context url]
            (assoc occurrences :ampie/status :loaded)))))))

(defn set-sidebar-url! [url]
  (some-> (load-page-info! url)
    (then-fn []
      (load-next-batch-of-tweets! url)
      (hn/load-next-batch-of-stories! url)
      #_(hn/load-next-batch-of-comments! url)))
  (swap! db update :url conj url))

(declare display-sidebar! remove-sidebar!)

(defn sidebar-component []
  (let [url         (-> @db :url first)
        url-context (get-in @db [:url->context url])]
    [:<>
     [:div.p-2.overscroll-contain.max-h-full.overflow-auto.font-sans
      (if (= :loading (:ampie/status url-context))
        [:div "Loading..."]
        [:div.flex.flex-col.gap-2
         #_[twitter-views/twitter-context url]
         [hn-views/hn-stories-context url]
         #_[hn-views/hn-comments-context url]])]
     (when goog.DEBUG
       [:div.absolute.p-2.pt-1.pb-1.bottom-0.right-0.font-sans.flex.gap-1.bg-white.border-t.border-l
        [:div.text-link-color.hover:underline
         {:on-click (fn [] (reset! db {}) (remove-sidebar!) (display-sidebar!))
          :role     :button}
         "Full"]
        [:div.text-link-color.hover:underline
         {:on-click (fn [] (remove-sidebar!) (display-sidebar!))
          :role     :button}
         "Reload"]])]))

(defn setup-sidebar-html-element []
  (let [sidebar-div     (. js/document createElement "div")
        shadow-root-el  (. js/document createElement "div")
        shadow          (. shadow-root-el (attachShadow #js {"mode" "open"}))
        tailwind        (. js/document createElement "link")
        sidebar-styling (. js/document createElement "link")]
    (set! (.-rel tailwind) "stylesheet")
    (set! (.-rel sidebar-styling) "stylesheet")
    (.setAttribute shadow-root-el "style"  "display: none;")
    (set! (.-href sidebar-styling) (.. browser -runtime (getURL "assets/sidebar.css")))
    ;; Put onload for the stylesheet that is attached last to the DOM
    (set! (.-onload tailwind) #(.setAttribute shadow-root-el "style" ""))
    (set! (.-href tailwind) (.. browser -runtime (getURL "assets/tailwind.css")))
    (set! (.-className shadow-root-el) "ampie-sidebar-holder")
    (set! (.-className sidebar-div) "sidebar-container")
    {:call-after-render (fn []
                          (. shadow (appendChild sidebar-styling))
                          (. shadow (appendChild tailwind))
                          (. shadow (appendChild sidebar-div))
                          (retargetEvents shadow)
                          (.. js/document -body (appendChild shadow-root-el)))
     :container         sidebar-div}))

(defn display-sidebar! []
  (js/console.log "loading sidebar")
  (when (. js/document querySelector ".ampie-sidebar-holder")
    (when goog.DEBUG
      (js/alert "ampie: attaching a second sidebar")
      (js/console.trace))
    (throw "Sidebar already exists"))
  (let [{:keys [container call-after-render]} (setup-sidebar-html-element)]
    (set-sidebar-url! (get-current-url))
    (rdom/render [sidebar-component] container call-after-render)))

(defn remove-sidebar! []
  (when-let [element (.. js/document -body (querySelector ".ampie-sidebar-holder"))]
    (.. js/document -body (removeChild element))
    (let [shadow-root (.. element -shadowRoot (querySelector ".sidebar-container"))]
      (rdom/unmount-component-at-node shadow-root))))

(defstate sidebar-state
  :start (display-sidebar!)
  :stop (do (remove-sidebar!)
            #_((:remove-sidebar @sidebar-state))))
