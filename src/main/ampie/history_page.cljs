(ns ampie.history-page
  (:require [reagent.core :as r])
  (:require [reagent.dom :as rdom])
  (:require [ampie.history :as history]))

(defn history-page [props]
  [:div "Success"])

(defn init []
  (rdom/render [history-page] (. js/document getElementById "history-holder")))
