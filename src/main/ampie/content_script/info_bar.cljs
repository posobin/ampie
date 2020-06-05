(ns ampie.content-script.info-bar
  (:require [reagent.dom :as rdom]
            [reagent.core :as r]
            [ampie.url :as url]
            [taoensso.timbre :as log]
            [goog.string :as gstring]
            [goog.string.format]))

(defn timestamp->date [timestamp]
  (let [date  (js/Date. timestamp)
        day   (.getDay date)
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
     (for [{:keys [url visit-hash firstOpened title] :as info} sources]
       ^{:key visit-hash}
       (try
         [:div.row
          [:div.title title]
          [:div.info
           [:div.domain (url/get-domain url)]
           [:div.date (timestamp->date firstOpened)]]]
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


(defn info-bar [{page-info :page-info}]
  (let [{seen  :seen-at
         :keys [url reference-counts]} @page-info]
    [:div.info-bar-container
     [seen-at seen]
     [bottom-row url reference-counts]]))


(defn remove-info-bar []
  (let [element (.. js/document -body (querySelector ".ampie-info-bar"))]
    (when element (.. js/document -body (removeChild element)))))

(defn update-current-page-info! [page-info]
  (let [current-url (url/clean-up (.. js/window -location -href))]
    (swap! page-info assoc :url current-url)
    (.. js/chrome
      -runtime
      (sendMessage
        (clj->js {:type  :add-seen-links
                  :links [current-url]})
        (fn [js-url->where-seen]
          (let [seen-at (->> (js->clj js-url->where-seen :keywordize-keys true)
                          first)]
            (swap! page-info assoc :seen-at seen-at)))))))

(defn display-info-bar []
  (let [info-bar-div (. js/document createElement "div")
        page-info    (r/atom {})]
    (aset info-bar-div "className" "ampie-info-bar")
    (update-current-page-info! page-info)
    (rdom/render [info-bar {:page-info page-info}] info-bar-div)
    (.. js/document -body (appendChild info-bar-div))))
