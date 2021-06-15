(ns ampie.content-script.info-bar.tweet
  (:require ["webextension-polyfill" :as browser]
            ["runes" :as runes]
            [clojure.string :as string]
            [goog.string]
            [ampie.utils :refer [assoc-when]]
            [ampie.time]
            [ampie.url :as url]
            [ampie.components.basics :as b]
            [taoensso.timbre :as log]))

(defmulti substr-entity->html (comp :type second))

(defmethod substr-entity->html :url
  [[_ {:keys [expanded_url display_url selected]}]]
  [:a (assoc (b/ahref-opts expanded_url)
        :class (when selected :selected-url))
   display_url])

(defmethod substr-entity->html :photo [[_ _]] [:<>])
(defmethod substr-entity->html :user-mention [[text {:keys [screen_name]}]]
  [:a (b/ahref-opts (str "https://twitter.com/" screen_name)) text])
(defmethod substr-entity->html :default [[text _]] [:<> text])

(defn- tweet-image [{:keys [expanded_url media_url_https]}]
  [:div.image
   [:a (b/ahref-opts expanded_url)
    [:img {:src (str media_url_https "?name=small")}]]])

(defn- tweet-images [images]
  [:div.images
   (for [image images]
     ^{:key (:id_str image)}
     [tweet-image image])])

(defn- tweet->html-text
  [{{:keys [urls media
            user_mentions]} :entities
    :keys                   [full_text]}
   selected-normalized-url]
  (let [entity->indices  (fn [type]
                           (fn [{[l r] :indices :as info}]
                             [[l (assoc info :type type)] [r nil]]))
        urls             (map #(assoc % :selected
                                 (= selected-normalized-url
                                   (-> % :expanded_url url/normalize)))
                           urls)
        urls-indices     (->> (concat [[0 nil]]
                                (mapcat (entity->indices :url) urls)
                                (mapcat (entity->indices :photo) media)
                                (mapcat (entity->indices :user-mention) user_mentions)
                                [[(count full_text) nil]])
                           (sort (fn [[idx1 obj1] [idx2 obj2]]
                                   (if (= idx1 idx2)
                                     (cond (= obj1 obj2 nil) 0
                                           (nil? obj1)       -1
                                           (nil? obj2)       1
                                           ;; Shouldn't happen, means there is
                                           ;; a zero-width entity
                                           :else             (compare obj1 obj2))
                                     (compare idx1 idx2)))))
        substrs-entities (->> (partition 2 1 urls-indices)
                           (map (fn [[[start entity] [end]]]
                                  [(-> full_text
                                     (runes/substr start (- end start))
                                     goog.string/unescapeEntities)
                                   entity])))
        result           (->> (map substr-entity->html substrs-entities)
                           (map-indexed #(vary-meta %2 assoc :key %1)))]
    result))

(comment
  ;; TODO This tweet is not parsed correctly rn
  ;; It links to docs.google.com: https://twitter.com/jebarjonet/status/1079473806173970433
  ;; The indices are off, both the ones returned by runes and the js .substring
  (def s (str "Looking for a free privacy policy and terms of use generator? ⚖️ "
           "Go on https://t.co/o4yTZY3BGi =&gt; create a new doc from template =&gt; "
           "select \"Privacy policy\" or \"Terms of use\" templates 🤑 \n"
           "No data selling or endless process asking for money in the end with this one https://t.co/RzJnJ0FBa8"))

  (.-length "hello")
  (.-length "🤑")
  ;; These two substrings should evaluate to the full links from the string s
  (runes/substr "👨‍👩‍👧‍👦" 0 1)
  (.substring s 71 94)
  (.substring s 270 293))

(defn tweet [{{media :media} :entities
              :keys          [created_at id_str replies reply-to quote-of
                              user retweeted-by]
              :as            tweet-info}
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
   (when (seq media) [tweet-images media])
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
                  (map (fn [[_ retweets]]
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
