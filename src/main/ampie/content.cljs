(ns ampie.content
  (:require [reagent.core :as r])
  (:require [reagent.dom :as rdom])
  (:require [shadow.cljs.devtools.client.browser :as shadow.browser])
  (:require [shadow.cljs.devtools.client.hud :as shadow.hud]))

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
  (when (not-empty urls)
    (.. js/chrome
        -runtime
        (sendMessage
          #js {:type     "send-links-on-page"
               :links    (clj->js urls)
               :page-url (.. js/window -location -href)}
          #(response-handler (js->clj %))))))

;; Check if the element is displayed on the page, element has to be not null.
(defn is-style-visible? [element]
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

(defn get-offsets [element]
  (let [element-rect (.getBoundingClientRect element)]
    (if (is-fixed? element)
      [(.-left element-rect) (.-top element-rect)]
      [(+ (.-left element-rect)
          (.-scrollX js/window)
          (.-offsetWidth element))
       (+ (.-top element-rect)
          (.-scrollY js/window))])))

(defn position-ampie-badge [target badge]
  (let [[pos-x pos-y] (get-offsets target)]
    ;; Set visibility of the badge depending on the visibility of the target
    (let [target-visible? (and (some? (.-offsetParent target))
                               (is-style-visible? target)
                               #_(or (nil? (.-parentElement target))
                                     (is-style-visible? (.-parentElement target)))
                               (is-style-visible? (.-offsetParent target)))
          badge-hidden?   (.. badge -classList (contains "hidden"))]
      (cond (and (not target-visible?) (not badge-hidden?))
            (.. badge -classList (add "hidden"))

            (and target-visible? badge-hidden?)
            (.. badge -classList (remove "hidden")))
      (when target-visible?
        (set! (.. badge -style -left) (str pos-x "px"))
        (set! (.. badge -style -top) (str pos-y "px"))))))

(defn add-ampie-badge [target target-id]
  (let [badge-div (. js/document createElement "div")]
    (aset badge-div "className" "ampie-badge")
    (.setAttribute badge-div "ampie-badge-id" target-id)
    (.. js/document -body (appendChild badge-div))
    (position-ampie-badge target badge-div)))

(defn process-links-on-page []
  (let [page-links      (array-seq (.-links js/document))
        unvisited-links (filter #(nil? (.getAttribute % "processed-by-ampie"))
                                page-links)]
    (doseq [link-element unvisited-links]
      (.setAttribute link-element "processed-by-ampie" ""))
    (send-links-to-background
      (mapv #(.-href %) unvisited-links)
      (fn [url->where-saw-it]
        (swap! url->display-info merge url->where-saw-it)
        (doseq [target unvisited-links]
          (let [unvisited-links (filter #(not= %))])
          (add-ampie-badge target @next-target-id)
          (.setAttribute target "processed-by-ampie" @next-target-id)
          (swap! target-ids conj @next-target-id)
          (swap! next-target-id inc))))))

(defn screen-update [])

(defn ^:dev/after-load reloaded []
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
