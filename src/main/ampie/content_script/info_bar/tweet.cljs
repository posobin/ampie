(ns ampie.content-script.info-bar.tweet
  (:require ["webextension-polyfill" :as browser]
            ["runes" :as runes]
            [clojure.string :as string]
            [goog.string]
            [ampie.utils :refer [assoc-when]]
            [ampie.url :as url]
            [ampie.components.basics :as b]
            [taoensso.timbre :as log]))

(defn- tweet->html-text
  [{{:keys [screen_name]} :user
    {urls :urls}          :entities
    :keys                 [full_text]}
   selected-normalized-url]
  (let [indices      (concat [0]
                       (mapcat :indices urls)
                       [(count full_text)])
        text-substrs (->> (partition 2 indices)
                       (map (fn [[start end]] (runes/substr full_text start (- end start))))
                       (map goog.string/unescapeEntities))
        links        (map-indexed
                       (fn [idx {:keys [expanded_url display_url]}]
                         ^{:key idx} [:a (assoc (b/ahref-opts expanded_url)
                                           :class (when (= selected-normalized-url
                                                          (url/normalize expanded_url))
                                                    :selected-url))
                                      display_url])
                       urls)
        result       (concat [(first text-substrs)]
                       (interleave links (rest text-substrs)))]
    result))

(defn tweet [{{urls :urls} :entities
              :keys        [created_at id_str
                            replies retweet-of reply-to quote-of
                            user retweeted-by]
              :as          tweet-info}
             selected-normalized-url]
  [:div.tweet.row
   (when reply-to [:div.reply-to [tweet reply-to selected-normalized-url]])
   (when (seq retweeted-by)
     [:div.retweeted-by
      (str "Retweeted by "
        (string/join ", " (map :name retweeted-by)))])
   [:div.tweet-inside
    [:img.profile-pic {:src (:profile_image_url_https user)}]
    [:div.tweet-column
     [:div.info
      (when (:name user) [:div.name (:name user)])
      [:div.author (str "@" (:screen_name user))]
      [:a.date
       (b/ahref-opts (str "https://twitter.com/" (:screen_name user) "/status/" id_str))
       (ampie.time/timestamp->date (js/Date.parse created_at))]]
     [:div.text (tweet->html-text tweet-info selected-normalized-url)]
     (when quote-of [:div.quote [tweet quote-of selected-normalized-url]])]]
   (when (seq replies)
     [:div.replies
      (for [{:keys [id_str] :as reply} replies]
        ^{:key id_str} [tweet reply selected-normalized-url])])])

(defn hydrate-tweets [tweets]
  (let [tweet-ids   (map (comp :tweet-id-str :info) tweets)
        replies-to  (mapcat (juxt (comp :tweet-id-str :reply-to :info)
                              (comp :tweet-id-str :quote-of :info)
                              (comp :tweet-id-str :retweet-of :info))
                      tweets)
        replies-ids (mapcat (comp #(mapv :tweet-id-str %) :replies :info) tweets)]
    (-> (.. browser -runtime
          (sendMessage (clj->js {:type :get-tweets
                                 :ids  (filter identity
                                         (-> (set tweet-ids)
                                           (into replies-to)
                                           (into replies-ids)))})))
      (.then #(js->clj % :keywordize-keys true))
      (.then (fn [response-tweets]
               (reduce (fn [acc tweet] (assoc acc (:id_str tweet) tweet))
                 {}
                 response-tweets)))
      (.then
        (fn [id->tweet]
          (let [hydrated-tweets
                (->> (map :info tweets)
                  (map
                    (fn [{:keys [tweet-id-str replies reply-to quote-of retweet-of]}]
                      (some-> (id->tweet tweet-id-str)
                        (assoc :replies
                          (->> (map
                                 (fn [reply]
                                   (let [res (-> reply :tweet-id-str id->tweet)]
                                     (when-not res
                                       (log/error (keys id->tweet))
                                       (log/error reply))
                                     ;; Return only when the tweet is a reply
                                     ;; or a direct quote tweet
                                     (when (or (nil? (:in_reply_to_status_id_str res))
                                             (= (:in_reply_to_status_id_str res)
                                               tweet-id-str))
                                       res)))
                                 replies)
                            (filter identity)))
                        (assoc-when reply-to
                          :reply-to (id->tweet (:tweet-id-str reply-to)))
                        (assoc-when quote-of
                          :quote-of (id->tweet (:tweet-id-str quote-of)))
                        (assoc-when retweet-of
                          :retweet-of (id->tweet (:tweet-id-str retweet-of))))))
                  (filter identity))
                retweeted-tweets
                (->> (group-by (comp :id_str :retweet-of) hydrated-tweets)
                  (#(dissoc % nil))
                  (map (fn [[tweet-id retweets]]
                         (-> (first retweets) :retweet-of
                           (assoc :retweeted-by (map :user retweets))))))
                retweeted-id->tweet
                (->> (map (juxt :id_str identity) retweeted-tweets)
                  (into {}))
                unseen-retweets (remove (comp (set tweet-ids) :id_str) retweeted-tweets)]
            (as-> hydrated-tweets $
              (map (fn [{id :id_str :as hydrated-tweet}]
                     (cond (:retweet-of hydrated-tweet) nil
                           (retweeted-id->tweet id)
                           (assoc hydrated-tweet
                             :retweeted-by (:retweeted-by (retweeted-id->tweet id)))
                           :else                        hydrated-tweet))
                $)
              (filter identity $)
              (concat $ unseen-retweets))))))))
