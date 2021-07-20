(ns ampie.content-script.sidebar.hn-views
  (:require [ampie.content-script.sidebar.db :refer [db]]
            [ampie.time]
            [ampie.components.basics :as b]
            [ampie.url :as url]
            [reagent.core :as r]
            [ampie.content-script.sidebar.hn :as hn]
            [ampie.content-script.sidebar.sticky-controller :as sticky-controller]
            ["webextension-polyfill" :as browser]))

(defn element->hiccup [^js el nurl]
  (let [children-seq
        (doall
          (for [[idx child] (map-indexed vector (.-childNodes el))]
            (vary-meta
              (element->hiccup child nurl)
              assoc :key idx)))]
    (vary-meta
      (case (.-nodeName el)
        "BODY"  children-seq
        "PRE"   [:pre.overflow-y-auto
                 (-> (.-childNodes el)
                   first
                   (element->hiccup nurl))]
        "P"     [:p children-seq]
        "A"     [:a.text-link-color.hover:underline
                 (assoc (b/ahref-opts (.-href el))
                   :class (when (= nurl (url/normalize (.-href el)))
                            [:pl-0.5 :pr-0.5 :bg-blue-50 :rounded :border]))
                 (.-innerText el)]
        "I"     [:i children-seq]
        "CODE"  [:code (.-innerText el)]
        "#text" [:<> (.-textContent el)])
      assoc :text-length (count (or (.-innerText el)
                                  (.-textContent el))))))

(def long-comment-length 280)

(defn hn-item-id->text [hn-item-id nurl show-whole-text toggle-show-whole-text]
  (let [text          @(r/cursor db [:hn-item-id->hn-item hn-item-id :text])
        parser        (js/DOMParser.)
        paragraphs    (filter (comp pos? :text-length meta)
                        (-> (.parseFromString parser text "text/html")
                          (.querySelector "body")
                          (element->hiccup nurl)))
        p-lengths     (reductions + 0
                        (map (comp :text-length meta) paragraphs))
        p-with-length (map vector paragraphs p-lengths)
        long-comment  (> (-> p-with-length last second) long-comment-length)]
    [:<>
     [:div
      (doall
        (for [[p prior-length] p-with-length
              :when            (or (<= prior-length long-comment-length) show-whole-text)]
          (with-meta p {:key prior-length})))]
     (when (and long-comment (not show-whole-text))
       [:div.text-link-color.hover:underline
        {:role     :button
         :on-click toggle-show-whole-text}
        "Show more..."])]))

(defn hn-item-id->url [item-id]
  (str "https://news.ycombinator.com/item?id=" item-id))

