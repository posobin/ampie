(ns ampie.content-script.info-bar
  (:require [reagent.dom :as rdom]
            [reagent.core :as r]
            [ampie.url :as url]))

(defn timestamp->date [timestamp]
  "June 1, 2020")

(defn seen-at [sources]
  (when (seq sources)
    [:div.seen-at
     [:div.header [:i.icon.history] "Previously seen at"]
     (for [{:keys [url timestamp title]} sources]
       ^{:key (str url " " timestamp)}
       [:div.row
        [:div.title title]
        [:div.info
         [:div.domain (url/get-domain url)]
         [:div.date (timestamp->date timestamp)]]])]))

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
        #js {:type  "send-links-on-page"
             :links #js [current-url]}
        (fn [js-url->where-seen]
          (let [seen-at (->> js-url->where-seen
                          js->clj
                          first
                          second
                          (map url/clean-up)
                          (map #(assoc {} :title % :url %)))]
            (swap! page-info assoc :seen-at seen-at)))))))

(defn display-info-bar []
  (let [info-bar-div (. js/document createElement "div")
        page-info    (r/atom {})]
    (aset info-bar-div "className" "ampie-info-bar")
    (update-current-page-info! page-info)
    (rdom/render [info-bar {:page-info page-info}] info-bar-div)
    (.. js/document -body (appendChild info-bar-div))))
