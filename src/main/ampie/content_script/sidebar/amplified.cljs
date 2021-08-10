(ns ampie.content-script.sidebar.amplified
  (:require [ampie.content-script.sidebar.db :refer [db]]
            [reagent.core :as r]
            [ampie.content-script.sidebar.hn :as hn]
            [ampie.content-script.sidebar.twitter :as twitter]
            ["webextension-polyfill" :as browser]
            [ampie.macros :refer [then-fn]]))

(def default-show-batch-size 10)

(defn save-visits! [visits]
  (let [tag->visit (reduce (fn [tag->visit visit]
                             (assoc tag->visit (:visit/tag visit)
                               (assoc visit :ampie/status :loaded)))
                     {} visits)]
    (swap! db update :visit-tag->visit merge tag->visit)))

(defn load-next-batch-of-amplified-links!
  "Takes the visits context for the given url from the db,
  makes sure all visits are in the db indexed by their tag, and shows them."
  [url]
  (let [state               (r/cursor db [:url->ui-state url :visit])
        whole-url-context   (get-in @db [:url->context url :visit])
        visits-left-to-show (remove (comp (set (:showing @state)) :visit/tag)
                              whole-url-context)]
    (swap! state #(merge {:showing []} % {:ampie/status :loading}))
    (save-visits! whole-url-context)
    (let [batch (take default-show-batch-size visits-left-to-show)]
      (swap! state (fn [state]
                     (-> (assoc state :ampie/status :loaded)
                       (update :showing into (map :visit/tag batch))))))
    (js/Promise.resolve)))
