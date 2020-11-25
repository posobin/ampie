(ns ampie.settings
  (:require [reagent.core :as r]
            ["webextension-polyfill" :as browser]
            [taoensso.timbre :as log]
            [clojure.data]
            [mount.core :refer [defstate]]))

(def default-settings {:show-badges               true
                       :auto-show-domain-links    true
                       :seen-domain-links-notice  false
                       :tried-clicking-subdomains false
                       :amplify-dialog-enabled    true
                       :hn-enabled                true
                       :blacklisted-urls          ["mail.google.com"
                                                   "hey.com"
                                                   "messenger.com"
                                                   "overleaf.com"
                                                   "docs.google.com"]
                       :blacklisted-domains       []})
(def settings-keys (set (keys default-settings)))

(defn load-settings [settings-atom]
  (.then
    (.. browser -storage -local (get (clj->js settings-keys)))
    (fn [settings]
      (reset! settings-atom (merge default-settings
                              (js->clj settings :keywordize-keys true)))
      (.. browser -storage -local (set (clj->js @settings-atom))))))

(defstate settings :start (doto (r/atom {}) (load-settings)))

(def ignore-settings-watcher (atom false))

(defn local-storage-updated
  "Handler for the changes to the local storage"
  [changes area-name]
  (reset! ignore-settings-watcher true)
  (doseq [[name {old-value :oldValue
                 new-value :newValue}]
          (js->clj changes :keywordize-keys true)]
    (when (contains? settings-keys (keyword name))
      (swap! @settings assoc (keyword name) new-value)))
  (reset! ignore-settings-watcher false))

(defn settings-updated
  "Event handler for updates to `settings`, saves them to the
  local storage."
  [key reference old-state new-state]
  (when-not @ignore-settings-watcher
    (doseq [[key value] (second (clojure.data/diff old-state new-state))]
      (assert (contains? settings-keys key))
      (.. browser -storage -local (set (clj->js {key (new-state key)}))))
    (doseq [[key value] (first (clojure.data/diff old-state new-state))
            :when       (= key :blacklisted-urls)]
      (assert (contains? settings-keys key))
      (.. browser -storage -local (set (clj->js {key (new-state key)}))))))

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
