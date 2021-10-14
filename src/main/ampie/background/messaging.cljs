(ns ampie.background.messaging
  (:require [ampie.visits.db :as visits.db]
            [ampie.url :as url]
            [ampie.seen-urls :as seen-urls]
            [ampie.tabs.core :as tabs]
            [ampie.interop :as i]
            [ampie.links :as links]
            [ampie.background.backend :as backend]
            [ampie.background.analytics :as analytics]
            [ampie.background.top-10k-domains :refer [top-10k-domains]]
            [ampie.settings :refer [settings]]
            [ampie.background.link-cache-sync :as link-cache-sync]
            [ampie.macros :refer [then-fn]]
            [clojure.set]
            [clojure.string]
            [taoensso.timbre :as log]
            ["webextension-polyfill" :as browser]
            [mount.core])
  (:require-macros [mount.core :refer [defstate]]))

(defn get-past-visits-parents [{url :url} _sender]
  (-> (visits.db/get-past-visits-to-the-url url 5)
    (.then
      (fn [past-visits]
        (->> past-visits
          (map :parent)
          (filter some?)
          visits.db/get-visits-info)))
    (.then
      (fn [past-visits-parents]
        (->> past-visits-parents
          (map #(select-keys % [:url :first-opened]))
          i/clj->js)))))

(defn get-nurls-info
  "Returns a Promise that resolves to a clj seq of maps :source -> [info+]."
  [normalized-urls]
  (.then
    (js/Promise.all
      (array
        (links/get-links-with-nurls normalized-urls)
        (seen-urls/find-where-saw-nurls normalized-urls)))
    (fn [[seen-at-seq history]]
      (map #(assoc %1 :history %2) seen-at-seq history))))

(defn add-seen-urls
  "Takes `request` and `sender`, filters out the pages with the same top-level
  domain as in `page-url`, saves them to to `seen-urls` and returns a Promise
  that resolves with the js object describing for each of the urls
  in the request where it was seen before, of the form
  {:url url :twitter twitter-links :normalized-url nurl :hn hn}."
  [{:keys [urls page-url] :as _request} sender]
  (let [sender              (i/js->clj sender)
        tab-id              (-> sender :tab :id)
        visit-hash          (:visit-hash (@@tabs/open-tabs tab-id))
        normalized-page-url (url/normalize page-url)
        _domain             (url/get-top-domain-normalized normalized-page-url)
        normalized-urls     (map url/normalize urls)]
    #_(when page-url
        (let [filtered-urls (filter #(not= domain (url/get-top-domain-normalized %))
                              normalized-urls)]
          (-> (visits.db/get-visit-by-hash visit-hash)
            (.then
              (fn [{url :url timestamp :first-opened}]
                (if (= url page-url)
                  (seen-urls/add-seen-nurls filtered-urls visit-hash timestamp)
                  (log/error "Couldn't add seen-urls because page-url"
                    "didn't match: expected" url "got" page-url)))))))
    ;; The result that the promise returns will be sent to the sender.
    (.then
      (.. browser -storage -local (get "show-badges"))
      (fn [show-badges]
        (.then (links/get-nurls-badge-sightings-counts normalized-urls)
          (fn [counts]
            (.then (get-nurls-info normalized-urls)
              (fn [infos]
                (->> (map #(assoc %1 :url %2 :normalized-url %3 :badge-sightings %4)
                       infos urls normalized-urls counts)
                  (map (fn [info]
                         (update info :history
                           (fn [history]
                             (vec
                               (filter #(not= visit-hash (:visit-hash %))
                                 history))))))
                  (assoc {:show-badges (or (not show-badges) (aget show-badges "show-badges"))}
                    :urls-info)
                  clj->js)))))))))

(defn inc-badge-sightings [{:keys [url]} _sender]
  (links/inc-nurl-badge-sightings-counts (url/normalize url))
  nil)

(defn get-url-info-based-on-local-cache
  "Returns a Promise that resolves to a js map :source -> [info+]."
  [{url :url} _sender include-links-info]
  (let [normalized-url (url/normalize url)]
    (.then
      (js/Promise.all
        (array
          (seen-urls/find-where-saw-nurls [normalized-url])
          (if include-links-info
            (-> (links/get-links-with-nurl normalized-url)
              (.then (fn [source->links]
                       (reduce-kv #(into %1 %3) [] source->links)))
              (.then links/link-ids-to-info))
            (links/get-links-with-nurl normalized-url))))
      (fn [[seen links]]
        (let [seen (first seen)]
          (clj->js (assoc links :history seen :normalized-url normalized-url)))))))

(defn get-urls-overview [{:keys [urls fast-but-incomplete src]}]
  (-> (backend/get-urls-overview urls fast-but-incomplete src)
    (.then #(clj->js % :keyword-fn i/name-with-ns))
    (.catch #(clj->js % :keyword-fn i/name-with-ns))))

(defn get-url-context [{:keys [url]}]
  (-> (backend/get-url-context url)
    (.then #(clj->js % :keyword-fn i/name-with-ns))
    (.catch #(clj->js % :keyword-fn i/name-with-ns))))

(defn get-partial-url-context [{:keys [url origin]}]
  (-> (backend/get-partial-url-context url origin)
    (.then #(clj->js % :keyword-fn i/name-with-ns))
    (.catch #(clj->js % :keyword-fn i/name-with-ns))))

(defn get-links-pages-info [{link-ids :link-ids} _sender]
  (-> (backend/get-links-pages-info link-ids)
    (.then clj->js)
    (.catch clj->js)))

(defn get-tweets [{ids :ids} _sender]
  (.then (backend/get-tweets ids) clj->js clj->js))

(defn get-parent-thread [{tweet-id :tweet-id} _sender]
  (.then (backend/get-parent-thread tweet-id) clj->js clj->js))

(defn get-prefixes-info [{url :url} _sender]
  (let [normalized-url (url/normalize url)
        prefixes       (rest
                         (url/get-prefixes-normalized normalized-url))]
    (.then (js/Promise.all
             (for [prefix prefixes]
               (links/get-links-starting-with prefix)))
      (fn [prefixes-info]
        (clj->js (map #(vector %1 %2) prefixes prefixes-info))))))

(defn open-weekly-links []
  (.. browser -tabs
    (create #js {:url (.. browser -runtime (getURL "weekly-links.html"))})))

(defn should-show-domain-links?
  "Resolves to a boolean: whether should automatically open the \"links at\"
  section for the given url."
  [{url :url} sender]
  (if (:auto-show-domain-links @@settings)
    (let [normalized-url (url/normalize url)
          domain         (url/get-domain-normalized normalized-url)
          _tab-id        (-> sender i/js->clj :tab :id)]
      (-> (visits.db/domain-visited? domain)
        (.then (fn [result]
                 (visits.db/mark-domain-visited domain)
                 (not result)))))
    (js/Promise.resolve false)))
(defn should-show-domain-links-notice? []
  (let [result (:seen-domain-links-notice @@settings)]
    (js/Promise.resolve (not result))))
(defn saw-domain-links-notice []
  (swap! @settings assoc :seen-domain-links-notice true))

(defn should-show-weekly? [] (js/Promise.resolve (backend/can-complete-weekly?)))
(defn update-user-info [] (backend/request-user-info @backend/user-info))

(defn should-show-subdomains-notice? []
  (js/Promise.resolve (not (:tried-clicking-subdomains @@settings))))
(defn clicked-subdomain []
  (swap! @settings assoc :tried-clicking-subdomains true))

(defn amplify-page [sender]
  (let [tab-info (-> sender i/js->clj :tab)]
    (-> (backend/amplify-page (select-keys tab-info [:url :fav-icon-url :title]))
      (.then (fn [x] (js/Promise. #(link-cache-sync/update-friends-visits)) x))
      (.then clj->js)
      (.catch clj->js))))

(defn update-amplified-page [request sender]
  (let [tab-info (-> sender i/js->clj :tab)]
    (-> (backend/update-amplified-page
          (merge (select-keys tab-info [:url :fav-icon-url :title])
            (select-keys request [:submission-tag :comment :reaction])))
      (.then (fn [x] (js/Promise. #(link-cache-sync/update-friends-visits)) x))
      (.then clj->js)
      (.catch clj->js))))

(defn delete-amplified-page [{:keys [submission-tag]} sender]
  (let [_tab-info (-> sender i/js->clj :tab)]
    (-> (backend/delete-amplified-page submission-tag)
      (.then
        (fn [{:keys [normalized-url link-id] :as x}]
          (js/Promise. #(links/delete-seen-at normalized-url link-id))
          x))
      (.then clj->js)
      (.catch clj->js))))

(defn amplify-dialog-enabled? [_ ^js sender]
  ;; Using .tab.url instead of .url because the latter appears to
  ;; stay the same for SPAs even when the actual page url changes.
  (let [url (.. sender -tab -url)]
    (js/Promise.resolve
      (boolean (and (:amplify-dialog-enabled @@settings)
                 (not (some #(clojure.string/includes? url %)
                        (:blacklisted-urls @@settings))))))))

(defn url-blacklisted? [{:keys [url]}]
  (js/Promise.resolve
    (boolean
      (some #(clojure.string/includes? url %)
        (:blacklisted-urls @@settings)))))

(defn get-time-spent-on-url [_request ^js sender]
  (let [url (.. sender -tab -url)]
    (visits.db/get-time-spent-on-url url)))

(defn saw-amplify-before? [{url :url} _sender]
  (visits.db/saw-amplify-before? url))
(defn saw-amplify-dialog [{url :url} _sender]
  (visits.db/saw-amplify-dialog url))

(defn search-friends-visits [{query :query} _sender]
  (-> (backend/search-friends-visits query)
    (.then #(clj->js % :keyword-fn i/name-with-ns))
    (.catch clj->js)))

(defn search-result-clicked [_request _sender]
  (backend/search-result-clicked))

(defn search-visit-clicked [_request _sender]
  (backend/search-visit-clicked))

(defn get-my-last-visits [sender]
  (let [sender      (i/js->clj sender)
        tab-id      (-> sender :tab :id)
        _visit-hash (:visit-hash (@@tabs/open-tabs tab-id))]
    (-> (backend/get-my-last-visits)
      (.then #(clj->js % :keyword-fn i/name-with-ns))
      (.catch clj->js))))

(defn open-page-context [{url :url} _]
  (.. browser -tabs
    (create #js {:url (str "https://ampie.app/url-context?src=extension&url="
                        (js/encodeURIComponent url))})))

(defn get-command-shortcuts []
  (.. browser -commands (getAll)))

(defn log-analytics-event [{:keys [event details]}]
  (analytics/log-event! event details))

(defn mark-page-visited [{:keys [url has-domain-context has-page-context]}]
  (let [normalized (url/normalize url)
        domain     (url/get-domain-normalized normalized)]
    (.then
      (js/Promise.all
        [(when has-domain-context (visits.db/mark-domain-visited domain))
         (when has-page-context (visits.db/mark-nurl-visited normalized))])
      (constantly true))))

(defn send-feedback [{:keys [contents]}]
  (-> (backend/send-feedback contents)
    (.then clj->js)
    (.catch (fn [x] (js/console.log x) (clj->js x)))))

(defn show-sidebar-on-url? [{:keys [url has-domain-context has-page-context]}]
  (let [normalized (url/normalize url)
        domain     (url/get-domain-normalized normalized)]
    (-> (js/Promise.all
          [(visits.db/domain-visited-many-times? domain)
           (visits.db/nurl-visited-many-times? normalized)
           (contains? top-10k-domains (url/get-domain url))])
      (then-fn [[domain-frequent nurl-frequent popular-domain]]
        (or (and has-page-context (not nurl-frequent))
          (and has-domain-context (and (not domain-frequent)
                                    (not popular-domain))))))))

(defn- notify-all-tabs-of-login! []
  (-> (.. browser -tabs (query #js {}))
    (.then (fn [^js tabs]
             (doseq [tab tabs]
               (.. browser -tabs (sendMessage (.-id tab)
                                   #js {:type "logged-in"})))))))

(defn- update-auth-token! [{:keys [auth-token]}]
  (let [changed? (backend/set-auth-token! auth-token)]
    (when (and changed? (not (clojure.string/blank? auth-token)))
      (notify-all-tabs-of-login!))
    (js/Promise.resolve changed?)))

(defn message-received [request sender]
  (let [request      (js->clj request :keywordize-keys true)
        request-type (:type request)]
    (when goog.DEBUG (log/info request-type request))
    (case (keyword request-type)
      :get-past-visits-parents   (get-past-visits-parents request sender)
      :add-seen-urls             (add-seen-urls request sender)
      :inc-badge-sightings       (inc-badge-sightings request sender)
      :get-urls-overview         (get-urls-overview request)
      :get-url-context           (get-url-context request)
      :get-partial-url-context   (get-partial-url-context request)
      :get-url-info              (get-url-info-based-on-local-cache request sender true)
      :get-local-url-info        (get-url-info-based-on-local-cache request sender false)
      :get-links-pages-info      (get-links-pages-info request sender)
      :get-tweets                (get-tweets request sender)
      :get-parent-thread         (get-parent-thread request sender)
      :get-prefixes-info         (get-prefixes-info request sender)
      :get-time-spent-on-url     (get-time-spent-on-url request sender)
      :saw-amplify-before?       (saw-amplify-before? request sender)
      :saw-amplify-dialog        (saw-amplify-dialog request sender)
      :saw-domain-links-notice   (saw-domain-links-notice)
      :show-domain-links-notice? (should-show-domain-links-notice?)
      :should-show-domain-links? (should-show-domain-links? request sender)
      :subdomains-notice?        (should-show-subdomains-notice?)
      :clicked-subdomain         (clicked-subdomain)
      :open-weekly-links         (open-weekly-links)
      :should-show-weekly?       (should-show-weekly?)
      :update-user-info          (update-user-info)
      :amplify-page              (amplify-page sender)
      :update-amplified-page     (update-amplified-page request sender)
      :delete-amplified-page     (delete-amplified-page request sender)
      :amplify-dialog-enabled?   (amplify-dialog-enabled? request sender)
      :url-blacklisted?          (url-blacklisted? request)
      :search-friends-visits     (search-friends-visits request sender)
      :search-result-clicked     (search-result-clicked request sender)
      :search-visit-clicked      (search-visit-clicked request sender)
      :get-my-last-visits        (get-my-last-visits sender)
      :open-page-context         (open-page-context request sender)
      :get-command-shortcuts     (get-command-shortcuts)
      :log-analytics-event       (log-analytics-event request)
      :show-sidebar-on-url?      (show-sidebar-on-url? request)
      :mark-page-visited!        (mark-page-visited request)
      :send-feedback             (send-feedback request)
      :update-auth-token         (update-auth-token! request)

      (log/error "Unknown request type" request-type))))

(defn stop []
  (.. browser -runtime -onMessage (removeListener message-received)))

(defn start []
  (.. browser -runtime -onMessage (addListener message-received)))

(defstate messages-handler :start (start) :stop (stop))

(defn send-message-to-active-tab [message]
  (-> (.. browser -tabs (query #js {:active true :currentWindow true}))
    (.then #(js->clj % :keywordize-keys true))
    (.then (fn [[{tab-id :id}]]
             (when tab-id
               (.. browser -tabs
                 (sendMessage tab-id
                   (clj->js message))))))))

(defn amplify-current-tab [] (send-message-to-active-tab {:type :amplify-page}))
(defn upvote-current-tab [] (send-message-to-active-tab {:type :upvote-page}))
(defn downvote-current-tab [] (send-message-to-active-tab {:type :downvote-page}))
