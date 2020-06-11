(ns ampie.content-script.content
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [ampie.interop :as i]
            [ampie.content-script.badge :as badge]
            [taoensso.timbre :as log]
            [shadow.cljs.devtools.client.browser :as shadow.browser]
            [ampie.content-script.info-bar
             :refer [display-info-bar remove-info-bar]]
            ["webextension-polyfill" :as browser]
            [mount.core :as mount]))

(defn refresh-source []
  (let [this-file
        {:resource-id   [:shadow.build.classpath/resource "ampie/content_script/content.cljs"]
         :module        :content-script :ns 'ampie.content-script.content
         :output-name   "ampie.content_script.content.js"
         :resource-name "ampie/content_script/content.cljs"
         :type          :cljs
         :provides      #{'ampie.content-script.content}
         :warnings      []
         :from-jar      nil
         :deps          ['goog 'cljs.core 'reagent.core 'reagent.dom
                         'shadow.cljs.devtools.client.browser
                         'ampie.content-script.info-bar
                         'shadow.cljs.devtools.client.hud]}

        info-bar
        {:resource-id   [:shadow.build.classpath/resource "ampie/content_script/info_bar.cljs"]
         :module        :content-script :ns 'ampie.content-script.info-bar
         :output-name   "ampie.content_script.info_bar.js"
         :resource-name "ampie/content_script/info_bar.cljs"
         :type          :cljs
         :provides      #{'ampie.content-script.info-bar}
         :warnings      []
         :from-jar      nil
         :deps          ['goog 'cljs.core 'reagent.core 'reagent.dom
                         'shadow.cljs.devtools.client.browser
                         'shadow.cljs.devtools.client.hud]}]
    #_(shadow.browser/handle-build-complete
        {:info        {:sources  [info-bar this-file]
                       :compiled #{(:resource-id this-file)
                                   (:resource-id info-bar)}}
         :reload-info {:never-load  #{}
                       :always-load #{}
                       :after-load  [{:fn-sym 'ampie.content-script.content/refreshed
                                      :fn-str "ampie.content_script.content.refreshed"}]}})))

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
