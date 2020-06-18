(ns ampie.pages.history
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [ampie.db :refer [db]]
            [ampie.visits.db :as visits.db]))

(defonce last-visits (r/atom []))

(defn visit-info [{:keys [title url time-spent] :as visit}]
  [:div.visit-info
   [:a.title {:href (str "http://" url)} (or title url)]
   [:p.time-spent "spent " time-spent "s"]])

(defn visits-column [column]
  [:div.visits-column
   (for [[visit id] (map vector column (range))]
     ^{:key id} [visit-info visit])])

(defn visit-block [{:keys [columns] :as visit}]
  [:div.visit-block
   [:div.visits-column
    [visit-info visit]]
   (for [[column id] (map vector columns (range))]
     ^{:key id} [visits-column column])])

(defn history-page []
  [:div.history-page
   [:div.header
    [:h1 "Browsing history"]]
   [:div.history-container
    (for [[visit id] (map vector @last-visits (range))]
      ^{:key id} [visit-block visit])]])

(defn db-visit->necessary-data [{:keys [url title time-spent]}]
  {:url           url
   :title         title
   :time-spent    time-spent
   :children-from 0
   :children-to   0})

(defn load-visits-column [block-id visit-hashes]
  (.then
    (visits.db/get-visits-info visit-hashes)
    (fn [visits]
      (when (seq visits)
        (let [children-counts (map #(count (:children %)) visits)
              all-children    (apply concat (map :children visits))
              children-sums   (reductions + children-counts)
              children-ints   (map vector (cons 0 children-sums) children-sums)
              visits-to-add   (->> visits
                                (map db-visit->necessary-data)
                                (map (fn [[from to] visit]
                                       (assoc visit :children-from from
                                         :children-to to))
                                  children-ints))]
          (swap! last-visits update-in [block-id :columns]
            conj visits-to-add)
          (load-visits-column block-id all-children))))))

(defn load-root-visit [{time-spent :time-spent :keys [children url title] :as visit}]
  (let [visit-to-add {:url        url
                      :time-spent time-spent
                      :title      title
                      :columns    []}
        index        (atom nil)]
    (swap! last-visits (fn [last-visits]
                         ;; This function has side-effects against recommendations for
                         ;; swap!, but it should be ok since the attempt that works will
                         ;; set the index correctly.
                         (reset! index (count last-visits))
                         (conj last-visits visit-to-add)))
    (load-visits-column @index children)))

(defn init []
  (->
    (visits.db/get-last-n-root-visits 50)
    (.then
      (fn [visits]
        (doseq [visit visits]
          (load-root-visit visit)))))
  (rdom/render [history-page]
    (. js/document getElementById "history-holder")))
