(ns ampie.content-script.visits-search
  (:require ["webextension-polyfill" :as browser]
            [reagent.dom :as rdom]
            [reagent.core :as r]
            [ampie.url :as url]
            [clojure.string :as string]
            [ampie.components.basics :as b]
            [ampie.content-script.sidebar :as sidebar]
            [ampie.time]
            [ampie.macros :refer [then-fn]]
            [mount.core :as mount :refer [defstate]]))

(def state (r/atom {}))

(defn load-search-results [query]
  (swap! state assoc :loading true)
  (-> (.. browser -runtime
        (sendMessage
          (clj->js {:type  :search-friends-visits
                    :query query})))
    (.then #(js->clj % :keywordize-keys true))
    (.then (fn [response]
             (swap! state assoc :loading false)
             (if (:fail response)
               (swap! state assoc
                 :failure true
                 :error (:message response))
               (swap! state assoc
                 :search-results (:search-results response)
                 :hidden false))))))

(defn- unescape [text]
  (-> text
    (string/replace "\\<" "<")
    (string/replace "\\>" ">")))

(defn highlight-headline [text]
  ;; [\s\S] to match all characters, including new lines
  (if-let [matches
           (re-seq #"([\s\S]*?)<b>([\s\S]*?)</b>([\s\S]*?)(?=(<|$))"
             (or text ""))]
    (into [:<>]
      (for [[_ pre highlight post] matches]
        [:<> (unescape pre)
         [:b.highlight (unescape highlight)]
         (unescape post)]))
    text))

(defn search-result [{:keys       [comment-headline content-headline]
                      :link/keys  [url]
                      :users/keys [username]
                      :visit/keys [reaction title created-at]}]
  [:div.search-result
   [:div.domain-name (url/get-domain url)]
   [:a.title (assoc (b/ahref-opts url)
               :on-click
               (fn []
                 (.. browser -runtime
                   (sendMessage
                     (clj->js {:type :search-result-clicked})))))
    title]
   (when (seq content-headline)
     [:div.headline (highlight-headline content-headline)])
   [:div.visits
    [:div.visit {:class (when (= reaction "like") "like")}
     (when (seq comment-headline)
       [:div.comment (highlight-headline comment-headline)]
       #_[:div.no.comment])
     [:div.info
      [:div.username
       [:a (assoc (b/ahref-opts (str "https://ampie.app/" username))
             :on-click
             (fn []
               (.. browser -runtime
                 (sendMessage
                   (clj->js {:type :search-visit-clicked})))))
        username]]
      [:div.created-at (ampie.time/timestamp->date (* 1000 created-at))]
      (when reaction [:div.reaction reaction])]]]])

(defn visits-search-results []
  (when-not (or (:hidden @state)
              (empty? (:search-results @state)))
    [:div.search-results
     [:h2 "Ampie results"]
     (doall
       (for [result (:search-results @state)]
         ^{:key (:visit/tag result)}[search-result result]))]))

(def sources [:hn_comment :hn_story :twitter :ahref :domain :visit])

(def result-tags-attr-value "result-tags")

(defn- search-result-tags [url-overview set-sidebar-url! engine]
  (let [{:keys [occurrences]} url-overview]
    (when (some (comp pos? :count val) occurrences)
      [:div.flex.flex-row.gap-1.mt-1
       {:class (when (= engine :google) :-mb-4)}
       (for [origin sources
             :let   [info (get occurrences origin)]
             :when  (pos? (:count info))]
         ^{:key origin}
         [:div.flex.flex-row.gap-1.border.rounded.p-1.hover:bg-yellow-100.hover:border-gray-400
          {:class    [:pt-0.5 :pb-0.5]
           :role     :button
           :on-click (fn [e] (set-sidebar-url! (:url url-overview)
                               {:expand-sidebar true
                                :focus-origin   origin
                                :reason         :ampie-tag-click})
                       (.stopPropagation e))}
          [:div.self-center.w-4.h-4.rounded {:class [(str (name origin) "-icon")]}]
          [:span (:count info)]])
       [:div.rounded.p-1.hover:bg-yellow-100.border.border-transparent.hover:border-gray-400
        {:role     :button
         :on-click (fn [e] (.stopPropagation e)
                     (.. browser -runtime
                       (sendMessage (clj->js {:type :open-page-context
                                              :url  (:url url-overview)})))
                     nil)}
        [:div.self-center.w-4.h-4.rounded.open-in-new-tab-icon]]])))

(defn- get-google-result-link-root [^js node]
  (let [root (reduce #(.-parentElement %1) node (range 4))]
    (when (= (first (.-classList root)) "g")
      root)))

(defn- add-search-result-tags
  [{:keys [overview set-sidebar-url! root engine]}]
  (let [badge-div    (. js/document createElement "div")
        shadow-style (. js/document createElement "link")
        tailwind     (. js/document createElement "link")
        shadow       (. badge-div (attachShadow #js {"mode" "open"}))]
    (set! (.-rel shadow-style) "stylesheet")
    (set! (.-rel tailwind) "stylesheet")
    (.setAttribute badge-div "style" "display: none;")
    (.setAttribute badge-div "data-ampie" result-tags-attr-value)
    (set! (.-onload shadow-style) #(.setAttribute badge-div "style" ""))
    (set! (.-href shadow-style) (.. browser -runtime (getURL "assets/search-result-info.css")))
    (set! (.-onload tailwind) #(.setAttribute badge-div "style" ""))
    (set! (.-href tailwind) (.. browser -runtime (getURL "assets/tailwind.css")))
    (rdom/render [search-result-tags overview set-sidebar-url! engine] shadow)
    (.appendChild shadow shadow-style)
    (.appendChild shadow tailwind)
    (.appendChild root badge-div)))

(defn- add-google-tags []
  (let [ahrefs (->> (array-seq (.querySelectorAll js/document.body ".yuRUbf > a[href]"))
                 (filter #(nil? (.getAttribute % "processed-by-ampie"))))
        urls   (mapv #(.-href %) ahrefs)]
    (-> (.. browser -runtime
          (sendMessage (clj->js {:type                :get-urls-overview
                                 :urls                urls
                                 :fast-but-incomplete true})))
      (.then #(js->clj % :keywordize-keys true))
      (then-fn [response]
        (doseq [[{:keys [occurrences url normalized-url] :as overview} ahref]
                (map vector response ahrefs)
                :when ahref
                :let  [root (get-google-result-link-root ahref)]
                :when root]
          (add-search-result-tags
            {:overview         overview
             :set-sidebar-url! sidebar/set-sidebar-url!
             :root             root
             :engine           :google}))))))

(defn- get-ddg-result-link-root [^js node]
  (let [root (reduce #(.-parentElement %1) node (range 3))]
    (when (string/includes? (.-className root) "result__body")
      root)))

(defn- add-ddg-tags []
  (let [ahrefs (->> (array-seq (.querySelectorAll js/document.body ".result a[href].result__url"))
                 (filter #(nil? (.getAttribute % "processed-by-ampie"))))
        urls   (mapv #(.-href %) ahrefs)]
    (js/console.log ahrefs)
    (js/console.log urls)
    (-> (.. browser -runtime
          (sendMessage (clj->js {:type                :get-urls-overview
                                 :urls                urls
                                 :fast-but-incomplete true})))
      (.then #(js->clj % :keywordize-keys true))
      (then-fn [response]
        (js/console.log response)
        (doseq [[{:keys [occurrences url normalized-url] :as overview} ahref]
                (map vector response ahrefs)
                :when ahref]
          (add-search-result-tags
            {:overview         overview
             :set-sidebar-url! sidebar/set-sidebar-url!
             :root             (get-ddg-result-link-root ahref)
             :engine           :ddg}))))))

(defn- delete-all-tags! []
  (doseq [element
          (array-seq
            (.. js/document -body
              (querySelectorAll (str "[data-ampie='"
                                  result-tags-attr-value "']"))))]
    (.remove element)))

(defstate google-results
  :start (when (and (re-matches #"https://(www\.)?google\..{1,6}/search.*"
                      (.. js/document -location -href))
                 ;; Prevents showing ampie results when searching in local maps
                 (not (string/includes? (.. js/document -location -href)
                        "tbm=lcl")))
           (add-google-tags)

           #_(let [rhs-el        (. js/document getElementById "rhs")
                   shadow-holder (. js/document createElement "div")
                   shadow        (. shadow-holder (attachShadow #js {"mode" "open"}))
                   shadow-style  (. js/document createElement "link")
                   query         (.. (js/URL. (.. js/document -location -href))
                                   -searchParams (get "q"))]
               (set! (.-rel shadow-style) "stylesheet")
               (.setAttribute shadow-holder "style"  "display: none;")
               (set! (.-onload shadow-style) #(.setAttribute shadow-holder "style" ""))
               (set! (.-href shadow-style) (.. browser -runtime (getURL "assets/search-results.css")))
               (set! (.-className shadow-holder)
                 "rhs VjDLd ampie-results-holder google")
               (.then
                 (load-search-results query)
                 (fn []
                   (when (seq (:search-results @state))
                     (rdom/render [visits-search-results] shadow)
                     (. shadow (appendChild shadow-style))
                     (.prepend rhs-el shadow-holder))))))
  :stop (delete-all-tags!))

(defstate ddg-results
  :start (do
           (when (re-matches #"https://(www\.)?duckduckgo\.com/\?.*"
                   (.. js/document -location -href))
             ;; Seems like DDG loads progressively so by the time we run this
             ;; the search results aren't rendered yet. Load them after
             ;; a delay instead.
             (js/setTimeout add-ddg-tags 100)
             #_(let [rhs-el        (. js/document querySelector ".sidebar-modules")
                     wrapper       (. js/document createElement "div")
                     shadow-holder (. js/document createElement "div")
                     shadow        (. shadow-holder (attachShadow #js {"mode" "open"}))
                     shadow-style  (. js/document createElement "link")
                     query         (.. (js/URL. (.. js/document -location -href))
                                     -searchParams (get "q"))]
                 (set! (.-rel shadow-style) "stylesheet")
                 (.setAttribute wrapper "style"  "display: none;")
                 (set! (.-onload shadow-style) #(.setAttribute wrapper "style" ""))
                 (set! (.-href shadow-style) (.. browser -runtime (getURL "assets/search-results.css")))
                 (set! (.-className wrapper) "module ampie-results-holder ddg")
                 (.then
                   (load-search-results query)
                   (fn []
                     (when (seq (:search-results @state))
                       (rdom/render [visits-search-results] shadow)
                       (. shadow (appendChild shadow-style))
                       (set! (.-className shadow-holder) "module__content")
                       (.appendChild wrapper shadow-holder)
                       (.prepend rhs-el wrapper)))))))
  :stop (delete-all-tags!))
