(ns ampie.content-script.sidebar
  (:require [ampie.url :as url]
            [ampie.time]
            [ampie.content-script.sidebar.db :refer [db]]
            [ampie.content-script.sidebar.twitter :as twitter
             :refer [fetch-tweets! fetch-parent-thread! load-and-show-parent-tweets! load-next-batch-of-tweets!]]
            [malli.core :as m]
            [malli.error :as me]
            [ampie.components.basics :as b]
            [cljs.pprint :as pp :refer [pprint]]
            ["webextension-polyfill" :as browser]
            ["react-shadow-dom-retarget-events" :as retargetEvents]
            [ampie.macros :refer [then-fn]]
            [ampie.content-script.info-bar.tweet :refer [tweet->html-text]]
            [ampie.content-script.demo
             :refer [is-demo-url? get-current-url send-message-to-page]]
            [reagent.dom :as rdom]
            [mount.core :as mount :refer [defstate]]))

(comment
  (m/validate [:= :a] :a)
  (-> @db :url->ui-state first)
  (-> @db :tweet-id->tweet)
  (-> @db :url->ui-state first second :twitter))

(defn load-page-info! [url]
  (send-message-to-page {:type :ampie-load-page-info :url url})
  (let [status (get-in @db [:url->context url :ampie/status])]
    (when-not (#{:loaded :loading} status)
      (swap! db assoc-in [:url->context url :ampie/status]
        :loading)
      (-> (.. browser -runtime
            (sendMessage (clj->js {:type :get-all-url-info
                                   :url  url})))
        (.then #(js->clj % :keywordize-keys true))
        (then-fn [{:keys [occurrences]}]
          (swap! db assoc-in [:url->context url]
            (assoc occurrences :ampie/status :loaded)))))))

(defn set-sidebar-url! [url]
  (some-> (load-page-info! url)
    (.then (fn [{{{:keys [twitter hn_story hn_comment ahref]} url} :url->context}]
             (load-next-batch-of-tweets! url))))
  (swap! db update :url conj url))

(defn tweet-body [{:keys [created_at id_str user] :as tweet} url]
  (let [{:keys [screen_name]} user]
    [:div.flex.flex-col
     [:div.flex.flex-row.gap-1
      [:img.w-6.h-6.rounded-full {:src (:profile_image_url_https user)}]
      [:div.flex.flex-col.mr-2
       #_[:span.font-bold.leading-4 (:name user)]
       [:span.opacity-70 "@" screen_name]]
      [:a.text-link-color.leading-4.ml-auto.min-w-max
       (b/ahref-opts (str "https://twitter.com/" screen_name "/status/" id_str))
       (ampie.time/timestamp->date (js/Date.parse created_at))]]
     [:div (try (tweet->html-text tweet (url/normalize url))
                (catch :default e
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
             [:div.text-link-color.hover-underline.mb-1
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
           [replies reply-ids
            {:url            url
             :hide-tweet-ids #{root-tweet-id}}]])]

       :loading
       "Loading tweet"

       :error
       [:div.p-1 "Couldn't load "
        [:a.text-link-color {:href (str "https://twitter.com/_/status/" root-tweet-id)}
         "tweet"]])]))

(defn twitter-context [url]
  (let [{:keys [showing ampie/status]} (get-in @db [:url->ui-state url :twitter])
        whole-url-context              (get-in @db [:url->context url :twitter])
        tweets-left-to-show            (remove (comp (set showing) :tweet/id)
                                         whole-url-context)]
    [:div [:div.text-lg "Twitter"]
     [:div
      (for [tweet-id showing]
        ^{:key tweet-id}
        [twitter-conversation tweet-id url])]
     (cond
       (contains? #{:loading nil} status)
       [:div "Loading tweets..."]

       (seq tweets-left-to-show)
       [:div.text-link-color.hover:underline
        {:role     :button
         :on-click #(load-next-batch-of-tweets! url)}
        "Load more tweets"])]))

(defn sidebar-component []
  (let [url         (-> @db :url first)
        url-context (get-in @db [:url->context url])]
    [:div.p-2.overscroll-contain.max-h-full.overflow-auto.font-sans
     (if (= :loading (:ampie/status url-context))
       [:div "Loading..."]
       [twitter-context url])]))

(defn setup-sidebar-html-element []
  (let [sidebar-div     (. js/document createElement "div")
        shadow-root-el  (. js/document createElement "div")
        shadow          (. shadow-root-el (attachShadow #js {"mode" "open"}))
        tailwind        (. js/document createElement "link")
        sidebar-styling (. js/document createElement "link")]
    (set! (.-rel tailwind) "stylesheet")
    (set! (.-rel sidebar-styling) "stylesheet")
    (.setAttribute shadow-root-el "style"  "display: none;")
    (set! (.-href sidebar-styling) (.. browser -runtime (getURL "assets/sidebar.css")))
    ;; Put onload for the stylesheet that is attached last to the DOM
    (set! (.-onload tailwind) #(.setAttribute shadow-root-el "style" ""))
    (set! (.-href tailwind) (.. browser -runtime (getURL "assets/tailwind.css")))
    (set! (.-className shadow-root-el) "ampie-sidebar-holder")
    (set! (.-className sidebar-div) "sidebar-container")
    {:call-after-render (fn []
                          (. shadow (appendChild sidebar-styling))
                          (. shadow (appendChild tailwind))
                          (. shadow (appendChild sidebar-div))
                          (retargetEvents shadow)
                          (.. js/document -body (appendChild shadow-root-el)))
     :container         sidebar-div}))

(defn display-sidebar! []
  (js/console.log "loading sidebar")
  (when (. js/document querySelector ".ampie-sidebar-holder")
    (when goog.DEBUG
      (js/alert "ampie: attaching a second sidebar")
      (js/console.trace))
    (throw "Sidebar already exists"))
  (let [{:keys [container call-after-render]} (setup-sidebar-html-element)]
    (set-sidebar-url! (get-current-url))
    (rdom/render [sidebar-component] container)
    (call-after-render)))

(defn remove-sidebar! []
  (let [element (.. js/document -body (querySelector ".ampie-sidebar-holder"))]
    (when element (.. js/document -body (removeChild element)))))

(defstate sidebar-state
  :start (display-sidebar!)
  :stop (do (remove-sidebar!)
            #_((:remove-sidebar @sidebar-state))))
