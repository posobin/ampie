(ns ampie.content-script.sidebar
  (:require [ampie.time]
            [ampie.content-script.sidebar.db :refer [db]]
            [ampie.content-script.sidebar.hn :as hn]
            [ampie.content-script.sidebar.hn-views :as hn-views]
            [ampie.content-script.sidebar.twitter-views :as twitter-views]
            [ampie.content-script.sidebar.twitter :as twitter]
            [ampie.content-script.sidebar.domain :as domain]
            [ampie.content-script.sidebar.domain-views :as domain-views]
            [ampie.content-script.sidebar.amplified :as amplified]
            [ampie.content-script.sidebar.amplified-views :as amplified-views]
            [ampie.content-script.sidebar.sticky-manager :as sticky-manager]
            [clojure.string :as str]
            [reagent.core :as r]
            ["webextension-polyfill" :as browser]
            ["react-shadow-dom-retarget-events" :as retargetEvents]
            [ampie.macros :refer [then-fn]]
            [ampie.content-script.demo :refer [get-current-url send-message-to-page]]
            [reagent.dom :as rdom]
            [mount.core :as mount :refer [defstate]]))

(defn load-page-info! [url]
  (send-message-to-page {:type :ampie-load-page-info :url url})
  (let [status (get-in @db [:url->context url :ampie/status])]
    (when-not (#{:loaded :loading} status)
      (swap! db assoc-in [:url->context url :ampie/status]
        :loading)
      (-> (.. browser -runtime
            (sendMessage (clj->js {:type :get-url-context
                                   :url  url})))
        (.then #(js->clj % :keywordize-keys true))
        (then-fn [{:keys [occurrences]}]
          (swap! db assoc-in [:url->context url]
            (assoc occurrences :ampie/status :loaded)))))))

(defn url-blacklisted? [url]
  (.. browser -runtime
    (sendMessage (clj->js {:type :url-blacklisted?
                           :url  url}))))

(defn set-sidebar-url! [url]
  (-> (url-blacklisted? url)
    (then-fn [blacklisted?]
      (when-not blacklisted?
        (swap! db update :url conj url)
        (some-> (load-page-info! url)
          (then-fn []
            (domain/load-next-batch-of-domain-links! url)
            (domain/load-next-batch-of-backlinks! url)
            (twitter/load-next-batch-of-tweets! url)
            (amplified/load-next-batch-of-amplified-links! url)
            (hn/load-next-batch-of-stories! url)
            (hn/load-next-batch-of-comments! url)))))))

(declare display-sidebar! remove-sidebar!)

;; For restoring the scroll position
;; TODO: save it in the ui-state in the DB
(defonce scroll-position (atom 0))

(defn sidebar-component []
  (let [sticky          (sticky-manager/sticky-manager)
        resize-observer (js/ResizeObserver.
                          (fn [[entry]]
                            ((:on-resize sticky) (.-target entry))
                            ;; Restore the scroll position from the saved one on extension reload
                            (when (> (.. entry -target -scrollHeight) @scroll-position)
                              (set! (.. entry -target -scrollTop) @scroll-position))))
        ;; Don't need a Ratom here, just the standard atom is enough
        sidebar-element (atom nil)
        key-presses     (r/atom
                          {:last-shift-press     0
                           :force-open           false
                           :last-alt-shift-press 0
                           :hidden               false})
        on-key-down
        (fn [e]
          (when (= (str/lower-case (.-key e)) "shift")
            (let [[last-time flag] (if (.-altKey e)
                                     [:last-alt-shift-press :hidden]
                                     [:last-shift-press :force-open])]
              (if (> (+ (last-time @key-presses) 400) (.getTime (js/Date.)))
                (swap! key-presses
                  (fn [kp] (-> kp (assoc last-time 0) (update flag not))))
                (swap! key-presses assoc last-time (.getTime (js/Date.)))))))]
    (r/create-class
      {:component-did-mount
       (fn [] (when @sidebar-element
                (set! (.-scrollTop @sidebar-element)
                  @scroll-position))
         (. js/document addEventListener "keydown" on-key-down))
       :component-will-unmount
       #(. js/document removeEventListener "keydown" on-key-down)

       :reagent-render
       (fn []
         (when-let [url (first @(r/cursor db [:url]))]
           (let [url-context (r/cursor db [:url->context url])]
             [:div.fixed.right-0.top-14.bottom-14.font-sans
              [:div.absolute.right-px.translate-y-full.bottom-0.transform.pt-px.flex.flex-row.gap-1.items-center
               {:class    (when (:hidden @key-presses) :hidden)
                :role     :button
                :on-click #(swap! key-presses update :force-open not)}
               [:span.text-xs.whitespace-nowrap
                "Shift × 2"]
               (if (:force-open @key-presses)
                 [:div.hide-sidebar-icon.w-4.h-4]
                 [:div.show-sidebar-icon.w-4.h-4])]
              [:div.absolute.right-px.-translate-y-full.transform.pb-px.flex.flex-row.gap-1.items-center
               {:class    (when (:hidden @key-presses) :hidden)
                :role     :button
                :on-click #(swap! key-presses update :hidden not)}
               [:span.text-xs.whitespace-nowrap
                "Alt-Shift × 2"]
               [:div.close-icon.w-2dot5.h-2dot5.p-0dot5]]
              [:div.sidebar-container.absolute.top-0.bottom-0
               {:class [(if (:force-open @key-presses)
                          :right-0
                          :hover:right-0)
                        (when (:hidden @key-presses) :hidden)]}
               [:div.absolute.left-0dot5.top-0dot5.bottom-0dot5.right-0.bg-white
                [:div.p-2.overscroll-contain.max-h-full.overflow-auto
                 {:ref (fn [el]
                         ((:container-ref sticky) el)
                         (reset! sidebar-element el)
                         (when el
                           (.observe resize-observer el)
                           (.addEventListener el "scroll"
                             (fn []
                               (reset! scroll-position (.-scrollTop el))
                               ((:on-scroll sticky) el)))))}
                 [(:render-context-provider sticky)
                  (if (= :loading (:ampie/status url-context))
                    [:div "Loading..."]
                    [:div.flex.flex-col.gap-2
                     [amplified-views/amplified-context url]
                     [twitter-views/twitter-context url]
                     [hn-views/hn-stories-context url]
                     [hn-views/hn-comments-context url]
                     [domain-views/domain-context url]
                     [domain-views/backlinks-context url]])]]
                (when goog.DEBUG
                  [:div.absolute.p-2.pt-1.pb-1.bottom-0.right-0.font-sans.flex.gap-1.bg-white.border-t.border-l
                   [:div.text-link-color.hover:underline
                    {:on-click (fn [] (reset! db {}) (remove-sidebar!) (display-sidebar!))
                     :role     :button}
                    "Full"]
                   [:div.text-link-color.hover:underline
                    {:on-click (fn [] (remove-sidebar!) (display-sidebar!))
                     :role     :button}
                    "Reload"]])]]])))})))

(defn setup-sidebar-html-element []
  (let [sidebar-div     (. js/document createElement "div")
        shadow-root-el  (. js/document createElement "div")
        shadow          (. shadow-root-el (attachShadow #js {"mode" "open"}))
        tailwind        (. js/document createElement "link")
        sidebar-styling (. js/document createElement "link")]
    (set! (.-rel tailwind) "stylesheet")
    (set! (.-rel sidebar-styling) "stylesheet")
    (.setAttribute shadow-root-el "style" "display: none;")
    (set! (.-href sidebar-styling) (.. browser -runtime (getURL "assets/sidebar.css")))
    ;; Put onload for the stylesheet that is attached last to the DOM
    (set! (.-onload tailwind) #(.setAttribute shadow-root-el "style" ""))
    (set! (.-href tailwind) (.. browser -runtime (getURL "assets/tailwind.css")))
    (set! (.-className shadow-root-el) "ampie-sidebar-holder")
    (set! (.-className sidebar-div) "sidebar-wrapper")
    {:call-after-render (fn []
                          (. shadow (appendChild sidebar-styling))
                          (. shadow (appendChild tailwind))
                          (. shadow (appendChild sidebar-div))
                          (retargetEvents shadow)
                          (.. js/document -body (appendChild shadow-root-el)))
     :remove-sidebar    (fn [])
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