(defn hn-comment [comment-id url]
  (let [comment @(r/cursor db [:hn-item-id->hn-item comment-id])
        state   (r/cursor db [:url->ui-state url :hn :hn-item-id->state comment-id])]
    (cond (= (:ampie/status comment) :loading) [:div "Loading comment..."]
          (= (:ampie/status comment) :error)   [:div "Error loading comment"]
          :else
          [:div.border-l-2 {:class [:pl-1.5 :pt-0.5 :pb-0.5]}
           [:div
            (if (:deleted comment)
              [:span.text-gray-400 "[deleted]"]
              [hn-item-id->text comment-id (url/normalize url) (:full-text @state)
               #(swap! state update :full-text (fnil not false))])
            [:div.flex.text-grey-500.mt-1
             (when (-> (:kids comment) count pos?)
               [:div.flex-grow
                {:role  :button
                 :class [:text-link-color "hover:underline"]
                 :on-click
                 (fn []
                   (hn/fetch-items! (:kids comment))
                   (swap! state update :kids-showing
                     #(if (= (set %) (set (:kids comment))) [] (:kids comment))))}
                (let [count (count (:kids comment))]
                  (str count " repl" (if (= count 1) "y" "ies")))])
             [:span.ml-auto.text-gray-500 (:by comment)]
             [:a.ml-2.hover:underline.text-blue-400.hover:text-link-color
              (b/ahref-opts (hn-item-id->url comment-id))
              (ampie.time/timestamp->date (* (:time comment) 1000))]]]
           (when (seq (:kids-showing @state))
             [:div.ml-4.mt-2.flex.flex-col.gap-1
              (for [kid-id (:kids-showing @state)]
                ^{:key kid-id}
                [hn-comment kid-id url])])])))

(defn hn-story [item-id url]
  (let [story @(r/cursor db [:hn-item-id->hn-item item-id])]
    (case (:ampie/status story)
      nil [:div "Loading..."]
      :loading
      [:div "Loading..."]
      :error
      [:div "Error loading the thread"]
      :loaded
      [:div
       [:div.flex.gap-1.items-baseline
        [:span.text-lg.leading-none.mb-1 (:title story)]
        (let [n-comments (:descendants story)]
          [:a.flex-none.hover:underline.text-link-color
           (b/ahref-opts (hn-item-id->url item-id))
           (:descendants story) " comment" (when (not= n-comments 1) "s")])]
       (when (and (:url story)
               (not= (url/normalize url) (url/normalize (:url story))))
         [:a.text-link-color.hover:underline.block.mb-1
          (b/ahref-opts (:url story)) (:url story)])
       (when (:text story)
         [:div.pb-2.pt-1
          [hn-item-id->text item-id (url/normalize url) true identity]])
       (let [{:keys [kids-showing kids-status]}
             (get-in @db [:url->ui-state url :hn :hn-item-id->state item-id])]
         [:<>
          (for [child-id kids-showing]
            ^{:key child-id}
            [:div.pb-2
             [hn-comment child-id url]])
          (cond (= kids-status :loading)
                [:div "Loading..."]
                (= kids-status :error)
                [:div "Error loading comments"]
                (< (count kids-showing) (count (:kids story)))
                [:div.inline-block.text-link-color.p-2.pt-1.pb-1.border.rounded-md.hover:bg-blue-50.mb-1
                 {:on-click #(hn/load-next-kids-batch! url item-id)
                  :role     :button}
                 "Show more comments"])])])))

(def hello (r/atom {:hello     {:world 0}
                    :something {1 1
                                2 2}
                    :chosen    1}))

(defn hn-stories-context [url]
  (let [{:keys [showing ampie/status]} (get-in @db [:url->ui-state url :hn_story])
        whole-url-context              (get-in @db [:url->context url :hn_story])
        stories-left-to-show           (remove (comp (set showing) :hn-item/id)
                                         whole-url-context)]
    [:div
     [:div.mb-1
      [sticky-controller/sticky-element
       [:div.text-xl.pb-1 "HN threads"]]]
     [:div.flex.flex-col.gap-2
      (for [item-id showing]
        ^{:key item-id}
        [hn-story item-id url])]
     (cond
       (contains? #{:loading nil} status)
       [:div "Loading threads..."]

       (seq stories-left-to-show)
       [:div.text-link-color.hover:underline.rounded-md.bg-blue-50.pt-2.pb-2.mt-1.text-center
        {:role     :button
         :on-click #(hn/load-next-batch-of-stories! url)}
        [:span "Show more threads around this URL"]])]))

(defn hn-context-comment [item-id url]
  (let [ultimate-parent @(r/track hn/item-id->ultimate-parent-id item-id)]
    [hn-story ultimate-parent url]))

(defn hn-comments-context [url]
  (let [{:keys [showing ampie/status]} @(r/cursor db [:url->ui-state url :hn_comment])
        whole-url-context              @(r/cursor db [:url->context url :hn_comment])
        comments-left-to-show          (remove (comp (set showing) :hn-item/id)
                                         whole-url-context)]
    [:div
     [:div.mb-1
      [sticky-controller/sticky-element
       [:div.text-xl.pb-1 "HN comments"]]]
     [:div.flex.flex-col.gap-2
      (doall
        (for [item-id showing]
          ^{:key item-id}
          [hn-context-comment item-id url]))]
     (cond
       (contains? #{:loading nil} status)
       [:div "Loading threads..."]

       (seq comments-left-to-show)
       [:div.text-link-color.hover:underline.rounded-md.bg-blue-50.pt-2.pb-2.mt-1.text-center
        {:role     :button
         :on-click #(hn/load-next-batch-of-comments! url)}
        [:span "Show more comments with this URL"]])]))
