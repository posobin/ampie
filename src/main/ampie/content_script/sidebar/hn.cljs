(ns ampie.content-script.sidebar.hn
  (:require [ampie.content-script.sidebar.db :refer [db]]
            [reagent.core :as r]
            ["webextension-polyfill" :as browser]
            [ajax.core :refer [GET]]
            [ampie.macros :refer [then-fn catch-fn]]))

(def default-show-batch-size 10)

(defn hn-item-url [item-id]
  (str "https://hacker-news.firebaseio.com/v0/item/" item-id ".json"))

(defn fetch-items!
  "Loads the given item ids and puts them in the DB."
  [item-ids]
  (let [new-ids (remove (comp #{:loaded :loading :error}
                          #(get-in @db [:hn-item-id->hn-item % :ampie/status]))
                  item-ids)]
    (swap! db #(reduce (fn [db id]
                         (assoc-in db [:hn-item-id->hn-item id :ampie/status] :loading))
                 % new-ids))
    (-> (js/Promise.all
          (for [item-id new-ids]
            (-> (js/Promise.
                  (fn [resolve]
                    (GET (hn-item-url item-id)
                      {:response-format :json
                       :keywords?       true
                       :handler         #(resolve %)})))
              (then-fn [item]
                (let [item (assoc item :ampie/status :loaded)]
                  (swap! db assoc-in [:hn-item-id->hn-item item-id] item)
                  item))
              (catch-fn []
                (swap! db assoc-in [:hn-item-id->hn-item item-id :ampie/status]
                  :error)
                {:id           item-id
                 :ampie/status :error}))))
      (.then vec))))

(defn load-next-batch-of-stories!
  "Takes the HN stories with the given URL from the db, fetches necessary items from HN,
  and shows the next batch of them."
  [url]
  (let [{:keys [showing]}    (get-in @db [:url->ui-state url :hn_story])
        whole-url-context    (get-in @db [:url->context url :hn_story])
        stories-left-to-show (remove (comp (set showing) :hn-item/id)
                               whole-url-context)]
    (swap! db update-in [:url->ui-state url :hn_story]
      #(merge {:showing []} % {:ampie/status :loading}))
    (let [batch (->> (take default-show-batch-size stories-left-to-show)
                  (map :hn-item/id))]
      (-> (fetch-items! batch)
        (then-fn [loaded-items]
          ;; Load first N kids for each story
          (let [child-ids (->> (map :kids loaded-items)
                            (mapcat #(take default-show-batch-size %)))]
            (then-fn (fetch-items! child-ids) []
              (swap! db (fn [db]
                          (reduce #(assoc-in %1
                                     [:url->ui-state url :hn
                                      :hn-item-id->state (:id %2)]
                                     {:kids-showing (->> (:kids %2)
                                                      (take default-show-batch-size)
                                                      (vec))
                                      :kids-status  :loaded})
                            db loaded-items))))))
        (then-fn []
          (swap! db update-in [:url->ui-state url :hn_story :showing]
            (fnil into []) batch)
          (swap! db assoc-in [:url->ui-state url :hn_story :ampie/status]
            :loaded))))))

(defn load-next-kids-batch [url item-id]
  (let [ui-state      (r/cursor db [:url->ui-state url :hn :hn-item-id->state item-id])
        hn-item       (get-in @db [:hn-item-id->hn-item item-id])
        kids-showing  (or (:kids-showing @ui-state) [])
        items-to-load (->> (:kids hn-item)
                        (remove (set kids-showing))
                        (take default-show-batch-size))]
    (swap! ui-state #(merge {:kids-showing []} % {:kids-status :loading}))
    (then-fn (fetch-items! items-to-load) []
      (swap! ui-state assoc
        :kids-status :loaded
        :kids-showing (into kids-showing items-to-load)))))
