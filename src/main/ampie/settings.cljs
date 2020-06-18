(ns ampie.settings
  (:require [reagent.core :as r]
            ["webextension-polyfill" :as browser]
            [taoensso.timbre :as log]
            [clojure.data]
            [mount.core])
  (:require-macros [mount.core :refer [defstate]]))

(def default-settings {:show-badges         true
                       :blacklisted-urls    []
                       :blacklisted-domains []})
(def settings-keys (set (keys default-settings)))

(defn init-settings [settings-atom]
  (reset! settings-atom default-settings)
  (doseq [[key value] @settings-atom]
    (.. browser -storage -local (set (clj->js {key value})))))

(defn load-settings [settings-atom]
  (.then
    (.get (.. browser -storage -local) (clj->js settings-keys))
    (fn [settings]
      (if (seq (js->clj settings))
        (reset! settings-atom (js->clj settings :keywordize-keys true))
        (init-settings settings-atom)))))

(defstate settings :start (doto (r/atom {}) (load-settings)))

(defn local-storage-updated
  "Handler for the changes to the local storage"
  [changes area-name]
  (doseq [[name {old-value :oldValue
                 new-value :newValue}]
          (js->clj changes :keywordize-keys true)]
    (when (contains? settings-keys (keyword name))
      (swap! @settings assoc (keyword name) new-value))))

(defn settings-updated
  "Event handler for updates to `settings`, saves them to the
  local storage."
  [key reference old-state new-state]
  (doseq [[key value] (second (clojure.data/diff old-state new-state))]
    (assert (contains? settings-keys key))
    (.. browser -storage -local (set (clj->js {key value})))))

(defstate settings-watcher
  :start
  (do
    (add-watch @settings :local-storage settings-updated)
    (.. browser -storage -onChanged
      (addListener local-storage-updated)))
  :stop
  (do
    (remove-watch @settings :local-storage)
    (.. browser -storage -onChanged
      (removeListener local-storage-updated))))
