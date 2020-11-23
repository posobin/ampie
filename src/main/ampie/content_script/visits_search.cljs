(ns ampie.content-script.visits-search
  (:require ["webextension-polyfill" :as browser]
            ["react-shadow-dom-retarget-events" :as retargetEvents]
            [reagent.dom :as rdom]
            [reagent.core :as r]
            [ampie.url :as url]
            [ampie.interop :as i]
            [taoensso.timbre :as log]
            [ajax.core :refer [GET]]
            [clojure.string :as string]
            [ampie.components.basics :as b]
            [ampie.time]
            [ampie.content-script.amplify :as amplify]
            [ampie.links :as links]
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
                      :visit/keys [reaction comment title created-at]
                      :as         result}]
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
         ^{:key (:tag result)}[search-result result]))]))

(defstate google-results
  :start (when (and (re-matches #"https://(www\.)?google\..{1,6}/search.*"
                      (.. js/document -location -href))
                 ;; Prevents showing ampie results when searching in local maps
                 (not (string/includes? (.. js/document -location -href)
                        "tbm=lcl")))
           (let [rhs-el        (. js/document getElementById "rhs")
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
  :stop (when-let [element (.. js/document -body
                             (querySelector ".ampie-results-holder"))]
          (.remove element)))

(defstate ddg-results
  :start (when (re-matches #"https://(www\.)?duckduckgo\.com/\?.*"
                 (.. js/document -location -href))
           (let [rhs-el        (. js/document querySelector ".sidebar-modules")
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
                   (.prepend rhs-el wrapper))))))
  :stop (when-let [element (.. js/document -body
                             (querySelector ".ampie-results-holder"))]
          (.remove element)))
