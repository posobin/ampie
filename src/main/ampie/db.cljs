(ns ampie.db
  (:require ["dexie" :default Dexie]
            [mount.core])
  (:require-macros [mount.core :refer [defstate]]))

(defn init-db [db]
  (-> (. db (version 1))
    (. stores
      #js {:visits     "&visitHash, normalizedUrl, firstOpened, url"
           :closedTabs "++objId"
           :seenUrls   "&normalizedUrl"})))

(defstate db
  :start (doto (Dexie. "AmpieDB") init-db)
  :stop (doto @db (.close)))
