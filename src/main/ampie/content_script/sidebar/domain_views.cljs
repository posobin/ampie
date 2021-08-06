(ns ampie.content-script.sidebar.domain-views
  (:require [ampie.content-script.sidebar.db :refer [db]]
            [reagent.core :as r]
            ["webextension-polyfill" :as browser]
            [ampie.components.basics :as b]
            [clojure.string :as str]
            [ampie.content-script.sidebar.hn-views :as hn-views]
            [ampie.content-script.sidebar.amplified-views :as amplified-views]
            [ampie.content-script.sidebar.twitter-views :as twitter-views]
            [ampie.content-script.sidebar.domain
             :refer [load-next-batch-of-domain-links! load-next-batch-of-backlinks!
                     load-origin-context!]]
            [ampie.content-script.sidebar.sticky-manager :as sticky-manager]))

(def sources [:hn_comment :hn_story :twitter :ahref :domain :visit])

(def source->name
  {:hn_comment "HN comments"
   :hn_story   "HN threads"
   :twitter    "Tweets"
   :ahref      "Backlinks"
   :domain     "Other pages on that domain"
   :visit      "Amplified pages"})

(declare backlinks-context domain-context)

(defn origin-context [page-url origin]
  (let [context (r/cursor db [:url->context page-url])
        status  (get-in @context [:ampie/individual-statuses origin])]
    [:div
     (cond (= :loading status)
           [:div "Loading"]
           (= :error status)
           [:div "Couldn't load context =("]
           (= :loaded status)
           (case origin
             :twitter    [twitter-views/twitter-context page-url {:hide-header true}]
             :hn_comment [hn-views/hn-comments-context page-url {:hide-header true}]
             :hn_story   [hn-views/hn-stories-context page-url {:hide-header true}]
             :visit      [amplified-views/amplified-context page-url {:hide-header true}]
             :ahref      [backlinks-context page-url {:hide-header true}]
             :domain     [domain-context page-url {:hide-header true}]
             nil))]))

(defn page-info [page-url state-url]
  (let [overview (r/cursor db [:url->overview page-url])
        ui-state (r/cursor db [:url->ui-state state-url :page-state page-url])
        status   (:ampie/status @overview)]
    [:div
     (cond (= :loading status)
           [:div "Loading"]
           (= :error status)
           [:div "Couldn't load info for " [:a (b/ahref-opts page-url) page-url]]
           (= :loaded status)
           [:div.flex.flex-row.gap-1
            [:img.w-4.h-4.flex-shrink-0.rounded
             {:class :mt-0.5
              :alt   (when-not (:favicon-url @overview)
                       "Couldn't load the icon")
              :src   (or (:favicon-url @overview)
                       (.. browser -runtime (getURL "assets/icons/empty-pic.svg")))}]
            [:div.flex.flex-col.gap-1.flex-grow-0.min-w-0
             [:a.text-link-color.hover:underline.text-base.leading-tight (b/ahref-opts page-url)
              (if (str/blank? (:title @overview)) [:span.break-all page-url] (:title @overview))]
             [:a.hover:underline.block.mb-1.-mt-0dot5.text-gray-500.leading-none
              (b/ahref-opts page-url) page-url]
             [:div.flex.flex-row.gap-1
              (let [{:keys [occurrences]} @overview]
                (for [origin sources
                      :let   [info (get occurrences origin)]
                      :when  (and (pos? (:count info))
                               (not= origin :domain))]
                  ^{:key origin}
                  [:div.flex.flex-row.gap-1.border.rounded.p-1.hover:bg-yellow-100.hover:border-gray-400
                   {:class [:pt-0.5 :pb-0.5]
                    :role  :button
                    :on-click
                    (fn []
                      (when (:open-origin (swap! ui-state update :open-origin
                                            #(when-not (= % origin) origin)))
                        (load-origin-context! page-url origin)))}
                   [:div.self-center.w-4.h-4.rounded {:class [(str (name origin) "-icon")]}]
                   [:span (:count info)]]))]
             (when (:open-origin @ui-state)
               [origin-context page-url (:open-origin @ui-state)])]])]))

(defn domain-context [url {:keys [hide-header]}]
  (let [{:keys [showing ampie/status]} (get-in @db [:url->ui-state url :domain])
        whole-url-context              (get-in @db [:url->context url :domain])
        pages-left-to-show             (remove (comp (set showing) :link/original)
                                         whole-url-context)]
    (when (seq whole-url-context)
      [:div
       (when-not hide-header
         (let [header-content
               [:div.flex.items-center {:class :gap-1.5}
                [:div.domain-icon.w-4.h-4] [:span (str (count whole-url-context) " pages on this domain")]]]
           [sticky-manager/sticky-element
            [:div.text-xl.pb-1 header-content]
            [:div.text-lg.text-link-color.hover:underline.leading-none.pt-1.pb-1.pl-2
             {:role :button}
             header-content]]))
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
          "Load more pages"])])))

(defn backlinks-context [url {:keys [hide-header]}]
  (let [{:keys [showing ampie/status]} (get-in @db [:url->ui-state url :ahref])
        whole-url-context              (get-in @db [:url->context url :ahref])
        pages-left-to-show             (remove (comp (conj (set showing) url) :page/original)
                                         whole-url-context)]
    (when (seq whole-url-context)
      [:div
       (when-not hide-header
         (let [header-content
               [:div.flex.items-center {:class :gap-1.5}
                [:div.ahref-icon.w-4.h-4] [:span (str (count whole-url-context) " backlinks")]]]
           [sticky-manager/sticky-element
            [:div.text-xl.pb-1 header-content]
            [:div.text-lg.text-link-color.hover:underline.leading-none.pt-1.pb-1.pl-2
             {:role :button}
             header-content]]))
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
          "Load more backlinks"])])))
