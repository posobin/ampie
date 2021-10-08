(ns ampie.content-script.sidebar
  (:require [ampie.time]
            [ampie.content-script.sidebar.db :as db :refer [db]]
            [ampie.content-script.sidebar.hn :as hn]
            [ampie.content-script.sidebar.hn-views :as hn-views]
            [ampie.content-script.sidebar.twitter-views :as twitter-views]
            [ampie.content-script.sidebar.twitter :as twitter]
            [ampie.content-script.sidebar.domain :as domain]
            [ampie.content-script.sidebar.domain-views :as domain-views]
            [ampie.content-script.sidebar.amplified :as amplified]
            [ampie.content-script.sidebar.amplified-views :as amplified-views]
            [ampie.content-script.sidebar.feedback-views :as feedback-views]
            [ampie.content-script.sidebar.sticky-manager :as sticky-manager]
            [ampie.components.basics :as b]
            [clojure.string :as str]
            [clojure.edn]
            [reagent.core :as r]
            ["webextension-polyfill" :as browser]
            ["react-shadow-dom-retarget-events" :as retargetEvents]
            [ampie.macros :refer [then-fn catch-fn]]
            [ampie.content-script.demo :refer [get-current-url send-message-to-page]]
            [reagent.dom :as rdom]
            [mount.core :as mount :refer [defstate]]))

