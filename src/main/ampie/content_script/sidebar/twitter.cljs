(ns ampie.content-script.sidebar.twitter
  (:require [ampie.content-script.sidebar.db :refer [db]]
            ["webextension-polyfill" :as browser]
            [ampie.macros :refer [then-fn]]))

(defn hash-map-by [key seq]
  (->> (map #(vector (key %) %) seq) (into {})))

(defn tweet-id->parent-ids [tweet-id->tweet tweet-id]
  (loop [id     tweet-id
         result []]
    (if id
      (recur
        (:in_reply_to_status_id_str (tweet-id->tweet id))
        (conj result id))
      result)))

(defn fetch-tweets!
  "Loads the given tweet ids from the backend and saves them to the db.
  Returns a promise that resolves with the tweet-id->tweet map for
  the fetched tweets."
  [tweet-ids]
  (let [new-ids (remove (comp #{:loaded :loading :error}
                          #(get-in @db [:tweet-id->tweet % :ampie/status]))
                  tweet-ids)]
    (swap! db #(reduce
                 (fn [db id]
                   (assoc-in db [:tweet-id->tweet id :ampie/status] :loading))
                 % new-ids))
    (-> (.. browser -runtime
          (sendMessage (clj->js {:type :get-tweets
                                 :ids  new-ids})))
      (.then #(js->clj % :keywordize-keys true))
      (then-fn [tweets] (->> (map #(assoc % :ampie/status :loaded) tweets)
                          (hash-map-by :id_str)))
      (then-fn [id->tweet]
        (let [couldnt-load
              (->> (map #(hash-map :ampie/status :error :id_str %) new-ids)
                (hash-map-by :id_str))]
          (swap! db update :tweet-id->tweet merge couldnt-load id->tweet)
          id->tweet)))))

(defn fetch-parent-thread!
  "Either returns nil when there was an error loading the given tweet id before
  or loads the parent thread for the given thread id (including the tweet with
  the given tweet-id), saves the tweets to the db and returns the id->tweet map."
  [tweet-id]
  (let [status-path [:tweet-id->tweet tweet-id :ampie/status]]
    (when (contains? #{nil :error} (get-in @db status-path))
      (swap! db assoc-in status-path :loading)
      (-> (.. browser -runtime
            (sendMessage (clj->js {:type     :get-parent-thread
                                   :tweet-id tweet-id})))
        (.catch #(doto % js/console.log))
        (.then #(js->clj % :keywordize-keys true))
        (then-fn [tweets]
          (update-in (->> (map #(assoc % :ampie/status :loaded) tweets)
                       (hash-map-by :id_str))
            [tweet-id :ampie/status] #(or % :error)))
        (then-fn [id->tweet]
          (swap! db assoc-in status-path :loading)
          (swap! db update :tweet-id->tweet merge id->tweet)
          id->tweet)))))

(defn load-and-show-parent-tweets!
  "Given a tweet id, loads it and its parents and sets show-parent to true
  for the first 10 loaded parents."
  [tweet-id url]
  (-> (fetch-parent-thread! tweet-id)
    (then-fn []
      (let [parents (->> (tweet-id->parent-ids (get @db :tweet-id->tweet) tweet-id)
                      ;; Need to drop the first tweet in the chain because
                      ;; it doesn't have a parent
                      (take 10))]
        (doseq [parent-id parents
                :let      [next-id (get-in @db [:tweet-id->tweet parent-id
                                                :in_reply_to_status_id_str])
                           next-tweet (get-in @db [:tweet-id->tweet next-id])]]
          (when next-tweet
            (swap! db assoc-in
              [:url->ui-state url :twitter :tweet-id->state
               parent-id :show-parent]
              true)))))))

(defn load-and-show-replies!
  "Given a tweet id, loads replies to it and sets show-replies to :showing
  for the given tweet id."
  [tweet-id url]
  (let [reply-ids         (get-in @db [:tweet-id->tweet tweet-id :reply-ids])
        show-replies-path [:url->ui-state url :twitter :tweet-id->state tweet-id
                           :show-replies]]
    (swap! db assoc-in show-replies-path :loading)
    (-> (fetch-tweets! reply-ids)
      (then-fn [] (swap! db assoc-in show-replies-path :showing)))))

(def default-show-batch-size 10)

(defn tweet-info->relevant-tweet-ids [tweet-info]
  (->> [:tweet/id :tweet/quoted-tweet-id :tweet/retweeted-tweet-id :tweet/in-reply-to-tweet-id]
    (mapv #(% tweet-info))
    (concat (:tweet/reply-ids tweet-info))
    (filterv identity)))

(defn load-next-batch-of-tweets!
  "Takes the twitter context for the given url from the db, fetches necessary tweets,
  and shows the next batch of tweets."
  [url]
  (let [{:keys [showing]}   (get-in @db [:url->ui-state url :twitter])
        whole-url-context   (get-in @db [:url->context url :twitter])
        tweets-left-to-show (remove (comp (set showing) :tweet/id)
                              whole-url-context)]
    (swap! db update-in [:url->ui-state url :twitter]
      #(merge {:showing []} % {:ampie/status :loading}))
    (let [batch    (take default-show-batch-size tweets-left-to-show)
          new-ids  (mapcat tweet-info->relevant-tweet-ids batch)
          root-ids (map :tweet/id batch)]
      (-> (fetch-tweets! new-ids)
        (then-fn []
          (swap! db update-in [:url->ui-state url :twitter :showing]
            (fnil into []) root-ids)
          (swap! db assoc-in [:url->ui-state url :twitter :ampie/status]
            :loaded))))))
