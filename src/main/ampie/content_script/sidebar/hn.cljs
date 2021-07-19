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
  "Loads the given item ids and puts them in the DB. Returns a Promise that
  never throws, always resolves with a vector of the HN items in the order
  of `item-ids`, including the ones that didn't have to be loaded during
  this call."
  [item-ids]
  (let [get-item        #(get-in @db [:hn-item-id->hn-item %])
        already-loaded? (comp #{:loaded :loading :error} :ampie/status get-item)
        new-ids         (set (remove already-loaded? item-ids))]
    (swap! db #(reduce (fn [db id]
                         (assoc-in db [:hn-item-id->hn-item id :ampie/status] :loading))
                 % new-ids))
    (-> (js/Promise.all
          (for [item-id item-ids]
            (if-not (new-ids item-id)
              (js/Promise.resolve (get-item item-id))
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
                   :ampie/status :error})))))
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

(defn load-next-kids-batch!
  "Fetches and saves the next batch of comments for the given item id,
  and marks them as shown in the item-id's UI state."
  [url item-id]
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

(defn load-next-batch-of-comments!
  "Takes the HN comments mentioning the given URL from the db,
  fetches necessary items from HN, and shows the next batch of them."
  [url]
  (let [{:keys [showing]}     (get-in @db [:url->ui-state url :hn_comment])
        whole-url-context     (get-in @db [:url->context url :hn_comment])
        hn-id->ui-state       (r/cursor db [:url->ui-state url :hn :hn-item-id->state])
        comments-left-to-show (remove (comp (set showing) :hn-item/id)
                                whole-url-context)]
    (swap! db update-in [:url->ui-state url :hn_comment]
      #(merge {:showing []} % {:ampie/status :loading}))
    (let [batch (->> (take default-show-batch-size comments-left-to-show)
                  (map :hn-item/id))]
      (-> (fetch-items! batch)
        (then-fn load-parents! [loaded-items]
          (doseq [child-id (map :id loaded-items)]
            #_(when (= child-id 23662795)
                (js/console.log loaded-items))
            (swap! hn-id->ui-state assoc-in [child-id :full-text] true))
          ;; Load parents for each loaded comment
          (let [with-parents (filter :parent loaded-items)]
            (then-fn (fetch-items! (map :parent with-parents)) [parent-items]
              (doseq [[child-id parent-id parent-item]
                      (map vector (map :id with-parents)
                        (map :parent with-parents)
                        parent-items)]
                (when (= parent-id 23662795)
                  (js/console.log child-id))
                (swap! hn-id->ui-state update parent-id
                  (fn [{:keys [kids-showing] :as state}]
                    (assoc state
                      :kids-showing (cond (not kids-showing) [child-id]
                                          (not ((set kids-showing) child-id))
                                          (conj kids-showing child-id)
                                          :else              kids-showing)
                      :kids-status :loaded))))
              (when (seq parent-items)
                (js/console.log parent-items)
                (js/console.log (mapv vector (map :id with-parents)
                                  (map :parent with-parents)
                                  parent-items))
                (load-parents! parent-items)))))
        (then-fn []
          (swap! db update-in [:url->ui-state url :hn_comment :showing]
            (fnil into []) batch)
          (swap! db assoc-in [:url->ui-state url :hn_comment :ampie/status]
            :loaded))))))

(defn item-id->ultimate-parent-id [item-id]
  (let [parent @(r/cursor db [:hn-item-id->hn-item item-id :parent])]
    (if parent
      (item-id->ultimate-parent-id parent)
      item-id)))
