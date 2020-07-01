(ns ampie.pages.history
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [taoensso.timbre :as log]
            [ampie.url :as url]
            [ampie.db :refer [db]]
            [ampie.time :as time]
            [ampie.visits.db :as visits.db]))

(defn delete-visit-subtree! [visit]
  (loop [[visit & rest] [visit]]
    (when visit
      (visits.db/delete-visit-keeping-children! visit)
      (recur (into (or rest []) (:children visit))))))

(defn visit-entry [_]
  (let [current-date (-> (js/Date.) (.getTime) (time/timestamp->date))
        mouse-in     (r/atom false)
        deleted      (r/atom false)]
    (fn [{:keys [title first-opened url normalized-url children time-spent]
          :as   visit}]
      (let [visit-date (time/timestamp->date first-opened)
            visit-time (time/timestamp->time first-opened)]
        (when-not @deleted
          [:div.visit-entry {:class (when @mouse-in "to-delete")}
           [:div.visit-info
            [:a.title {:href url} (or title url)]
            [:div.additional-info
             [:span.domain (url/get-domain url)]
             [:span.time-spent time-spent]
             (into
               [:span.first-opened]
               (if (= current-date visit-date)
                 ["opened at " [:span.time visit-time]]
                 ["opened on " [:span.date visit-date]
                  ", " [:span.time visit-time]]))
             [:button.delete {:on-click      (fn []
                                               (delete-visit-subtree! visit)
                                               (reset! deleted true))
                              :on-mouse-out  #(reset! mouse-in false)
                              :on-mouse-over #(reset! mouse-in true)}
              "Delete subtree"]]]
           [:div.child-visits
            (for [child children :when (not (int? child))]
              ^{:key (:visit-hash child)} [visit-entry child])]])))))

(defn history-page [history]
  [:div.history-page
   [:div.header [:h1 "Browsing history"]
    [:div.notice
     "Ampie doesn't support deleting the urls seen on the deleted page "
     "when deleting from history yet. E.g. if you delete "
     [:code "example.org"]" entry below, and on that website there was a link to "
     [:code "ampie.app"]", the "
     [:div.icon.history-icon]
     " indicator on "[:code "ampie.app"]" will still show the same number as before. "
     "But the "[:code "example.org"]" visit won't be present when viewing "
     "where the url "[:code "ampie.app"]" was seen."]]
   [:div.history-container
    (for [visit (:origin-visits @history)]
      ^{:key (:visit-hash visit)} [visit-entry visit])]])

(defn load-children-visits
  [history visit-path]
  (let [visit           (get-in @history visit-path)
        children-hashes (get-in @history (conj visit-path :children))]
    (swap! history update :n-loading inc)
    (-> (visits.db/get-visits-info children-hashes)
      (.then
        (fn [children]
          (swap! history assoc-in (conj visit-path :children) children)
          (doseq [[index child] (map-indexed vector children)]
            (load-children-visits history (conj visit-path :children index)))))
      (.catch
        (fn [error]
          (log/error "Couldn't load history:" error "for visit" visit)))
      (.finally
        #(swap! history update :n-loading dec)))))

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
                    (load-children-visits history [:origin-visits (+ len index)])))
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
