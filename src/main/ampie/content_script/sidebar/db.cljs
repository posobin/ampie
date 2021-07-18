(ns ampie.content-script.sidebar.db
  (:require [malli.core :as m]
            [malli.error :as me]
            [cljs.pprint :as pp :refer [pprint]]
            [malli.util :as mu]
            [reagent.core :as r]))

(def LoadStatus [:enum :loaded :loading :error])

(def TwitterState
  [:map {:closed true}
   [:showing [:vector string?]]
   [:ampie/status LoadStatus]
   [:tweet-id->state {:optional true}
    [:map-of :string
     (mu/optional-keys
       [:map [:show-parent boolean?]
        [:show-replies [:enum :showing :hidden :loading]]])]]])

(def HNStoriesState
  [:map {:closed true}
   [:showing [:vector int?]]
   [:ampie/status LoadStatus]])

(def HNItemsState
  [:map {:closed true}
   [:hn-item-id->state {:optional true}
    [:map-of int?
     (mu/optional-keys
       [:map
        [:full-text boolean?]
        [:kids-showing [:vector int?]]
        [:kids-status LoadStatus]])]]])

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

(def HNItem
  [:or
   [:map [:ampie/status [:enum :loading :error]
          :id number?]]
   [:map [:deleted [:= true]]]
   [:map
    [:type [:= "comment"]]
    [:by string?]
    [:id number?]
    [:kids {:optional true} [:vector number?]]
    [:parent number?]
    [:text string?]
    [:time number?]
    [:ampie/status LoadStatus]]
   [:map
    [:time number?]
    [:type [:enum "poll" "story"]]
    [:descendants number?]
    [:title string?]
    [:text {:optional true} string?]
    [:kids [:vector number?]]
    [:ampie/status LoadStatus]
    [:id number?]
    [:score number?]
    [:url {:optional true} string?]
    [:by string?]]])

(def HNStoryInfo
  [:map {:closed true}
   [:link/original string?]
   [:link/normalized string?]
   [:hn-item/title string?]
   [:hn-item/author string?]
   [:hn-item/item-type [:enum "story" "poll"]]
   [:hn-item/descendants number?]
   [:hn-item/posted-at number?]
   [:hn-item/id number?]
   [:hn-item/score number?]])

(def DB
  (mu/optional-keys
    [:map {:closed true}
     [:url [:sequential string?]]
     [:tweet-id->tweet [:map-of :string Tweet]]
     [:hn-item-id->hn-item [:map-of number? HNItem]]
     [:url->ui-state
      [:map-of :string
       (mu/optional-keys
         [:map
          [:twitter TwitterState]
          [:hn_story HNStoriesState]
          [:hn_comment HNCommentsState]
          [:hn HNItemsState]
          [:domain DomainState]
          [:ahref BacklinksState]])]]
     [:url->context
      [:map-of :string
       [:map
        [:ampie/status LoadStatus]
        [:twitter {:optional true} [:vector TweetInfo]]
        [:hn_story {:optional true} [:vector HNStoryInfo]]
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
  (pp/pprint (-> @db :url->context first second :hn_story) )
  (-> @db :url->ui-state first second :hn :hn-item-id->state (get 19297283))
  (pp/pprint (me/humanize (m/explain HNItem (-> @db :hn-item-id->hn-item (get 19297283))))))