(defn load-page-info! [url]
  (send-message-to-page {:type :ampie-load-page-info :url url})
  (let [status (get-in @db [:url->context url :ampie/status])]
    (if-not (#{:loaded :loading} status)
      (do (swap! db assoc-in [:url->context url :ampie/status]
            :loading)
          (-> (.. browser -runtime
                (sendMessage (clj->js {:type :get-url-context
                                       :url  url})))
            (.then #(js->clj % :keywordize-keys true))
            (then-fn [{:keys [occurrences error]}]
              (swap! db assoc-in [:url->context url]
                (assoc occurrences :ampie/status (if error :error :loaded)))
              (when (= error "unauthorized")
                (swap! db assoc-in [:user-state :logged-out?] true))
              (boolean error))))
      (js/Promise.resolve false))))

(defn url-blacklisted? [url]
  (.. browser -runtime
    (sendMessage (clj->js {:type :url-blacklisted?
                           :url  url}))))

(defn sidebar-empty? [url]
  (let [url-context @(r/cursor db [:url->context url])]
    (not (some #(seq (url-context %)) db/url-context-origins))))

(defn logged-out? []
  @(r/cursor db [:user-state :logged-out?]))

(defn show-sidebar? [url]
  (or (and (= @(r/cursor db [:url->context url :ampie/status]) :loaded)
        (not @(r/track sidebar-empty? url)))
    (logged-out?)))

(declare expand-sidebar! scroll-header-into-view! log-analytics-event! reset-analytics-log!)

(defn has-contexts? [url]
  (let [url-context @(r/cursor db [:url->context url])]
    {:has-domain-context (boolean (seq (url-context :domain)))
     :has-page-context   (boolean (some #(seq (url-context %))
                                    (remove #{:domain} db/url-context-origins)))}))

(defn show-sidebar-on-url? [url]
  (let [{:keys [has-domain-context has-page-context]} (has-contexts? url)]
    (.. browser -runtime
      (sendMessage (clj->js {:type               :show-sidebar-on-url?
                             :url                url
                             :has-domain-context has-domain-context
                             :has-page-context   has-page-context})))))

(defn mark-page-visited! [url]
  (let [{:keys [has-domain-context has-page-context]} (has-contexts? url)]
    (.. browser -runtime
      (sendMessage (clj->js {:type               :mark-page-visited!
                             :url                url
                             :has-domain-context has-domain-context
                             :has-page-context   has-page-context})))))

(defn mac? [] (str/starts-with? (.-platform js/navigator) "Mac"))

(def initial-sidebar-visual-state
  {:last-shift-press   0
   :force-open         false ;; Expand the sidebar without hover?
   :last-alt-press     0
   ;; Hide the sidebar completely?
   :hidden             true
   ;; Show a notice that says that the sidebar is empty.
   ;; Triggered by pressing opt-opt/ctrl-ctrl on a page that doesn't have context
   :show-empty-notice? false
   })

(defonce sidebar-visual-state (r/atom initial-sidebar-visual-state))

(defn load-all-origins-info-for-url!
  "Returns a promise that resolves once all the info for all the origins
  has been loaded for the given url"
  [url]
  (js/Promise.all
    (array
      (domain/load-next-batch-of-domain-links! url)
      (domain/load-next-batch-of-backlinks! url)
      (twitter/load-next-batch-of-tweets! url)
      (amplified/load-next-batch-of-amplified-links! url)
      (hn/load-next-batch-of-stories! url)
      (hn/load-next-batch-of-comments! url))))

(defn set-sidebar-url!
  ([url] (set-sidebar-url! url {}))
  ([url {:keys [expand-sidebar focus-origin reason prevent-load]
         :or   {expand-sidebar false
                focus-origin   nil
                prevent-load   false
                reason         :page-visit}}]
   {:pre [(#{:page-visit :ampie-tag-click} reason)]}
   (reset-analytics-log!)
   (swap! db update :url conj url)
   (when expand-sidebar (expand-sidebar!))
   (when (= reason :ampie-tag-click) (log-analytics-event! :search-click nil))
   (when-not prevent-load
     (-> (load-page-info! url)
       (then-fn []
         ;; Always show the sidebar when logged out to prompt the log in
         (if (and (= reason :page-visit) (not (logged-out?)))
           (-> (show-sidebar-on-url? url)
             (then-fn [show?]
               (.then (mark-page-visited! url)
                 (constantly show?))))
           true))
       (then-fn [show?]
         (when show?
           (swap! sidebar-visual-state assoc :hidden false)
           (load-all-origins-info-for-url! url)))
       (then-fn []
         (when focus-origin
           (js/setTimeout
             #(scroll-header-into-view! (name focus-origin))
             500)))))))

(defn load-all-current-url-info!
  "Makes sure the current active url has all its info loaded, returns a promise
  that resolves once it is"
  []
  (let [url (first @(r/cursor db [:url]))]
    (.then (load-page-info! url)
      #(load-all-origins-info-for-url! url))))

(declare display-sidebar! remove-sidebar!)

;; For restoring the scroll position on remount when developing
;; TODO: save it in the ui-state in the DB?
(defonce scroll-position (atom 0))

(defn expand-sidebar! []
  (swap! sidebar-visual-state
    assoc :force-open true :hidden false))

(defn process-key-down
  "Toggles the sidebar on double shift,
  closes it on double opt on mac and double ctrl on other systems"
  [e]
  (if-let [[last-time flag]
           (case (str/lower-case (.-key e))
             "shift" [:last-shift-press :force-open]
             (if (mac?) "alt" "control")
             [:last-alt-press :hidden]
             nil)]
    (if (> (+ (last-time @sidebar-visual-state) 400) (.getTime (js/Date.)))
      (let [[old new] (swap-vals! sidebar-visual-state
                        (fn [visual-state]
                          (-> visual-state (assoc last-time 0) (update flag not))))]
        (when (and (not (:hidden new)) (:hidden old))
          ;; When unhiding the sidebar, make sure that all of the info in it is loaded
          (.then (load-all-current-url-info!)
            (fn []
              ;; If there is no info for the page, show the notice
              (when (-> (first @(r/cursor db [:url]))
                      sidebar-empty?)
                (swap! sidebar-visual-state assoc :show-empty-notice? true))))))
      (swap! sidebar-visual-state assoc last-time (.getTime (js/Date.))))
    (swap! sidebar-visual-state assoc
      :last-shift-press 0
      :last-alt-press 0)))

(declare shadow-root)

(def current-sticky (atom nil))

(comment (scroll-header-into-view! "hn_comment"))

(defn scroll-header-into-view! [header-type]
  (when-let [header (.. @shadow-root
                      (querySelector
                        (str "[data-ampie-header=\"" header-type "\"]")))]
    (when @current-sticky
      ((:scroll-into-view @current-sticky) header))))

(defn log-analytics-event! [event details]
  (.. browser -runtime
    (sendMessage (clj->js {:type    :log-analytics-event
                           :event   event
                           :details details}))))

(def previously-logged-events (atom #{}))

(defn reset-analytics-log! [] (reset! previously-logged-events #{}))

(defn log-analytics-event-once! [event details]
  (when-not (@previously-logged-events event)
    (log-analytics-event! event details)
    (swap! previously-logged-events conj event)))

(defn log-click-event! [^js event-info]
  (let [target (.-target event-info)]
    (when-let [clickable-parent (.closest target "[data-ampie-click-info]")]
      (let [parent-info (clojure.edn/read-string
                          (.getAttribute clickable-parent
                            "data-ampie-click-info"))]
        (log-analytics-event! :click
          (cond-> parent-info
            (= (:type parent-info) :ahref)
            (assoc :href (.-href clickable-parent))))))))

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
        ;; Make sure to capture the listener function so that if on source
        ;; reload we redefine the function, the right listener will get removed
        on-key-down     process-key-down]
    (r/create-class
      {:component-did-mount
       (fn [] (when @sidebar-element
                (set! (.-scrollTop @sidebar-element)
                  @scroll-position))
         (. js/document addEventListener "keydown" on-key-down)
         (reset-analytics-log!)
         (reset! current-sticky sticky))
       :component-will-unmount
       (fn []
         (. js/document removeEventListener "keydown" on-key-down)
         (reset-analytics-log!)
         (reset! current-sticky nil))

       :reagent-render
       (fn []
         (when-let [url (first @(r/cursor db [:url]))]
           [:div.contents
            (when (and (:show-empty-notice? @sidebar-visual-state)
                    (not (:hidden @sidebar-visual-state)))
              [:div.fixed.right-2.font-sans.transition-offsets.w-24.transform.leading-tight
               {:class    ["top-1/2" "-translate-y-1/2" "p-1dot5" "rounded-md"
                           "border" "border-blue-300"
                           "bg-white" "bg-opacity-80"]
                :role     "button"
                :on-click #(swap! sidebar-visual-state assoc
                             :show-empty-notice? false
                             :hidden true)}
               "Didn't find any context for the page"])
            (when (show-sidebar? url)
              ;; It's ok to call this on every render since it logs at most once per mounted component
              (log-analytics-event-once! :seen nil)
              [:div.fixed.right-0.font-sans.transition-offsets
               {:key           url
                :class         (if (:force-open @sidebar-visual-state)
                                 [:top-5 :bottom-5]
                                 [:top-14 :bottom-14])
                :on-wheel      #(log-analytics-event-once! :scroll nil)
                :on-mouse-down log-click-event!}
               [:div.absolute.right-px.translate-y-full.bottom-0.transform.pt-px.flex.flex-row.gap-1.items-center.bg-opacity-50.bg-white
                {:class    (when (:hidden @sidebar-visual-state) :hidden)
                 :role     :button
                 :on-click (fn []
                             (swap! sidebar-visual-state update :force-open not)
                             (log-analytics-event-once! :expand nil))}
                [:span.text-xs.whitespace-nowrap
                 "Shift-Shift"]
                (if (:force-open @sidebar-visual-state)
                  [:div.hide-sidebar-icon.w-4.h-4]
                  [:div.show-sidebar-icon.w-4.h-4])]
               [:div.absolute.right-px.-translate-y-full.transform.pb-px.flex.flex-row.gap-1.items-center.bg-opacity-50.bg-white
                {:class    (when (:hidden @sidebar-visual-state) :hidden)
                 :role     :button
                 :on-click (fn []
                             (swap! sidebar-visual-state update :hidden not)
                             (log-analytics-event-once! :close nil))}
                [:span.text-xs.whitespace-nowrap
                 (if (mac?) "Opt-Opt" "Ctrl-Ctrl")]
                [:div.close-icon.w-2dot5.h-2dot5.p-0dot5]]
               [:div.sidebar-container.absolute.top-0.bottom-0
                {:class [(if (:force-open @sidebar-visual-state)
                           :right-0
                           :hover:right-0)
                         (when (:hidden @sidebar-visual-state) :hidden)]}
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
                   [:div.flex.flex-col.gap-2
                    (when (logged-out?)
                      [:div [:a.text-link-color.underline
                             (b/ahref-opts "https://ampie.app/register") "Sign up"]
                       " to use ampie"])
                    [amplified-views/amplified-context url]
                    [twitter-views/twitter-context url]
                    [hn-views/hn-stories-context url]
                    [hn-views/hn-comments-context url]
                    [domain-views/backlinks-context url]
                    [domain-views/domain-context url]
                    [feedback-views/feedback-form]]]]
                 (when goog.DEBUG
                   [:div.absolute.p-2.pt-1.pb-1.bottom-0.right-0.font-sans.flex.gap-1.bg-white.border-t.border-l
                    [:div.text-link-color.hover:underline
                     {:on-click (fn [] (reset! db {}) (remove-sidebar!) (display-sidebar!))
                      :role     :button}
                     "Full"]
                    [:div.text-link-color.hover:underline
                     {:on-click (fn [] (remove-sidebar!) (display-sidebar!))
                      :role     :button}
                     "Reload"]])]]])]))})))

(def shadow-root (atom nil))

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
    (reset! shadow-root shadow)
    {:call-after-render (fn []
                          (. shadow (appendChild sidebar-styling))
                          (. shadow (appendChild tailwind))
                          (. shadow (appendChild sidebar-div))
                          (retargetEvents shadow)
                          (.. js/document -body (appendChild shadow-root-el)))
     :remove-sidebar!   (fn [] (reset! shadow-root nil))
     :container         sidebar-div}))

(defn display-sidebar! []
  (when (. js/document querySelector ".ampie-sidebar-holder")
    (when goog.DEBUG
      (js/alert "ampie: attaching a second sidebar")
      (js/console.trace))
    (throw "Sidebar already exists"))
  (let [{:keys [container call-after-render remove-sidebar!]}
        (setup-sidebar-html-element)
        url (get-current-url)]
    (-> (url-blacklisted? url)
      (then-fn [blacklisted?]
        (set-sidebar-url! url {:expand-sidebar false
                               :prevent-load   blacklisted?
                               :reason         :page-visit})))
    (rdom/render [sidebar-component] container call-after-render)
    {:remove-sidebar! remove-sidebar!}))

(defn remove-sidebar! []
  (when-let [element (.. js/document -body (querySelector ".ampie-sidebar-holder"))]
    (.. js/document -body (removeChild element))
    (when-not goog.DEBUG
      (reset! sidebar-visual-state initial-sidebar-visual-state))
    (let [shadow-root (.. element -shadowRoot (querySelector ".sidebar-wrapper"))]
      (rdom/unmount-component-at-node shadow-root))))

(defstate sidebar-state
  :start (display-sidebar!)
  :stop (do (remove-sidebar!)
            ((:remove-sidebar! @sidebar-state))))
