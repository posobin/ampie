(ns ampie.content
  (:require [reagent.core :as r])
  (:require [reagent.dom :as rdom])
  (:require [ampie.url :as url])
  (:require [shadow.cljs.devtools.client.browser :as shadow.browser]))

(defonce parent-urls (r/atom []))
(defonce link-tracking-interval-id (atom nil))
(defonce url->display-info (atom {}))
(defonce target-ids (atom #{}))
(defonce next-target-id (atom 0))

(defn refresh-source []
  (let [this-file
        {:resource-id   [:shadow.build.classpath/resource "ampie/content.cljs"]
         :module        :content-script :ns 'ampie.content
         :output-name   "ampie.content.js"
         :resource-name "ampie/content.cljs"
         :type          :cljs
         :provides      #{'ampie.content}
         :warnings      []
         :from-jar      nil
         :deps          ['goog 'cljs.core 'reagent.core 'reagent.dom
                         'shadow.cljs.devtools.client.browser
                         'shadow.cljs.devtools.client.hud]}]
    (shadow.browser/handle-build-complete
      {:info        {:sources [this-file] :compiled #{(:resource-id this-file)}}
       :reload-info {:never-load  #{}
                     :always-load #{}
                     :after-load  [{:fn-sym 'ampie.content/refreshed
                                    :fn-str "ampie.content.refreshed"}]}})))

(defn send-links-to-background [urls response-handler]
  (when (seq urls)
    (.. js/chrome
        -runtime
        (sendMessage
          #js {:type     "send-links-on-page"
               :links    (clj->js urls)
               :page-url (.. js/window -location -href)}
          (fn [js-url->where-seen]
            (let [url->where-seen (js->clj js-url->where-seen)
                  current-url     (url/clean-up (.. js/window -location -href))
                  cleaned-up      (into {} (for [[k v] url->where-seen]
                                             [k (filter #(not= (url/clean-up %)
                                                               current-url)
                                                        v)]))]
              (response-handler cleaned-up)))))))

(defn get-z-index
  ([element] (get-z-index element 0))
  ([element current-max]
   (if (= element js/document)
     current-max
     (let [style   (.. js/document -defaultView (getComputedStyle element))
           z-index (int (.getPropertyValue style "z-index"))
           parent  (.-parentNode element)]
       (recur parent (max z-index current-max))))))

;; Check if the element is displayed on the page, element has to be not null.
(defn is-element-visible? [element]
  (let [style     (. js/window getComputedStyle element false)
        transform (= (. style getPropertyValue "transform")
                     "matrix(1, 0, 0, 0, 0, 0)")
        hidden    (= (. style getPropertyValue "visibility")
                     "hidden")
        display   (= (. style getPropertyValue "display")
                     "none")]
    (not (or transform hidden display))))

(defn is-fixed? [element]
  (and (some? element)
       (or (= (-> (. js/window getComputedStyle element false)
                  (. -position))
              "fixed")
           (recur (.-offsetParent element)))))

(defn get-offsets
  ([element] (get-offsets element (is-fixed? element)))
  ([element target-fixed?]
   (let [element-rect  (.getBoundingClientRect element)
         element-width (.-offsetWidth element)
         width         (if (= element-width 0)
                         (.. element -firstChild -offsetWidth)
                         element-width)]
     (if target-fixed?
       [(+ (.-left element-rect)
           width)
        (- (.-top element-rect)
           4)]
       [(+ (.-left element-rect)
           (.-scrollX js/window)
           width)
        (+ (.-top element-rect)
           (.-scrollY js/window)
           -4)]))))

(defn position-ampie-badge [target badge]
  (let [target-fixed?   (is-fixed? target)
        badge-fixed?    (.. badge -classList (contains "ampie-badge-fixed"))
        [pos-x pos-y] (get-offsets target target-fixed?)
        ;; Set visibility of the badge depending on the visibility of the target
        target-visible? (and (some? (.-offsetParent target))
                             (is-element-visible? target)
                             #_(or (nil? (.-parentElement target))
                                   (is-element-visible? (.-parentElement target)))
                             (is-element-visible? (.-offsetParent target)))
        badge-hidden?   (.. badge -classList (contains "ampie-badge-hidden"))]
    (cond (and (not target-visible?) (not badge-hidden?))
          (.. badge -classList (add "ampie-badge-hidden"))

          (and target-visible? badge-hidden?)
          (.. badge -classList (remove "ampie-badge-hidden")))
    (when target-visible?
      (println (.getAttribute badge "ampie-badge-id")
               pos-x pos-y)
      (cond (and target-fixed? (not badge-fixed?))
            (.. badge -classList (add "ampie-badge-fixed"))

            (and (not target-fixed?) badge-fixed?)
            (.. badge -classList (remove "ampie-badge-fixed")))
      (set! (.. badge -style -left) (str pos-x "px"))
      (set! (.. badge -style -top) (str pos-y "px"))
      (set! (.. badge -style -zIndex) (+ (get-z-index target) 1)))))

(defn generate-tooltip [target-info]
  (let [tooltip-div (. js/document createElement "div")
        header      (. js/document createElement "div")]
    (aset tooltip-div "className" "ampie-badge-tooltip")
    (aset header "className" "ampie-badge-tooltip-header")
    (aset header "textContent" "Seen at")
    (.appendChild tooltip-div header)
    (doseq [previously-seen-at target-info]
      (let [url-p (. js/document createElement "p")]
        (aset url-p "textContent" previously-seen-at)
        (.appendChild tooltip-div url-p)))
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

(defn add-ampie-badge [target target-id target-info]
  (let [badge-div  (. js/document createElement "div")
        badge-icon (. js/document createElement "div")
        tooltip    (generate-tooltip target-info)]
    (aset badge-div "className" "ampie-badge")
    (aset badge-icon "className" "ampie-badge-icon")
    (aset badge-icon "textContent" "&")
    (.appendChild badge-div badge-icon)
    (.appendChild badge-div tooltip)
    (.setAttribute badge-div "ampie-badge-id" target-id)
    (.addEventListener
      badge-div "mouseover"
      (fn []
        (position-tooltip badge-div)
        (.. badge-div -classList
            (add "ampie-badge-tooltip-visible"))))
    (let [mouse-out? (atom false)]
      (.addEventListener
        badge-div "mouseout"
        (fn []
          (reset! mouse-out? true)
          (js/setTimeout
            (fn []
              (when @mouse-out?
                (.. badge-div -classList
                    (remove "ampie-badge-tooltip-visible"))))
            200)))
      (.addEventListener
        tooltip "mouseover"
        (fn []
          (reset! mouse-out? false))))
    (.addEventListener
      tooltip "mouseout"
      (fn []
        (.. badge-div -classList
            (remove "ampie-badge-tooltip-visible"))))
    (.. js/document -body (appendChild badge-div))
    (position-ampie-badge target badge-div)))

(defn process-links-on-page []
  (let [page-links      (array-seq (.-links js/document))
        unvisited-links (filter #(nil? (.getAttribute % "processed-by-ampie"))
                                page-links)
        current-url     (url/clean-up (.. js/document -location -href))]
    (doseq [link-element unvisited-links]
      (.setAttribute link-element "processed-by-ampie" ""))
    (send-links-to-background
      (->> unvisited-links
           (mapv #(.-href %))
           (filter url/should-store-url?))
      (fn [url->where-saw-it]
        (swap! url->display-info merge url->where-saw-it)
        (doseq [target unvisited-links]
          (let [target-url             (url/clean-up (.-href target))
                target-prior-sightings (url->where-saw-it target-url)]
            (when (and (seq target-prior-sightings)
                       (not= target-url current-url))
              (add-ampie-badge target @next-target-id target-prior-sightings)
              (.setAttribute target "processed-by-ampie" @next-target-id)
              (swap! target-ids conj @next-target-id)
              (swap! next-target-id inc))))))))

(defn screen-update []
  (doseq [target-id @target-ids]
    (let [target (. js/document querySelector
                    (str "[processed-by-ampie=\"" target-id "\"]"))
          badge  (. js/document querySelector
                    (str "[ampie-badge-id=\"" target-id "\"]"))]
      (if (nil? target)
        (do
          (.remove badge)
          (swap! target-ids disj target-id))
        (position-ampie-badge target badge)))))

(defn ^:dev/after-load reloaded []
  (process-links-on-page)
  (let [ticking? (atom false)]
    (. js/window addEventListener "scroll"
       (fn []
         (. js/window requestAnimationFrame
            (fn []
              (screen-update)
              (reset! ticking? false)))
         (when (not @ticking?)
           (reset! ticking? true)))))
  (let [resize-id (atom nil)]
    (. js/window addEventListener "resize"
       (fn []
         (js/clearTimeout resize-id)
         (reset! resize-id (js/setTimeout screen-update 25)))))
  (reset! link-tracking-interval-id
          (. js/window
             (setInterval
               process-links-on-page
               2000))))

(defn info-holder [props]
  [:div
   (for [parent-obj @parent-urls]
     ^{:key (:first-opened parent-obj)} [:p (:url parent-obj)])])

(defn refreshed []
  (reloaded)
  (.. js/chrome
      -runtime
      (sendMessage
        #js {:type "get-past-visits-parents"
             :url  (.. js/window -location -href)}
        (fn [parents]
          (let [parents (->> (js->clj parents :keywordize-keys true)
                             (sort-by :first-opened)
                             (reverse))]
            (reset! parent-urls parents)))))
  (let [info-holder-div (.createElement js/document "div")]
    (set! (.-id info-holder-div) "ampie-holder")
    (-> (.-body js/document)
        (.appendChild info-holder-div))
    (rdom/render [info-holder] info-holder-div)))

(defn init []

  (js/setTimeout refresh-source 500))

(defn ^:dev/before-load before-load []
  (. js/window (clearInterval @link-tracking-interval-id))
  (reset! link-tracking-interval-id nil))
