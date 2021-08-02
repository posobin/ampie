(ns ampie.content-script.sidebar.amplified-views
  (:require [ampie.content-script.sidebar.db :refer [db]]
            [reagent.core :as r]
            ["webextension-polyfill" :as browser]
            [ampie.components.basics :as b]
            [ampie.time]
            [ampie.content-script.sidebar.sticky-manager :as sticky-manager]
            [ampie.content-script.sidebar.amplified :refer [load-next-batch-of-amplified-links!]]))

(defn visit-info [visit-tag url]
  (let [{:keys [users/username users/profile-image-url visit/created-at
                visit/reaction visit/comment ampie/status]}
        @(r/cursor db [:visit-tag->visit visit-tag])]
    (cond
      (contains? #{nil :error} status)
      [:div.p-1.border "Error loading amplification"]
      (= :loading status)
      [:div.p-1.border "Loading..."]
      (= :loaded status)
      [:div.flex.flex-col.border.rounded-md.p-1.pl-2.pr-2
       [:div.flex.flex-row.gap-1
        [:a.flex.flex-row.gap-1.mr-2.group
         (b/ahref-opts (str "https://ampie.app/" username))
         [:img.w-6.h-6.rounded-full {:src profile-image-url}]
         [:span.text-gray-500.leading-none.group-hover:underline.self-center "@" username]]
        [:span reaction]
        [:span.leading-4.ml-auto.min-w-max
         (ampie.time/timestamp->date (js/Date. (* created-at 1000)))]]
       [:div comment]])))

(defn amplified-context [url {:keys [hide-header]}]
  (let [{:keys [showing ampie/status]} (get-in @db [:url->ui-state url :visit])
        whole-url-context              (get-in @db [:url->context url :visit])
        pages-left-to-show             (remove (comp (set showing) :visit/tag)
                                         whole-url-context)]
    [:div
     (when-not hide-header
       (let [header-content
             [:div.flex.items-center {:class :gap-1.5}
              [:div.visit-icon.w-4.h-4] [:span (str (count whole-url-context) " amplifications")]]]
         [sticky-manager/sticky-element
          [:div.text-xl.pb-1 header-content]
          [:div.text-lg.text-link-color.hover:underline.leading-none.pt-1.pb-1.pl-2
           {:role :button}
           header-content]]))
     [:div.flex.flex-col.gap-2
      (for [visit-tag showing]
        ^{:key visit-tag}
        [visit-info visit-tag url])]
     (cond
       (contains? #{:loading nil} status)
       [:div "Loading amplifications..."]

       (seq pages-left-to-show)
       [:div.text-link-color.hover:underline.rounded-md.bg-blue-50.pt-2.pb-2.mt-1.text-center
        {:role     :button
         :on-click #(load-next-batch-of-amplified-links! url)}
        "Load more amplifications"])]))
