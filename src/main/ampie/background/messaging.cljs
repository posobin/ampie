(ns ampie.background.messaging
  (:require [ampie.visits.db :as visits.db]
            [ampie.url :as url]
            [ampie.seen-urls :as seen-urls]
            [ampie.tabs.core :as tabs]
            [ampie.interop :as i]
            [ampie.links :as links]
            [ampie.background.backend :as backend]
            [ampie.settings :refer [settings]]
            [ampie.background.link-cache-sync :as link-cache-sync]
            [clojure.set]
            [taoensso.timbre :as log]
            ["webextension-polyfill" :as browser]
            [mount.core])
  (:require-macros [mount.core :refer [defstate]]))

(defn get-past-visits-parents [{url :url} sender]
  (-> (visits.db/get-past-visits-to-the-url url 5)
    (.then
      (fn [past-visits]
        (->> (map :parent)
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
  [{:keys [urls page-url] :as request} sender]
  (let [sender              (i/js->clj sender)
        tab-id              (-> sender :tab :id)
        visit-hash          (:visit-hash (@@tabs/open-tabs tab-id))
        normalized-page-url (url/normalize page-url)
        domain              (url/get-top-domain-normalized normalized-page-url)
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
        (if (or (not show-badges) (aget show-badges "show-badges"))
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
                    clj->js)))))
          (clj->js (map (constantly {}) normalized-urls)))))))

(defn inc-badge-sightings [{:keys [url]} sender]
  (links/inc-nurl-badge-sightings-counts (url/normalize url))
  nil)

(defn get-url-info
  "Returns a Promise that resolves to a js map :source -> [info+]."
  [{url :url} sender include-links-info]
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

(defn get-links-pages-info [{link-ids :link-ids} sender]
  (-> (backend/get-links-pages-info link-ids)
    (.then clj->js)
    (.catch clj->js)))

(defn get-tweets [{ids :ids} sender]
  (.then (backend/get-tweets ids) clj->js))

(defn get-prefixes-info [{url :url} sender]
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
          tab-id         (-> sender i/js->clj :tab :id)]
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
  (let [tab-info (-> sender i/js->clj :tab)]
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

(defn get-time-spent-on-url [request ^js sender]
  (let [url (.. sender -tab -url)]
    (visits.db/get-time-spent-on-url url)))

(defn saw-amplify-before? [{url :url} sender]
  (visits.db/saw-amplify-before? url))
(defn saw-amplify-dialog [{url :url} sender]
  (visits.db/saw-amplify-dialog url))

(defn search-friends-visits [{query :query} sender]
  (-> (backend/search-friends-visits query)
    (.then #(clj->js % :keyword-fn i/name-with-ns))
    (.catch clj->js)))

(defn message-received [request sender]
  (let [request      (js->clj request :keywordize-keys true)
        request-type (:type request)]
    (case (keyword request-type)
      :get-past-visits-parents   (get-past-visits-parents request sender)
      :add-seen-urls             (add-seen-urls request sender)
      :inc-badge-sightings       (inc-badge-sightings request sender)
      :get-url-info              (get-url-info request sender true)
      :get-local-url-info        (get-url-info request sender false)
      :get-links-pages-info      (get-links-pages-info request sender)
      :get-tweets                (get-tweets request sender)
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
      :search-friends-visits     (search-friends-visits request sender)

      (log/error "Unknown request type" request-type))))

(defn stop []
  (.. browser -runtime -onMessage (removeListener message-received)))

(defn start []
  (.. browser -runtime -onMessage (addListener message-received)))

(defstate messages-handler :start (start) :stop (stop))

(defn amplify-current-tab []
  (-> (.. browser -tabs (query #js {:active true :currentWindow true}))
    (.then #(js->clj % :keywordize-keys true))
    (.then (fn [[{tab-id :id}]]
             (when tab-id
               (.. browser -tabs
                 (sendMessage tab-id
                   (clj->js {:type :amplify-page}))))))))
