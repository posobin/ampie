(ns ampie.components.visit
  (:require [reagent.core :as r]
            [ampie.url :as url]
            [ampie.time :as time]
            [ampie.components.basics :as b]))

(defn visit [{_ :visit on-delete :on-delete}]
  (let [v            visit ; Since we are also using visit argument below
        current-date (-> (js/Date.) (.getTime) (time/timestamp->date))
        mouse-in     (r/atom false)
        deleted      (r/atom false)
        dragging     (r/atom false)]
    (fn [{{:keys [title first-opened url normalized-url children time-spent]
           :as   visit}
          :visit
          on-delete :on-delete}]
      (let [visit-date (time/timestamp->date first-opened)
            visit-time (time/timestamp->time first-opened)]
        (when-not @deleted
          [:div.visit-entry
           {:class       [(when @mouse-in "to-delete")
                          (when @dragging "dragging")]
            :draggable   "true"
            :on-drag-end #(reset! dragging false)
            :on-drag-start
            (fn [^js evt]
              (.. evt -dataTransfer (setData "text" (:url visit)))
              (.. evt -dataTransfer (setData "text/uri-list" (:url visit)))
              (.. evt -dataTransfer (setData "edn" (pr-str visit)))
              (set! (.. evt -dataTransfer -effectAllowed) "copy")
              (reset! dragging true))}
           [:div.visit-info
            [:a.title (assoc (b/ahref-opts url) :draggable "false") (or title url)]
            [:div.additional-info
             [:span.domain (url/get-domain url)]
             [:span.time-spent
              {:class (cond (> time-spent 1800)
                            "extra-long-visit"
                            (> time-spent 300)
                            "long-visit")}
              (time/seconds->str time-spent)]
             (into
               [:span.first-opened]
               (if (= current-date visit-date)
                 ["@" [:span.time visit-time]]
                 [[:span.date visit-date]
                  ", " [:span.time visit-time]]))
             (when on-delete
               [:button.delete {:on-click      (fn []
                                                 (on-delete visit)
                                                 (reset! deleted true))
                                :on-mouse-out  #(reset! mouse-in false)
                                :on-mouse-over #(reset! mouse-in true)}
                (str "Delete"
                  (when (seq children) " subtree"))])]]
           [:div.child-visits
            (for [child children :when (not (int? child))]
              ^{:key (:visit-hash child)}
              [v {:visit     child
                  :on-delete on-delete}])]])))))
