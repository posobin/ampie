(ns ampie.background.messaging
  (:require [ampie.visits.db :as visits.db]
            [ampie.url :as url]
            [ampie.seen-urls :as seen-urls]
            [ampie.tabs.core :as tabs]
            [ampie.interop :as i]
            [ampie.links :as links]
            [ampie.background.backend :as backend]
            [clojure.set]
            [taoensso.timbre :as log]
            ["webextension-polyfill" :as browser]
            [mount.core])
  (:require-macros [mount.core :refer [defstate]]))

(defn get-past-visits-parents [{url :url} sender]
  (-> (visits.db/get-past-visits-to-the-url url 5)
    (.then
      (fn [past-visits]
        (->> (i/js->clj past-visits)
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
  (letfn [(transform-seen-at [seen-at]
            (let [grouped (group-by second seen-at)
                  hn      (concat (grouped "hnc") (grouped "hn"))
                  twitter (concat (grouped "tf") (grouped "tl"))]
              (merge (when (seq hn) {:hn hn})
                (when (seq twitter) {:twitter twitter}))))]
    (.then
      (js/Promise.all
        [(-> (links/get-links-with-nurls normalized-urls)
           (.then #(map transform-seen-at %)))
         (seen-urls/find-where-saw-nurls normalized-urls)])
      (fn [[seen-at-seq history]]
        (map #(assoc %1 :history %2) seen-at-seq history)))))

(defn add-seen-urls
  "Takes `request` and `sender`, filters out the pages with the same top-level
  domain as in `page-url`, saves them to to `seen-urls` and returns a Promise
  that resolves with the js object describing for each of the urls
  in the request where it was seen before, of the form
  {:url url :twitter twitter-links :normalized-url nurl :hn hn}."
  [{:keys [urls page-url] :as request} sender]
  (let [sender          (i/js->clj sender)
        tab-id          (-> sender :tab :id)
        visit-hash      (:visit-hash (@@tabs/open-tabs tab-id))
        domain          (url/get-top-domain page-url)
        normalized-urls (map url/normalize urls)]
    (log/trace "add-seen-urls for" page-url)
    (when page-url
      (let [filtered-urls (filter #(not= domain (url/get-top-domain %))
                            normalized-urls)]
        (-> (visits.db/get-visit-by-hash visit-hash)
          (.then
            (fn [{url :url timestamp :first-opened :as rr}]
              (if (= url page-url)
                (seen-urls/add-seen-nurls filtered-urls visit-hash timestamp)
                (log/error "Couldn't add seen-urls because page-url"
                  "didn't match: expected" url "got" page-url)))))))
    ;; The result that the promise returns will be sent to the sender.
    (.then
      (get-nurls-info normalized-urls)
      (fn [infos]
        (->> (map #(assoc %1 :url %2 :normalized-url %3)
               infos urls normalized-urls)
          (map (fn [info]
                 (update info :history
                   (fn [history]
                     (filter #(not= visit-hash (:visit-hash %))
                       history)))))
          clj->js)))))

(defn get-url-info
  "Returns a Promise that resolves to a js map :source -> [info+]."
  [{url :url} sender include-links-info]
  (let [normalized-url (url/normalize url)]
    (.then
      (js/Promise.all
        [(seen-urls/find-where-saw-nurls [normalized-url])
         (if include-links-info
           (.then (links/get-links-with-nurl normalized-url)
             links/link-ids-to-info)
           (-> (links/get-links-with-nurl normalized-url)
             (.then
               (fn [seen-at]
                 (let [grouped (group-by second seen-at)
                       hn      (concat (grouped "hnc") (grouped "hn"))
                       twitter (concat (grouped "tf") (grouped "tl"))]
                   (merge (when (seq hn) {:hn hn})
                     (when (seq twitter) {:twitter twitter})))))))])
      (fn [[seen links]]
        (log/info links)
        (let [seen (first seen)]
          (clj->js (assoc links :history seen :normalized-url normalized-url)))))))

(defn get-tweets [{ids :ids} sender]
  (.then (backend/get-tweets ids) clj->js))

(defn get-prefixes-info [{url :url} sender]
  (let [normalized-url (url/normalize url)
        prefixes       (take 10 (url/get-prefixes-normalized normalized-url))]
    (.then (js/Promise.all
             (for [prefix prefixes]
               (links/get-links-starting-with prefix)))
      (fn [prefixes-info]
        (clj->js (map #(vector %1 %2) prefixes prefixes-info))))))

(defn message-received [request sender]
  (let [request      (js->clj request :keywordize-keys true)
        request-type (:type request)]
    (log/info request)
    (case (keyword request-type)
      :get-past-visits-parents (get-past-visits-parents request sender)
      :add-seen-urls           (add-seen-urls request sender)
      :get-url-info            (get-url-info request sender true)
      :get-local-url-info      (get-url-info request sender false)
      :get-tweets              (get-tweets request sender)
      :get-prefixes-info       (get-prefixes-info request sender)

      (log/error "Unknown request type" request-type))))

(defn stop []
  (.. browser -runtime -onMessage (removeListener message-received)))

(defn start []
  (.. browser -runtime -onMessage (addListener message-received)))

(defstate messages-handler :start (start) :stop (stop))
