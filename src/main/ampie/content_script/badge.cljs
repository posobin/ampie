(ns ampie.content-script.badge
  (:require ["webextension-polyfill" :as browser]
            [ampie.interop :as i]
            [ampie.url :as url]
            [taoensso.timbre :as log]
            [mount.core]
            [clojure.string :as string])
  (:require-macros [mount.core :refer [defstate]]))

(defn- send-urls-to-background
  "Sends the given urls to background so that it saves them to seen-urls,
  and returns the map url -> [visits on which saw the url]."
  [urls response-handler]
  (when (seq urls)
    (->
      (.. browser
        -runtime
        (sendMessage
          (clj->js {:type     :add-seen-urls
                    :urls     urls
                    :page-url (.. js/window -location -href)})))
      (.then
        (fn [seen-urls-visits]
          (let [seen-urls-visits (js->clj seen-urls-visits :keywordize-keys true)
                current-nurl     (url/clean-up (.. js/window -location -href))

                cleaned-up
                (into {}
                  (for [[url visits] (map vector urls seen-urls-visits)]
                    [url
                     (->> visits
                       (filter #(not= (:normalized-url %) current-nurl)))]))]
            (log/info "Got response from background"
              seen-urls-visits)
            (log/info "After cleaning up"
              cleaned-up)
            (response-handler cleaned-up)))))))

(defn- get-z-index
  "Compute z-index of the given element."
  [element]
  (let [style   (.. js/document -defaultView (getComputedStyle element))
        z-index (.getPropertyValue style "z-index")]
    (when-not (= z-index "auto")
      (int z-index))))

(defn- is-visible?
  "Check if the element is displayed on the page, `element` has to be not null."
  [element]
  (let [style     (. js/window getComputedStyle element false)
        transform (= (. style getPropertyValue "transform")
                    "matrix(1, 0, 0, 0, 0, 0)")
        hidden    (= (. style getPropertyValue "visibility")
                    "hidden")
        display   (= (. style getPropertyValue "display")
                    "none")]
    (not (or transform hidden display))))

(defn- table-element?
  "Check if the element is td, th or table.
  Useful because positioning is disallowed in the offsetParents of those kinds."
  [element]
  (contains? #{"td" "th" "table"}
    (string/lower-case (.-tagName element))))

(defn- is-fixed? [element]
  (and (some? element)
    (or (= (-> (. js/window getComputedStyle element false)
             (. -position))
          "fixed")
      (recur (.-offsetParent element)))))

(defn- get-offsets
  ([element] (get-offsets element (is-fixed? element)))
  ([element target-fixed?]
   (let [element-rect  (.getBoundingClientRect element)
         element-width (.-offsetWidth element)
         style         (. js/window getComputedStyle element)
         width         (if (and (= element-width 0)
                             (.-firstChild element))
                         (.. element -firstChild -offsetWidth)
                         element-width)]
     (if target-fixed?
       [(+ (.-left element-rect) width)
        (- (.-top element-rect) 4)]
       (if-let [parent (.-offsetParent element)]
         (if (table-element? parent)
           [(- (+ (.-left element-rect) width
                 (.-scrollX js/window))
              (js/parseFloat (.-paddingRight style)))
            (- (+ (.-top element-rect)
                 (js/parseFloat (.-paddingTop style))
                 (.-scrollY js/window))
              2)]
           [(max 0
              (min
                (- (+ (.-offsetLeft element) width)
                  (js/parseFloat (.-paddingRight style)))
                (- (.-clientWidth parent) 11)))
            (max 0
              (min
                (- (+ (.-offsetTop element)
                     (js/parseFloat (.-paddingTop style)))
                  2)
                (- (.-clientHeight parent) 15)))])
         ;; If no offset parent, the element is either fixed or
         ;; has display: none;
         [0 0])))))

(defn set-badge-color
  "Sets the color of the `badge` to the color of the `target`."
  [target badge]
  (let [color (.. js/window (getComputedStyle target) -color)]
    (set! (.. badge -firstElementChild -style -color) color)))

(defn position-ampie-badge [target badge]
  (let [target-fixed?   (is-fixed? target)
        badge-fixed?    (.. badge -classList (contains "ampie-badge-fixed"))
        [pos-x pos-y]   (get-offsets target target-fixed?)
        ;; Set visibility of the badge depending on the visibility of the target
        target-visible? (and (some? (.-offsetParent target))
                          (is-visible? target)
                          (is-visible? (.-offsetParent target)))
        badge-hidden?   (.. badge -classList (contains "ampie-badge-hidden"))]
    (cond (and (not target-visible?) (not badge-hidden?))
          (.. badge -classList (add "ampie-badge-hidden"))

          (and target-visible? badge-hidden?)
          (.. badge -classList (remove "ampie-badge-hidden")))
    (when target-visible?
      (cond (and target-fixed? (not badge-fixed?))
            (.. badge -classList (add "ampie-badge-fixed"))

            (and (not target-fixed?) badge-fixed?)
            (.. badge -classList (remove "ampie-badge-fixed")))
      (set! (.. badge -style -left) (str pos-x "px"))
      (set! (.. badge -style -top) (str pos-y "px"))
      (set! (.. badge -style -zIndex) (get-z-index target)))))

(defn generate-tooltip [target-info]
  (let [tooltip-div (. js/document createElement "div")
        header      (. js/document createElement "div")]
    (set! (.-className tooltip-div) "ampie-badge-tooltip")
    (set! (.-className header) "ampie-badge-tooltip-header")
    (set! (.-textContent header) "Seen at")
    (.appendChild tooltip-div header)
    (doseq [previously-seen-at target-info]
      (let [ahref (. js/document createElement "a")]
        (set! (.-href ahref) (:url previously-seen-at))
        (set! (.-textContent ahref) (:normalized-url previously-seen-at))
        (.appendChild tooltip-div ahref)))
    tooltip-div))

(defn position-tooltip [badge-div]
  (let [badge-rect    (.getBoundingClientRect badge-div)
        window-width  (. js/window -innerWidth)
        window-height (. js/window -innerHeight)]
    (if (< (- window-width (.-left badge-rect))
          300)
      (.. badge-div -classList (add "ampie-badge-tooltip-align-right"))
      (.. badge-div -classList (remove "ampie-badge-tooltip-align-right")))
    (if (< (- window-height (.-bottom badge-rect))
          150)
      (.. badge-div -classList (add "ampie-badge-tooltip-align-bottom"))
      (.. badge-div -classList (remove "ampie-badge-tooltip-align-bottom")))))

(defn show-tooltip [badge tooltip]
  (let [badge-rect    (.getBoundingClientRect badge)
        window-width  (. js/window -innerWidth)
        window-height (. js/window -innerHeight)]
    (when (not (.-parentElement tooltip))
      (. js/document.body appendChild tooltip))
    (let [tooltip-rect (.getBoundingClientRect tooltip)
          body-x       (.. js/document.body (getBoundingClientRect) -left)
          body-y       (.. js/document.body (getBoundingClientRect) -top)
          client-x     (if (< (- window-width (.-left badge-rect)) 300)
                         (- (.-left badge-rect) (.-width tooltip-rect) 2)
                         (+ (.-right badge-rect) 2))
          client-y     (if (< (- window-height (.-bottom badge-rect))
                             150)
                         (- (.-bottom badge-rect) (.-height tooltip-rect) 2)
                         (.-top badge-rect))
          fixed        (is-fixed? badge)]
      (if (and fixed
            (not (.. badge -classList (contains "ampie-badge-tooltip-fixed"))))
        (.. tooltip -classList (add "ampie-badge-tooltip-fixed"))
        (.. tooltip -classList (remove "ampie-badge-tooltip-fixed")))
      (set! (.. tooltip -style -left)
        (str (- client-x (when-not fixed body-x)) "px"))
      (set! (.. tooltip -style -top)
        (str (- client-y (when-not fixed body-y)) "px")))))

(defn add-ampie-badge [target target-id target-info]
  (let [badge-div  (. js/document createElement "div")
        badge-icon (. js/document createElement "div")
        tooltip    (generate-tooltip target-info)]
    (set! (.-className badge-div) "ampie-badge")
    (set! (.-className badge-icon) "ampie-badge-icon")
    (set! (.-textContent badge-icon) "&")
    (.appendChild badge-div badge-icon)
    (.setAttribute badge-div "ampie-badge-id" target-id)
    (let [mouse-out? (atom false)
          on-mouse-out
          (fn []
            (reset! mouse-out? true)
            (js/setTimeout #(when @mouse-out? (.remove tooltip)) 200))]
      (.addEventListener badge-div "mouseover"
        (fn []
          (reset! mouse-out? false)
          (show-tooltip badge-div tooltip)))
      (.addEventListener tooltip "mouseover" #(reset! mouse-out? false))
      (.addEventListener badge-div "mouseout" on-mouse-out)
      (.addEventListener tooltip "mouseout" on-mouse-out))
    (let [parent (.-offsetParent target)]
      (if (and parent (not (table-element? parent)))
        (.appendChild parent badge-div)
        (.. js/document -body (appendChild badge-div))))
    (position-ampie-badge target badge-div)
    (set-badge-color target badge-div)))

(defn process-child-links
  "Go through all the links that are in the `element`'s subtree and add badges
  to them."
  [element target-ids next-target-id]
  (let [page-links        (array-seq (.querySelectorAll element "a[href]"))
        unprocessed-links (filter #(nil? (.getAttribute % "processed-by-ampie"))
                            page-links)
        current-nurl      (url/clean-up (.. js/document -location -href))
        urls-to-query     (->> unprocessed-links
                            (map #(.-href %))
                            (filter url/should-store-url?))]
    (doseq [link-element unprocessed-links]
      (.setAttribute link-element "processed-by-ampie" ""))
    (send-urls-to-background
      urls-to-query
      (fn [url->where-saw-it]
        (doseq [target unprocessed-links]
          (let [target-url             (.-href target)
                target-prior-sightings (url->where-saw-it target-url)]
            (when (and (seq target-prior-sightings)
                    (not= (url/clean-up target-url) current-nurl))
              (let [target-id (dec (swap! next-target-id inc))]
                (add-ampie-badge target target-id target-prior-sightings)
                (.setAttribute target "processed-by-ampie" target-id)
                (swap! target-ids conj target-id)))))))))

(defn update-badge
  "Function to be run when `target` changes.
  Repositions/hides/shows the `badge` element depending on the state
  of `target`."
  [target badge]
  (let [badge-parent  (.-offsetParent badge)
        target-parent (.-offsetParent target)]
    (if (and target-parent (table-element? target-parent))
      (when-not (= badge-parent (.-body js/document))
        (.remove badge)
        (.appendChild (.-body js/document) badge))
      (when-not (= badge-parent target-parent)
        (when-let [parent (.-parent badge)]
          (.removeChild parent badge))
        (if (nil? target-parent)
          (.appendChild (. js/document -body) badge)
          (.appendChild (.-offsetParent target) badge))))
    (position-ampie-badge target badge)
    (set-badge-color target badge)))

(defn screen-update
  "Iterate over all the badge ids in the `target-ids` atom
  and update them, deleting the entries from `target-ids`
  that do not exist in the document."
  [target-ids]
  (doseq [target-id @target-ids]
    (let [target (. js/document querySelector
                   (str "[processed-by-ampie=\"" target-id "\"]"))
          badge  (. js/document querySelector
                   (str "[ampie-badge-id=\"" target-id "\"]"))]
      (if (nil? target)
        (do (when badge (.remove badge))
            (swap! target-ids disj target-id))
        (update-badge target badge)))))

(defn- get-child-targets-badges
  "Returns a seq of `{:target :badge :id}` - all the children targets
  of `element`, their corresponding badge elements, and their ids."
  [element]
  (let [targets (array-seq
                  (. element querySelectorAll "a[href][processed-by-ampie]"))
        ids     (map #(.getAttribute % "processed-by-ampie") targets)
        badges  (map #(. js/document querySelector
                        (str "[ampie-badge-id=\"" % "\"]"))
                  ids)]
    (map #(hash-map :target %1 :badge %2 :id %3)
      targets badges ids)))

(defn update-child-badges
  "Goes through all the links in the subtree of `element` that have been
  processed by ampie and updates their position/hides them/shows them."
  [element]
  (doseq [{:keys [target badge]} (get-child-targets-badges element)
          :when                  badge]
    (update-badge target badge)))

(defn remove-child-badges
  "Goes through all the links in the subtree of `element` that have been
  processed by ampie and removes the associated badges from the DOM and
  their ids from the `target-ids` atom."
  [element target-ids]
  (doseq [{:keys [target badge id]} (get-child-targets-badges element)]
    (swap! target-ids disj id)
    (when badge
      (.remove badge))
    (.removeAttribute target "processed-by-ampie")))

(defn- ampie-badge? [element]
  (and (.-className element)
    (string/includes? (.-className element) "ampie-badge")))

(defn- process-page-update
  "Accepts the update event from MutationObserver
  and updates the badges accordingly."
  [update target-ids next-target-id]
  (case (.-type update)
    "childList"
    (let [added-nodes   (->> (array-seq (.-addedNodes update))
                          (filter #(not (ampie-badge? %))))
          removed-nodes (->> (array-seq (.-removedNodes update))
                          (filter #(not (ampie-badge? %))))
          target        (.-target update)]
      (when (and
              (or (seq added-nodes) (seq removed-nodes))
              (= (.-nodeType target) 1)
              (.-offsetParent target))
        (update-child-badges (.-offsetParent target)))
      (when (seq added-nodes)
        (doseq [added-node added-nodes
                ;; Filter out text nodes
                :when      (= (.-nodeType added-node) 1)]
          (process-child-links added-node target-ids next-target-id)))
      (when (seq removed-nodes)
        (doseq [removed-node removed-nodes
                ;; Filter out text nodes
                :when        (= (.-nodeType removed-node) 1)]
          (remove-child-badges removed-node target-ids))))
    (log/error "Unknown mutation type" (.-type update))))

(defn start []
  (let [target-ids        (atom #{})
        next-target-id    (atom 0)
        process-page-updates
        (fn [updates]
          (doseq [update (array-seq updates)]
            (process-page-update update target-ids next-target-id)))
        mutation-observer (js/MutationObserver. process-page-updates)

        resize-event-timeout (atom nil)
        on-resize
        (fn []
          (js/clearTimeout @resize-event-timeout)
          (reset! resize-event-timeout
            (js/setTimeout #(screen-update target-ids) 25)))]
    (.observe mutation-observer js/document.body #js {:childList true
                                                      :subtree   true})
    (. js/window addEventListener "resize" on-resize)
    (process-child-links js/document.body target-ids next-target-id)
    {:next-target-id    next-target-id
     :target-ids        target-ids
     :mutation-observer mutation-observer
     :on-resize         on-resize}))

(defn stop [{:keys [next-target-id target-ids mutation-observer on-resize]}]
  (.disconnect mutation-observer)
  (.removeEventListener js/window "resize" on-resize))

(defstate service
  :start (start)
  :stop (stop @service))
