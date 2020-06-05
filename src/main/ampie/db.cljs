(ns ampie.db
  (:require ["dexie" :default Dexie]
            [mount.core])
  (:require-macros [mount.core :refer [defstate]]))

(defn init-db [db]
  (-> (. db (version 3))
    (. stores
      #js {:visits     "&visitHash, url, firstOpened"
           :closedTabs "++objId"
           :seenLinks  "&url"})
    (.upgrade
      (fn [transaction]
        (.clear
          (.table transaction "seenLinks")))))
  (-> (. db (version 2))
    (. stores
      #js {:visits     "&visitHash, url, firstOpened"
           :closedTabs "++objId"
           :seenLinks  "&url"})
    (.upgrade
      (fn [transaction]
        (.clear
          (.table transaction "seenLinks")))))
  (-> (. db (version 1))
    (. stores
      #js {:visits     "&visitHash, url, firstOpened"
           :closedTabs "++objId"
           :seenLinks  "&[parentUrl+childUrl],childUrl"})))

(defstate db
  :start (doto (Dexie. "AmpieDB") init-db)
  :stop (doto @db (.close)))
