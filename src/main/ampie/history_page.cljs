(ns ampie.history-page
  (:require [reagent.core :as r])
  (:require [reagent.dom :as rdom])
  (:require [ampie.history :as history]))

(defonce last-visits (r/atom []))

(defn visit-component [{:keys [url time-spent children] :as visit}]
  [:div.visit-component
   (str url " for " time-spent "s")
   (for [[child id] (map vector children (range))]
     ^{:key id} [visit-component child])])

(defn history-page []
  [:div.history-container
   (for [[visit id] (map vector @last-visits (range))]
     ^{:key id} [visit-component visit])])

(defn display-visit-at [visit place]
  (let [{time-spent :timeSpent :keys [url children]} visit
        visit-to-add {:url        url
                      :time-spent time-spent
                      :children   []}
        index        (count (get-in @last-visits place))
        new-place    (conj place index :children)]
    (swap! last-visits update-in place conj visit-to-add)
    (doseq [child-hash children]
      (.then
        (history/get-visit-by-hash child-hash)
        (fn [visit] (display-visit-at visit new-place))))))

(defn display-root-visit [visit]
  (let [{time-spent :timeSpent :keys [url children]} visit
        visit-to-add {:url        url
                      :time-spent time-spent
                      :children   []}
        index        (count @last-visits)]
    (swap! last-visits conj visit-to-add)
    (doseq [child-hash children]
      (.then
        (history/get-visit-by-hash child-hash)
        (fn [visit] (display-visit-at visit
                                      [index :children]))))))

(defn init []
  (history/init-db)
  (.open history/db)
  (->
    (history/get-last-n-root-visits 50)
    (.then
      (fn [visits]
        (println (js->clj visits))
        (doseq [visit visits]
          (display-root-visit visit)))))
  (rdom/render [history-page]
               (. js/document getElementById "history-holder")))
