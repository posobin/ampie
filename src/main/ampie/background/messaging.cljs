(ns ampie.background.messaging
  (:require [ampie.visits.db :as visits.db]
            [ampie.url :as url]
            [ampie.seen-links :as seen-links]
            [ampie.tabs.core :as tabs]
            [taoensso.timbre :as log]
            ["webextension-polyfill" :as browser]))

(defn get-past-visits-parents [{url :url} sender]
  (-> (visits.db/get-past-visits-to-the-url url 5)
    (.then
      (fn [past-visits]
        (->> (js->clj past-visits :keywordize-keys true)
          (map :parent)
          (filter some?)
          visits.db/get-visits-info)))
    (.then
      (fn [past-visits-parents]
        (->> past-visits-parents
          (map #(select-keys % [:url :firstOpened]))
          clj->js)))))

(defn add-seen-links
  "Takes `request` and `sender`, saves the links in the request
  to `seen-links` and returns a Promise that resolves with the
  js object describing for each of the links in the request where
  it was seen before, as in `seen-links/find-where-saw-urls`."
  [{:keys [links page-url] :as request} sender]
  (let [page-url   (url/clean-up page-url)
        links      (map url/clean-up links)
        sender     (js->clj sender :keywordize-keys true)
        tab-id     (-> sender :tab :id)
        visit-hash (:visit-hash (@@tabs/open-tabs tab-id))]
    (log/trace "add-seen-links for" page-url)
    (when page-url
      (-> (visits.db/get-visit-by-hash visit-hash)
        (.then
          (fn [{url :url timestamp :firstOpened :as rr}]
            (if (= url page-url)
              (seen-links/add-seen-links links visit-hash timestamp)
              (log/error "Couldn't add seen-links because page-url"
                "didn't match: expected" url " got" page-url))))))
    ;; The result that the promise returns will be sent to the sender.
    (.then
      (seen-links/find-where-saw-urls links)
      clj->js)))

(defn message-received [request sender]
  (let [request      (js->clj request :keywordize-keys true)
        request-type (:type request)]
    (log/trace "Got request of type" request-type)
    (case (keyword request-type)
      :get-past-visits-parents
      (get-past-visits-parents request sender)

      :add-seen-links
      (add-seen-links request sender)

      (log/error "Unknown request type" request-type))))

(defn stop []
  (.. browser -runtime -onMessage (removeListener message-received)))

(defn start []
  (.. browser -runtime -onMessage (addListener message-received)))
