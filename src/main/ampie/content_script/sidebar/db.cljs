(ns ampie.content-script.sidebar.db
  (:require [malli.core :as m]
            [malli.error :as me]
            [malli.util :as mu]
            [reagent.core :as r]))

(def LoadStatus [:enum :loaded :loading :error])

(def TwitterState
  [:map [:showing [:vector string?]]
   [:ampie/status LoadStatus]
   [:tweet-id->state {:optional true}
    [:map-of :string
     (mu/optional-keys
       [:map [:show-parent boolean?]
        [:show-replies [:enum :showing :hidden :loading]]])]]])

(def HNStoriesState
  [:map [:n-showing int?]])

(def HNCommentsState
  [:map [:n-showing int?]])

(def DomainState
  [:map [:n-showing int?]])

(def BacklinksState
  [:map [:n-showing int?]])

(def Tweet
  [:or
   [:map [:ampie/status [:enum :error :loading :loaded]]]
   [:map
    [:ampie/status LoadStatus]
    [:id_str string?]
    [:in_reply_to_status_id_str [:maybe string?]]
    [:created_at string?]
    [:reply-ids [:vector string?]]
    [:user
     [:map
      [:screen_name string?]]]]])

(def TweetInfo
  [:map
   [:tweet/id string?]
   [:tweet/user-id string?]
   [:tweet/screen-name string?]
   [:tweet/created-at number?]

   [:tweet/retweeted-tweet-id [:maybe string?]]
   [:tweet/retweeted-user-id [:maybe string?]]
   [:tweet/retweeted-screen-name [:maybe string?]]

   [:tweet/in-reply-to-tweet-id [:maybe string?]]
   [:tweet/in-reply-to-screen-name [:maybe string?]]
   [:tweet/in-reply-to-user-id [:maybe string?]]

   [:tweet/quoted-tweet-id [:maybe string?]]
   [:tweet/quoted-user-id [:maybe string?]]
   [:tweet/quoted-screen-name [:maybe string?]]

   [:tweet/reply-ids [:maybe [:vector string?]]]])

(def DB
  (mu/optional-keys
    [:map {:closed true}
     [:url [:sequential string?]]
     [:tweet-id->tweet [:map-of :string Tweet]]
     [:url->ui-state
      [:map-of :string
       (mu/optional-keys
         [:map
          [:twitter TwitterState]
          [:hn_story HNStoriesState]
          [:hn_comment HNCommentsState]
          [:domain DomainState]
          [:ahref BacklinksState]])]]
     [:url->context
      [:map-of :string
       [:map
        [:ampie/status LoadStatus]
        [:twitter {:optional true} [:vector TweetInfo]]
        [:hn_story {:optional true} vector?]
        [:hn_comment {:optional true} vector?]
        [:domain {:optional true} vector?]
        [:ahref {:optional true} vector?]]]]]))

(defonce db (r/atom {}))

(defn validate-db [value]
  (when-not (m/validate DB value)
    (->> (m/explain DB value) me/humanize (js/console.error "Invalid DB: "))
    (js/console.log "DB value: " value)))

(when goog.DEBUG
  (remove-watch db :schema-validation)
  (add-watch db :schema-validation
    (fn [_ _ _old-value new-value]
      (validate-db new-value)))
  (validate-db @db))

(comment
  (require '[clojure.string :as str])
  (try (subs nil 1 2)
       (catch :default e "hello"))
  (pp/pprint (-> @db :url->ui-state first second :twitter) )
  (pp/pprint (-> @db :tweet-id->tweet (get "1091156574993870849")))
  (->> (tweet-id->parent-ids (get @db :tweet-id->tweet)
         "1200868587180830723")
    butlast))
