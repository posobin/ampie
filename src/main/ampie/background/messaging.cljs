(ns ampie.background.messaging
  (:require [ampie.visits.db :as visits.db]
            [ampie.url :as url]
            [ampie.seen-links :as seen-links]
            ["webextension-polyfill" :as browser]))

(defn message-get-past-visits-parents [{url :url} sender]
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

(defn message-send-links-on-page [{:keys [links page-url]} sender]
  (let [page-url (url/clean-up page-url)
        links    (map url/clean-up links)
        sender   (js->clj sender :keywordize-keys true)
        tab-id   (-> sender :tab :id)]
    (when page-url
      (seen-links/add-seen-links tab-id page-url links))
    (.then
      (seen-links/find-where-saw-urls links)
      clj->js)))

(defn message-received [request sender]
  (let [request      (js->clj request :keywordize-keys true)
        request-type (:type request)]
    (cond (= request-type "get-past-visits-parents")
          (message-get-past-visits-parents request sender)

          (= request-type "send-links-on-page")
          (message-send-links-on-page request sender))))

(defn stop []
  (.. browser -runtime -onMessage (removeListener message-received)))

(defn start []
  (.. browser -runtime -onMessage (addListener message-received)))
