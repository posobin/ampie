(ns ampie.content-script.info-bar
  (:require ["webextension-polyfill" :as browser]
            ["react-shadow-dom-retarget-events" :as retargetEvents]
            [reagent.dom :as rdom]
            [reagent.core :as r]
            [ampie.url :as url]
            [ampie.interop :as i]
            [taoensso.timbre :as log]
            [goog.string :as gstring]
            [goog.string.format]))

(defn timestamp->date [timestamp]
  (let [date  (js/Date. timestamp)
        day   (.getDate date)
        month (.getMonth date)
        month-name
        (["January" "February" "March" "April" "May" "June" "July" "August"
          "September" "October" "November" "December"]
         month)
        year  (.getFullYear date)]
    (gstring/format "%s %d, %d" month-name day year)))

(defn seen-at [sources]
  (when (seq sources)
    [:div.seen-at
     [:div.header [:i.icon.history] "Previously seen at"]
     (for [{:keys [url visit-hash first-opened title] :as info} sources]
       ^{:key visit-hash}
       (try
         [:div.row
          [:div.title
           [:a {:href url} title]]
          [:div.info
           [:div.domain (url/get-domain url)]
           [:div.date (timestamp->date first-opened)]]]
         (catch js/Error e
           (log/error info))))]))

(defn mini-tags [reference-counts]
  (for [[source-name count] reference-counts]
    ^{:key source-name}
    [:div.mini-tag
     [(keyword (str "i.icon." (name source-name)))]
     count]))

(defn bottom-row [url reference-counts]
  [:div.bottom-row
   [:div.url url]
   [mini-tags reference-counts]])

(defn window [{:keys [overscroll-handler window-atom]}]
  (letfn [(change-height [el delta-y]
            (let [current-height    (js/parseFloat
                                      (. (js/getComputedStyle el)
                                        getPropertyValue "height"))
                  min-height        40
                  lowest-child-rect (.. el -lastElementChild
                                      getBoundingClientRect)
                  children-height   (- (+ (. lowest-child-rect -y)
                                         (. lowest-child-rect -height))
                                      (.. el getBoundingClientRect -y))
                  max-height        children-height
                  new-height        (+ current-height
                                      delta-y)]
              (cond
                (< new-height min-height)
                (do (set! (.. el -style -height) (str min-height "px"))
                    (overscroll-handler :down (- min-height new-height)))

                (> new-height max-height)
                (do (set! (.. el -style -height) (str max-height "px"))
                    (overscroll-handler :up (- new-height max-height)))

                :else
                (do (set! (.. el -style -height) (str new-height "px"))
                    ;; Return false not to propagate the scroll
                    false))))]
    (into [:div.window
           {:ref
            (fn [el]
              (when el
                (swap! window-atom assoc :ref el)
                (swap! window-atom assoc
                  :update-height (partial change-height el))
                (set! (. el -onwheel)
                  (fn [evt] (change-height el (. evt -deltaY))))))}]
      (r/children (r/current-component)))))

(defn elements-stack []
  (let [children       (r/children (r/current-component))
        children-info  (repeat (count children) (r/atom {}))
        update-heights (fn [index direction delta]
                         (if (= index (dec (count children)))
                           ;; Propagate the scroll event
                           true
                           ((:update-height
                             @(nth children-info (inc index)))
                            (if (= direction :up)
                              delta
                              (- delta)))))]
    (into [:div.stack]
      (map #(vector window
              {:window-atom        %2
               :overscroll-handler (partial update-heights %3)}
              %1)
        (r/children (r/current-component)) children-info (range)))))

(defn info-bar [{page-info :page-info}]
  (let [{seen  :seen-at
         :keys [normalized-url reference-counts]} @page-info]
    [:div.info-bar
     [elements-stack
      [seen-at seen]
      [seen-at seen]]
     [bottom-row normalized-url reference-counts]]))

(defn remove-info-bar []
  (let [element (.. js/document -body (querySelector ".ampie-info-bar-holder"))]
    (when element (.. js/document -body (removeChild element)))))

(defn update-current-page-info! [page-info]
  (let [current-url (.. js/window -location -href)]
    (swap! page-info assoc :url current-url
      :normalized-url (url/clean-up current-url))
    (.. js/chrome
      -runtime
      (sendMessage
        (clj->js {:type :add-seen-urls
                  :urls [current-url]})
        (fn [js-url->where-seen]
          (let [seen-at (->> (js->clj js-url->where-seen :keywordize-keys true)
                          first)]
            (swap! page-info assoc :seen-at seen-at)))))))

(defn display-info-bar []
  (let [info-bar-div   (. js/document createElement "div")
        shadow-root-el (. js/document createElement "div")
        shadow         (. shadow-root-el (attachShadow #js {"mode" "open"}))
        shadow-style   (. js/document createElement "link")
        page-info      (r/atom {})]
    (set! (.-rel shadow-style) "stylesheet")
    (set! (.-href shadow-style) (.. browser -runtime (getURL "assets/info-bar.css")))
    (set! (.-className shadow-root-el) "ampie-info-bar-holder")
    (set! (.-className info-bar-div) "info-bar-container")
    (update-current-page-info! page-info)
    (rdom/render [info-bar {:page-info page-info}] info-bar-div)
    (. shadow (appendChild shadow-style))
    (. shadow (appendChild info-bar-div))
    (retargetEvents shadow)
    (.. js/document -body (appendChild shadow-root-el))))
