(ns ampie.content-script.sidebar.domain-views
  (:require [ampie.content-script.sidebar.db :refer [db]]
            [reagent.core :as r]
            ["webextension-polyfill" :as browser]
            [ampie.components.basics :as b]
            [clojure.string :as str]
            [ampie.content-script.sidebar.domain
             :refer [load-next-batch-of-domain-links! load-next-batch-of-backlinks!]]
            [ampie.content-script.sidebar.sticky-manager :as sticky-manager]))

(def sources [:hn_comment :hn_story :twitter :ahref :domain :visit])

(def source->name
  {:hn_comment "HN comments"
   :hn_story   "HN threads"
   :twitter    "Tweets"
   :ahref      "Backlinks"
   :domain     "Other pages on that domain"
   :visit      "Amplified pages"})

(defn page-info [page-url state-url]
  (let [overview (r/cursor db [:url->overview page-url])
        status   (:ampie/status @overview)]
    [:div
     (cond (= :loading status)
           [:div "Loading"]
           (= :error status)
           [:div "Couldn't load info for " [:a (b/ahref-opts page-url) page-url]]
           (= :loaded status)
           [:div.flex.gap-1
            [:img.w-4.h-4.flex-shrink-0.rounded
             {:class :mt-0.5
              :alt   (when-not (:favicon-url @overview)
                       "Couldn't load the icon")
              :src   (or (:favicon-url @overview)
                       (.. browser -runtime (getURL "assets/icons/empty-pic.svg")))}]
            [:div.flex.flex-col.gap-1
             [:a.text-link-color.hover:underline.text-base.leading-tight (b/ahref-opts page-url)
              (if (str/blank? (:title @overview)) [:span.break-all page-url] (:title @overview))]
             [:div.flex.flex-row.gap-1
              (let [{:keys [occurrences]} @overview]
                (for [origin sources
                      :let   [info (get occurrences origin)]
                      :when  (and (pos? (:count info))
                               (not= origin :domain))]
                  ^{:key origin}
                  [:div.flex.flex-row.gap-1.border.rounded.p-1 {:class [:pt-0.5 :pb-0.5]}
                   [:div.self-center.w-4.h-4.rounded {:class [(str (name origin) "-icon")]}]
                   [:span (:count info)]]))]]])]))

(defn domain-context [url]
  (let [{:keys [showing ampie/status]} (get-in @db [:url->ui-state url :domain])
        whole-url-context              (get-in @db [:url->context url :domain])
        pages-left-to-show             (remove (comp (set showing) :link/original)
                                         whole-url-context)]
    [:div
     (let [header-content
           [:div.flex.items-center {:class :gap-1.5}
            [:div.domain-icon.w-4.h-4] [:span (str (count whole-url-context) " pages on this domain")]]]
       [sticky-manager/sticky-element
        [:div.text-xl.pb-1 header-content]
        [:div.text-lg.text-link-color.hover:underline.leading-none.pt-1.pb-1.pl-2
         {:role :button}
         header-content]])
     [:div.flex.flex-col.gap-2
      (for [page-url showing]
        ^{:key page-url}
        [page-info page-url url])]
     (cond
       (contains? #{:loading nil} status)
       [:div "Loading domain pages..."]

       (seq pages-left-to-show)
       [:div.text-link-color.hover:underline.rounded-md.bg-blue-50.pt-2.pb-2.mt-1.text-center
        {:role     :button
         :on-click #(load-next-batch-of-domain-links! url)}
        "Load more pages"])]))

(defn backlinks-context [url]
  (let [{:keys [showing ampie/status]} (get-in @db [:url->ui-state url :ahref])
        whole-url-context              (get-in @db [:url->context url :ahref])
        pages-left-to-show             (remove (comp (conj (set showing) url) :page/original)
                                         whole-url-context)]
    [:div
     (let [header-content
           [:div.flex.items-center {:class :gap-1.5}
            [:div.ahref-icon.w-4.h-4] [:span (str (count whole-url-context) " backlinks")]]]
       [sticky-manager/sticky-element
        [:div.text-xl.pb-1 header-content]
        [:div.text-lg.text-link-color.hover:underline.leading-none.pt-1.pb-1.pl-2
         {:role :button}
         header-content]])
     [:div.flex.flex-col.gap-2
      (for [page-url showing]
        ^{:key page-url}
        [page-info page-url url])]
     (cond
       (contains? #{:loading nil} status)
       [:div "Loading backlinks..."]

       (seq pages-left-to-show)
       [:div.text-link-color.hover:underline.rounded-md.bg-blue-50.pt-2.pb-2.mt-1.text-center
        {:role     :button
         :on-click #(load-next-batch-of-backlinks! url)}
        "Load more backlinks"])]))
