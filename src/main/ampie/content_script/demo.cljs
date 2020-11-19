(ns ampie.content-script.demo
  (:require [ampie.url :as url]))

(defn is-ampie-domain? [url]
  (if goog.DEBUG
    (= "localhost:8280" (url/get-domain url))
    (= "ampie.app" (url/get-domain url))))

(defn is-demo-url? [url]
  (if goog.DEBUG
    (= "localhost:8280/hello" (url/normalize url))
    (= "app.ampie/hello" (url/normalize url))))

(defn get-current-url []
  (let [current-url (.. js/window -location -href)]
    (if (is-demo-url? current-url)
      "https://www.eugenewei.com/blog/2018/5/21/invisible-asymptotes"
      current-url)))

(defn send-message-to-page [message-map]
  (when (is-ampie-domain? (.. js/document -location -href))
    (.postMessage js/window
      (clj->js message-map)
      (if goog.DEBUG "http://localhost:8280" "https://ampie.app"))))
