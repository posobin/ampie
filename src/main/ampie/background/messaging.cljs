(ns ampie.background.messaging
  (:require [ampie.visits.db :as visits.db]
            [ampie.url :as url]
            [ampie.seen-urls :as seen-urls]
            [ampie.tabs.core :as tabs]
            [ampie.interop :as i]
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

(defn add-seen-urls
  "Takes `request` and `sender`, saves the urls in the request
  to `seen-urls` and returns a Promise that resolves with the
  js object describing for each of the urls in the request where
  it was seen before, as in `seen-urls/find-where-saw-nurls`."
  [{:keys [urls page-url] :as request} sender]
  (let [normalized-urls (map url/clean-up urls)
        sender          (i/js->clj sender)
        tab-id          (-> sender :tab :id)
        visit-hash      (:visit-hash (@@tabs/open-tabs tab-id))]
    (log/trace "add-seen-urls for" page-url)
    (when page-url
      (-> (visits.db/get-visit-by-hash visit-hash)
        (.then
          (fn [{url :url timestamp :first-opened :as rr}]
            (if (= url page-url)
              (seen-urls/add-seen-nurls normalized-urls visit-hash timestamp)
              (log/error "Couldn't add seen-urls because page-url"
                "didn't match: expected" url "got" page-url))))))
    ;; The result that the promise returns will be sent to the sender.
    (.then
      (seen-urls/find-where-saw-nurls normalized-urls)
      clj->js)))

(defn message-received [request sender]
  (let [request      (js->clj request :keywordize-keys true)
        request-type (:type request)]
    (log/info request)
    (case (keyword request-type)
      :get-past-visits-parents
      (get-past-visits-parents request sender)

      :add-seen-urls
      (add-seen-urls request sender)

      (log/error "Unknown request type" request-type))))

(defn stop []
  (.. browser -runtime -onMessage (removeListener message-received)))

(defn start []
  (.. browser -runtime -onMessage (addListener message-received)))

(defstate messages-handler :start (start) :stop (stop))
