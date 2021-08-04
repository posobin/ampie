(ns ampie.content-script.sidebar.domain
  (:require [ampie.content-script.sidebar.db :refer [db]]
            [reagent.core :as r]
            [ampie.content-script.sidebar.hn :as hn]
            [ampie.content-script.sidebar.twitter :as twitter]
            [ampie.content-script.sidebar.amplified :as amplified]
            ["webextension-polyfill" :as browser]
            [ampie.macros :refer [then-fn]]))

(def default-show-batch-size 10)

(defn fetch-urls-info! [urls]
  (let [overviews  (map (juxt identity #(get-in @db [:url->overview %])) urls)
        not-loaded (remove
                     (fn [[_ {overview-status :ampie/status}]]
                       (#{:loading :loaded} overview-status))
                     overviews)]
    (doseq [[url] not-loaded]
      (swap! db assoc-in [:url->overview url :ampie/status] :loading))
    (-> (.. browser -runtime
          (sendMessage (clj->js {:type :get-urls-overview
                                 :urls urls})))
      (.then #(js->clj % :keywordize-keys true))
      (then-fn [response]
        (doseq [{:keys [occurrences url normalized-url] :as overview}
                response]
          (swap! db assoc-in [:url->overview url]
            (-> (dissoc overview :url :normalized-url)
              (assoc :ampie/status :loaded))))
        #_(swap! db assoc-in [:url->context (first urls)]
            (assoc occurrences :ampie/status :loaded))))))

(defn load-next-batch-of-domain-links!
  "Takes the domain context for the given url from the db,
  fetches information about the next batch of links on the same domain,
  and shows them."
  [url]
  (let [state              (r/cursor db [:url->ui-state url :domain])
        whole-url-context  (get-in @db [:url->context url :domain])
        links-left-to-show (remove (comp (conj (set (:showing @state)) url) :link/original)
                             whole-url-context)]
    (swap! state #(merge {:showing []} % {:ampie/status :loading}))
    (let [batch (take default-show-batch-size links-left-to-show)]
      (-> (fetch-urls-info! (map :link/original batch))
        (then-fn []
          (swap! state (fn [state]
                         (-> (assoc state :ampie/status :loaded)
                           (update :showing into (map :link/original batch))))))))))

(defn load-next-batch-of-backlinks!
  "Takes the backlinks context for the given url from the db,
  fetches information about the next batch of backlinks pointing to the URL,
  and shows them."
  [url]
  (let [state              (r/cursor db [:url->ui-state url :ahref])
        whole-url-context  (get-in @db [:url->context url :ahref])
        links-left-to-show (remove (comp (conj (set (:showing @state)) url) :page/original)
                             whole-url-context)]
    (swap! state #(merge {:showing []} % {:ampie/status :loading}))
    (let [batch (take default-show-batch-size links-left-to-show)]
      (-> (fetch-urls-info! (map :page/original batch))
        (then-fn []
          (swap! state (fn [state]
                         (-> (assoc state :ampie/status :loaded)
                           (update :showing into (map :page/original batch))))))))))

(defn load-origin-context! [url origin]
  (let [context (r/cursor db [:url->context url])]
    (when-not (#{:loaded :loading} (get-in @context [:ampie/individual-statuses origin]))
      (swap! context assoc-in [:ampie/individual-statuses origin]
        :loading)
      (-> (.. browser -runtime
            (sendMessage (clj->js {:type   :get-partial-url-context
                                   :origin origin
                                   :url    url})))
        (.then #(js->clj % :keywordize-keys true))
        (then-fn [{:keys [occurrences]}]
          (swap! context
            #(-> (merge % occurrences)
               (assoc-in [:ampie/individual-statuses origin] :loaded))))
        (then-fn []
          (case origin
            :hn_comment (hn/load-next-batch-of-comments! url)
            :hn_story   (hn/load-next-batch-of-stories! url)
            :twitter    (twitter/load-next-batch-of-tweets! url)
            :visit      (amplified/load-next-batch-of-amplified-links! url)
            :ahref      (load-next-batch-of-backlinks! url)
            :domain     (load-next-batch-of-domain-links! url)
            nil))))))
