(ns ampie.content-script.content
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [ampie.interop :as i]
            [ampie.content-script.badge :as badge]
            [taoensso.timbre :as log]
            [ampie.content-script.info-bar
             :refer [display-info-bar remove-info-bar]]
            ["webextension-polyfill" :as browser]
            [mount.core :as mount]))

(defn ^:dev/after-load reloaded []
  (display-info-bar)
  (mount/start))

(defn refreshed []
  (reloaded))

(defn init []
  (refreshed)
  #_(js/setTimeout refresh-source 500))

(defn ^:dev/before-load before-load []
  (remove-info-bar)
  (mount/stop))
