(ns ampie.pages.history
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [taoensso.timbre :as log]
            [ampie.components.visit :as components.visit]
            [ampie.db :refer [db]]
            [ampie.visits :as visits]
            [ampie.visits.db :as visits.db]))

(defn delete-visit-subtree! [visit]
  (loop [[visit & rest] [visit]]
    (when visit
      (visits.db/delete-visit-keeping-children! visit)
      (recur (into (or rest []) (:children visit))))))

(defn history-page [history]
  [:div.history-page.content
   [:div.header [:h1 "Browsing history"]
    [:div.notice
     "This history is unrelated to the browser's history: for now you have "
     "to delete entries from both this page and the browser's history page "
     "if you want to delete them completely. "
     "This history is stored locally on your computer, it will not be shared with "
     "ampie without your approval."]]
   [:div.history-container
    (for [visit (:origin-visits @history)]
      ^{:key (:visit-hash visit)}
      [components.visit/visit
       {:visit     visit
        :on-delete delete-visit-subtree!}])]])

(def scroll-listener (atom nil))

(defn ^:dev/before-load stop []
  (when @scroll-listener
    (. js/window removeEventListener @scroll-listener)
    (reset! scroll-listener nil)))

(defn ^:dev/after-load init []
  (let [history   (r/atom {:origin-visits  []
                           :last-timestamp nil
                           :n-loading      0})
        scrolling (r/atom false)
        load-more
        (fn []
          (swap! history update :n-loading inc)
          (-> (visits.db/get-last-n-origin-visits
                50 (:last-timestamp @history)
                (set (map #(or (:visit-hash %) %)
                       (:origin-visits @history))))
            (.then
              (fn [{:keys [origin-visits last-timestamp]}]
                (let [{new-origin-visits :origin-visits}
                      (swap! history update :origin-visits into origin-visits)
                      len (- (count new-origin-visits) (count origin-visits))]
                  (swap! history assoc :last-timestamp last-timestamp)
                  (doseq [[index visit] (map-indexed vector origin-visits)]
                    (visits/load-children-visits history
                      [:origin-visits (+ len index)])))
                (swap! history update :n-loading dec)))
            (.catch
              (fn [error]
                (log/error "Couldn't load history:" error)))
            (.finally
              #(swap! history update :n-loading dec))))]
    (load-more)
    (let [listener-id
          (.addEventListener js/window
            "scroll"
            (fn []
              (when (> (+ (.-scrollY js/window) 200)
                      (- (.. js/document -body -offsetHeight)
                        (.-outerHeight js/window)))
                (when-not @scrolling
                  (reset! scrolling true)
                  (.finally
                    (load-more)
                    #(reset! scrolling false))))))]
      (reset! scroll-listener listener-id))
    (rdom/render [history-page history]
      (. js/document getElementById "history-holder"))))
