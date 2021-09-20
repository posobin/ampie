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
       [:map {:closed true}
        [:full-text boolean?]
        [:kids-showing [:vector int?]]
        [:kids-status LoadStatus]])]]])

(def HNCommentsState
  [:map {:closed true}
   [:showing [:vector int?]]
   [:ampie/status LoadStatus]])

(def DomainState
  [:map
   [:showing [:vector string?]]
   [:ampie/status LoadStatus]])

(def BacklinksState
  [:map
   [:showing [:vector string?]]
   [:ampie/status LoadStatus]])

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
   [:and
    [:or
     [:map [:descendants number?]]
     [:map [:dead [:= true]]]]
    [:map
     [:time number?]
     [:type [:enum "poll" "story"]]
     [:title string?]
     [:text {:optional true} string?]
     [:kids [:vector number?]]
     [:ampie/status LoadStatus]
     [:id number?]
     [:score number?]
     [:url {:optional true} string?]
     [:by string?]]]])

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

(def HNCommentInfo
  [:map {:closed true}
   [:link/original string?]
   [:link/normalized string?]
   [:hn-item/author string?]
   [:hn-item/item-type [:= "comment"]]
   [:hn-item/posted-at number?]
   [:hn-item/id number?]
   [:hn-item/title nil?]
   [:hn-item/descendants nil?]
   [:hn-item/score nil?]])

(def SameDomainLinksInfo
  [:map {:closed true}
   [:link/original string?]
   [:link/normalized string?]
   [:link/score number?]])

(def OverviewOrigins
  [:enum :twitter :hn_story :hn_comment :domain :ahref
   :ahref_same_site :visit :upvote :pocket_link :history_visit
   :weekly_post])

(def UrlOverview
  [:or
   [:map
    [:ampie/status [:enum :loading :error]]]
   [:map {:closed true}
    [:favicon-url [:or string? nil?]]
    [:occurrences [:map-of OverviewOrigins [:map [:count number?]]]]
    [:title [:or string? nil?]]
    [:date {:optional true} [:or string? nil?]]
    [:author {:optional true} [:or string? nil?]]
    [:ampie/status LoadStatus]]
   [:map
    [:error string?]
    [:occurrences [:map-of OverviewOrigins [:map [:count number?]]]]
    [:ampie/status LoadStatus]]])

(def PageState
  [:map
   [:open-origin [:or nil? OverviewOrigins]]])

(def VisitInfo
  [:map
   [:users/username string?]
   [:users/tag string?]
   [:users/profile-image-url [:or string? nil?]]
   [:link/original string?]
   [:link/normalized string?]
   [:visit/created-at number?]
   [:visit/title string?]
   [:visit/tag string?]
   [:visit/reaction [:or [:enum "like" "to read"] nil?]]
   [:visit/comment [:or nil? string?]]
   [:visit/fav-icon-url [:or nil? string?]]])

(def Visit
  [:and VisitInfo
   [:map [:ampie/status LoadStatus]]])

(def url-context-origins-schemas
  {:twitter    [:vector TweetInfo]
   :hn_story   [:vector HNStoryInfo]
   :hn_comment [:vector HNCommentInfo]
   :domain     vector?
   :ahref      vector?
   :visit      [:vector VisitInfo]})

(def url-context-origins (keys url-context-origins-schemas))

(def UserState
  [:map [:logged-out? boolean?]])

(def DB
  (mu/optional-keys
    [:map {:closed true}
     [:user-state UserState]
     [:url [:sequential string?]]
     [:tweet-id->tweet [:map-of :string Tweet]]
     [:hn-item-id->hn-item [:map-of number? HNItem]]
     [:visit-tag->visit [:map-of :string Visit]]
     [:url->ui-state
      [:map-of :string
       (mu/optional-keys
         [:map
          [:twitter TwitterState]
          [:hn_story HNStoriesState]
          [:hn_comment HNCommentsState]
          [:hn HNItemsState]
          [:domain DomainState]
          [:ahref BacklinksState]
          [:page-state [:map-of :string PageState]]])]]
     [:url->overview
      [:map-of :string UrlOverview]]
     [:url->context
      [:map-of :string
       (into [:map
              [:ampie/status {:optional true} LoadStatus]
              [:ampie/individual-statuses {:optional true}
               (mu/optional-keys
                 [:map
                  [:twitter LoadStatus]
                  [:hn_story LoadStatus]
                  [:hn_comment LoadStatus]
                  [:domain LoadStatus]
                  [:ahref LoadStatus]
                  [:visit LoadStatus]])]]
         (mapv (fn [[key schema]] [key {:optional true} schema])
           url-context-origins-schemas))]]]))

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
  (pp/pprint (-> @db :url->ui-state first second :domain))
  (pp/pprint (-> @db :url->context first second :twitter))
  (js/console.log @db)
  (-> @db :url->ui-state first second :hn :hn-item-id->state (get 23662795))
  (pp/pprint (me/humanize (m/explain HNItem (-> @db :hn-item-id->hn-item (get 23662795))))))
