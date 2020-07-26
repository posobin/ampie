(ns ampie.links
  (:require [ampie.db :refer [db]]
            [taoensso.timbre :as log]
            [ampie.background.backend :as backend]
            [ampie.interop :as i]
            [mount.core :refer [defstate]]))

(defstate large-prefixes-cache :start (atom {}))

(defn get-links-with-nurl
  "Returns a promise that resolves with a clojure vector
  `[[server-link-id source]+]` - all the entries for the corresponding
  normalized url from the links table."
  [normalized-url]
  (-> (.-links @db) (.get normalized-url)
    (.then #(-> % i/js->clj :seen-at))))

(defn get-links-with-nurls
  "Returns a promise that resolves with a seq
  `([[server-link-id source]+]+)` - one vector for each given normalized url."
  [normalized-urls]
  (-> (.-links @db) (.bulkGet (clj->js normalized-urls))
    (.then #(->> % i/js->clj (map :seen-at)))))

(defn link-ids-to-info
  "Accepts a seq of {:id link-id :origin origin} maps,
  and returns a Promise that resolves to a seq of corresponding
  links info: tweet urls and hn stories/comments urls."
  [links]
  (if (seq links)
    (let [local-link->server-format
          (fn [[link-id source]]
            {:id link-id :origin (case source
                                   "hn"  :hn_story
                                   "hnc" :hn_comment
                                   "tf"  :tf
                                   "tl"  :tl
                                   nil)})
          links (map local-link->server-format links)]
      (backend/link-ids-to-info links))
    (js/Promise.resolve {:hn nil :twitter nil})))

(defn get-links-starting-with
  ([normalized-url-prefix]
   (get-links-starting-with normalized-url-prefix 0))
  ([normalized-url-prefix page]
   (get-links-starting-with normalized-url-prefix 50 (* page 50)))
  ([normalized-url-prefix limit offset]
   (if-let [result (@@large-prefixes-cache normalized-url-prefix)]
     (js/Promise.resolve (->> result (drop offset) (take limit)))
     (-> (.-links @db) (.where "normalizedUrl")
       (.startsWith normalized-url-prefix)
       (.reverse)
       (.sortBy "count")
       (.then
         (fn [result]
           (let [result (i/js->clj result)]
             (when (> (count result) 100)
               (swap! @large-prefixes-cache assoc normalized-url-prefix result))
             (->> result (drop offset) (take limit)))))))))
