(ns ampie.content-script.sidebar.twitter-views
  (:require [ampie.content-script.sidebar.twitter :as twitter
             :refer [fetch-tweets! load-and-show-parent-tweets! load-next-batch-of-tweets!]]
            [ampie.url :as url]
            [ampie.content-script.sidebar.db :refer [db]]
            [ampie.time]
            [ampie.content-script.info-bar.tweet :refer [tweet->html-text]]
            [ampie.content-script.sidebar.sticky-manager :as sticky-manager]
            [ampie.components.basics :as b]))

(defn tweet-body [{:keys [created_at id_str user] :as tweet} url]
  (let [{:keys [screen_name]} user]
    [:div.flex.flex-col
     [:div.flex.flex-row.gap-1
      [:a.flex.flex-row.gap-1.mr-2.group
       (b/ahref-opts (str "https://twitter.com/" screen_name))
       [:img.w-6.h-6.rounded-full {:src (:profile_image_url_https user)}]
       [:span.text-gray-500.leading-none.group-hover:underline.self-center "@" screen_name]]
      [:a.text-link-color.leading-4.ml-auto.min-w-max
       (b/ahref-opts (str "https://twitter.com/" screen_name "/status/" id_str))
       (ampie.time/timestamp->date (js/Date.parse created_at))]]
     [:div (try (tweet->html-text tweet (url/normalize url))
                (catch :default _
                  (js/console.log tweet)))]]))

(declare tweet-component)

(defn replies [reply-ids tweet-opts]
  [:<>
   (doall
     (for [reply-id reply-ids
           :let     [reply (get-in @db [:tweet-id->tweet reply-id])]
           :when    (= (:ampie/status reply) :loaded)]
       ^{:key reply-id}
       [:div.rounded.bg-gray-50.mt-1.p-2.pb-1.pt-1.border
        [tweet-component reply-id (assoc tweet-opts :hide-parent true)]]))])

(defn tweet-component [tweet-id {:keys [url showing-replies hide-parent hide-tweet-ids]
                                 :or   {hide-tweet-ids #{}}}]
  (when-not (contains? hide-tweet-ids tweet-id)
    (let [{:keys [show-parent show-replies]}
          (get-in @db [:url->ui-state url :twitter :tweet-id->state tweet-id])
          {:keys [ampie/status reply-ids in_reply_to_status_id_str quoted_status_id_str] :as tweet}
          (get-in @db [:tweet-id->tweet tweet-id])
          filtered-reply-ids (remove #(or (contains? hide-tweet-ids %) (contains? showing-replies %))
                               reply-ids)]
      (case status
        nil
        [:div.text-link-color.hover-underline
         {:role     :button
          :on-click (fn [] (fetch-tweets! [tweet-id]))}
         "Load tweet"]

        :error
        [:div "Couldn't load the tweet"]

        :loading
        [:div "Loading tweet..."]

        [:div
         (when (and in_reply_to_status_id_str (not hide-parent))
           (if show-parent
             [:div.pb-1
              [tweet-component in_reply_to_status_id_str
               {:url             url
                :showing-replies #{tweet-id}
                :hide-tweet-ids  (conj hide-tweet-ids tweet-id)}]]
             [:div.text-link-color.hover:underline.mb-1
              {:role :button
               :on-click
               (fn []
                 (load-and-show-parent-tweets! in_reply_to_status_id_str url)
                 (swap! db assoc-in [:url->ui-state url :twitter
                                     :tweet-id->state tweet-id :show-parent]
                   true))}
              "⬑ load parent"]))
         [tweet-body tweet url]
         (when quoted_status_id_str
           ;; TODO: remove duplication here and in twitter-conversation
           [:div.pl-2.border-l-4.border-indigo-300
            [tweet-component quoted_status_id_str
             {:url             url
              :showing-replies #{tweet-id}
              :hide-tweet-ids  (conj hide-tweet-ids tweet-id)}]])
         (when (seq filtered-reply-ids)
           (cond
             (contains? #{:hidden nil} show-replies)
             [:div.text-link-color.hover:underline
              {:role     :button
               :on-click #(twitter/load-and-show-replies! tweet-id url)}
              "Load replies"]
             (= :loading show-replies)
             [:div.pl-2 "Loading..."]
             :else
             [:div.pl-2.flex.flex-col.pb-1
              [replies filtered-reply-ids
               {:url            url
                :hide-tweet-ids (conj hide-tweet-ids tweet-id)}]]))]))))

(defn twitter-conversation [root-tweet-id url]
  (let [{:keys [ampie/status reply-ids in_reply_to_status_id_str quoted_status_id_str] :as tweet}
        (get-in @db [:tweet-id->tweet root-tweet-id])]
    [:div.rounded.mt-1.border.border-blue-150.p-2.pt-1
     (case status
       :loaded
       [:div
        (let [in-reply-to (get-in @db [:tweet-id->tweet in_reply_to_status_id_str])]
          (when (= (:ampie/status in-reply-to) :loaded)
            [tweet-component in_reply_to_status_id_str
             {:url             url
              :showing-replies #{root-tweet-id}
              :hide-tweet-ids  #{root-tweet-id}}]))
        [tweet-body tweet url]
        (when quoted_status_id_str
          ;; TODO: remove duplication here and in tweet-component
          [:div.pl-2.border-l-4.border-indigo-300
           [tweet-component quoted_status_id_str
            {:url             url
             :showing-replies #{root-tweet-id}
             :hide-tweet-ids  #{root-tweet-id}}]])
        (when (seq reply-ids)
          [:div.pl-2.flex.flex-col.pb-1
           [replies (distinct reply-ids)
            {:url            url
             :hide-tweet-ids #{root-tweet-id}}]])]

       :loading
       "Loading tweet"

       :error
       [:div.p-1 "Couldn't load "
        [:a.text-link-color {:href (str "https://twitter.com/_/status/" root-tweet-id)}
         "tweet"]])]))

(defn twitter-context [url {:keys [hide-header]}]
  (let [{:keys [showing ampie/status]} (get-in @db [:url->ui-state url :twitter])
        whole-url-context              (get-in @db [:url->context url :twitter])
        tweets-left-to-show            (remove (comp (set showing) :tweet/id)
                                         whole-url-context)]
    [:div
     (when-not hide-header
       (let [header-content
             [:div.flex.items-center {:class :gap-1.5}
              [:div.twitter-icon.w-4.h-4] [:span (str (count whole-url-context) " tweets")]]]
         [sticky-manager/sticky-element
          [:div.text-xl.pb-1 header-content]
          [:div.text-lg.text-link-color.hover:underline.leading-none.pt-1.pb-1.pl-2
           {:role :button}
           header-content]]))
     [:div
      (for [tweet-id showing]
        ^{:key tweet-id}
        [twitter-conversation tweet-id url])]
     (cond
       (contains? #{:loading nil} status)
       [:div "Loading tweets..."]

       (seq tweets-left-to-show)
       [:div.text-link-color.hover:underline.rounded-md.bg-blue-50.pt-2.pb-2.mt-1.text-center
        {:role     :button
         :on-click #(load-next-batch-of-tweets! url)}
        "Load more tweets"])]))
