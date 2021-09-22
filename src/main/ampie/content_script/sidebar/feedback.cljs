(ns ampie.content-script.sidebar.feedback
  (:require ["webextension-polyfill" :as browser]))

(defn send-feedback! [contents]
  (.then (.. browser -runtime
           (sendMessage (clj->js {:type     :send-feedback
                                  :contents contents})))
    #(js->clj % :keywordize-keys true)))
