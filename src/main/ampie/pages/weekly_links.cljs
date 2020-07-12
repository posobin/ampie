(ns ampie.pages.weekly-links
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [taoensso.timbre :as log]
            [clojure.string :as string]
            [ampie.url :as url]
            [ampie.db :refer [db]]
            [ampie.time :as time]
            [ampie.visits.db :as visits.db]
            [ampie.background.backend :as ampie.backend]
            [ampie.components.visit :as components.visit]
            [ampie.components.basics :as b]
            [mount.core :as mount]
            ["webextension-polyfill" :as browser]))

(defonce state (r/atom {:selected-links []}))

(defn add-url [url]
  (let [url (if (string/index-of url "://")
              url
              (str "http://" url))]
    (-> (ampie.backend/load-url-info url)
      (.then
        (fn [{:keys [title] :as result}]
          (log/info "Result" result)
          (swap! state update :selected-links
            (fn [selected-links]
              (let [hash (->> (map :hash selected-links)
                           (apply max 0)
                           inc)]
                (conj selected-links
                  {:url   url
                   :hash  hash
                   :title title}))))))
      (.catch
        (fn [{:keys [message] :as error}]
          (swap! state assoc
            :add-url-error message))))))

(defn url-form []
  (let [url (r/atom "")]
    (fn []
      [:div.url-form
       [:input.url {:type         "text" :placeholder "Add URL" :auto-focus true
                    :value        @url
                    :on-change    #(reset! url (-> % .-target .-value))
                    :on-key-press (fn [^js e]
                                    (when (= (.-key e) "Enter")
                                      (add-url @url) (reset! url "")))}]
       (when-not (empty? @url)
         [:div.add {:on-click (fn [] (add-url @url) (reset! url ""))}
          "add"])])))

(defn textarea [{:keys [value on-change disabled placeholder] :as props}]
  [:textarea
   (merge props
     {:on-change (fn [^js evt]
                   (set! (.. evt -target -style -height) "inherit")
                   (set! (.. evt -target -style -height)
                     (str (.. evt -target -scrollHeight) "px"))
                   (on-change (.. evt -target -value)))
      :ref       (fn [el]
                   (when el
                     (set! (.. el -style -height) "inherit")
                     (set! (.. el -style -height)
                       (str (.. el -scrollHeight) "px"))))
      :rows      1})])

(def textarea-with-focus (with-meta textarea {:component-did-mount #(.focus (rdom/dom-node %))}))

(defn weekly-link [{:keys [editable title url time-spent index hash comment
                           on-change on-drop on-delete]}]
  (let [dropping (r/atom 0)
        dragging (r/atom false)
        editing  (r/atom false)]
    (fn [{:keys [editable title url time-spent index hash comment
                 on-change on-drop on-delete]
          :as   link}]
      [:div.weekly-link.visit-info
       {:class         [(when-not (zero? @dropping) "dropping")
                        (when @dragging "dragging")]
        :draggable     "true"
        :on-drag-end   #(reset! dragging false)
        :on-drag-start (fn [^js evt]
                         (.. evt -dataTransfer (setData "text" url))
                         (.. evt -dataTransfer (setData "text/uri-list" url))
                         (.. evt -dataTransfer
                           (setData "edn"
                             (pr-str (select-keys link
                                       [:title :url :time-spent
                                        :hash :comment]))))
                         (set! (.. evt -dataTransfer -effectAllowed) "copy")
                         (reset! dragging true))
        :on-drop       (fn [evt] (reset! dropping 0) (on-drop evt))
        :on-drag-over  (fn [evt] (.preventDefault evt))
        :on-drag-leave (fn [evt] (swap! dropping #(max (dec %) 0)))
        :on-drag-enter (fn [evt] (swap! dropping inc) (.preventDefault evt))}
       [:div.title {:class (when (and editable (not @editing)) "editable")}
        [textarea
         {:on-change   #(on-change :title %)
          :on-focus    #(reset! editing true)
          :on-blur     #(reset! editing false)
          :max-length  280
          :value       title
          :disabled    (not editable)
          :placeholder "Add title"}]]
       [:div.additional-info
        [:div.url {:class (when (and editable (not @editing)) "editable")}
         [textarea
          {:on-change   #(on-change :url %)
           :on-focus    #(reset! editing true)
           :on-blur     #(reset! editing false)
           :max-length  600
           :value       url
           :disabled    (not editable)
           :placeholder "Add url"}]]]
       [:div.comment {:class (when (and editable (not @editing)) "editable")}
        [textarea
         {:placeholder "Any comments? Why is this link good?"
          :on-change   #(on-change :comment %)
          :on-focus    #(reset! editing true)
          :on-blur     #(reset! editing false)
          :max-length  600
          :value       comment
          :disabled    (not editable)}]]
       [:div.dragging-space]
       [:div.buttons
        [:button.delete {:on-click on-delete} "Delete"]]])))

(defn extract-drop-info [evt]
  (let [url (.. evt -dataTransfer (getData "text/uri-list"))
        edn (.. evt -dataTransfer (getData "edn"))]
    (cond edn (cljs.reader/read-string edn)
          url {:url url})))

(defn add-selected-link
  ([link-info] (add-selected-link link-info nil))
  ([link-info index]
   (swap! state update :selected-links
     (fn [selected-links]
       (let [hash           (or (:hash link-info)
                              (->> (map :hash selected-links)
                                (apply max 0)
                                inc))
             filtered       (if (:hash link-info)
                              (remove #(= (:hash link-info) (:hash %))
                                selected-links)
                              selected-links)
             [before after] (if index
                              (split-at index filtered)
                              [filtered nil])]
         (vec (concat before [(assoc link-info :hash hash)] after)))))))

(defn delete-link [hash]
  (swap! state update :selected-links
    (fn [selected-links]
      (filterv #(not= (:hash %) hash)
        selected-links))))

(defn submit-links []
  (let [selected-links (:selected-links @state)]
    (swap! state dissoc :submission-error)
    (swap! state assoc :submitting true)
    (-> (ampie.backend/send-weekly-links
          selected-links
          (time/js-date->yyyy-MM-dd (js/Date.)))
      (.then (fn [url]
               (.then
                 (.. browser -runtime
                   (sendMessage
                     (clj->js {:type :update-user-info})))
                 #(set! (.. js/window -location -href) url))))
      (.catch (fn [error]
                (swap! state assoc :submission-error (:message error))
                (js/setTimeout #(swap! state dissoc :submission-error)
                  10000)))
      (.finally #(swap! state assoc :submitting false)))))

(defn weekly-links-form []
  (let [dropping       (r/atom 0)
        dropping-empty (r/atom 0)]
    (fn []
      [:div.weekly-links-form
       [:h3 "Chosen links"]
       [url-form]
       [:div.weekly-links-selection
        {:on-drag-leave (fn [_] (swap! dropping #(max (dec %) 0)))
         :on-drag-enter
         (fn [evt]
           (when (.. evt -dataTransfer -types (includes "text/uri-list"))
             (set! (.. evt -dataTransfer -dropEffect) "copy")
             (swap! dropping inc)
             (.preventDefault evt)))}
        (doall
          (for [[index link-info] (map-indexed vector (:selected-links @state))]
            ^{:key [index (:hash link-info)]}
            [weekly-link (assoc link-info :index index
                           :editable (zero? @dropping)
                           :on-delete #(delete-link (:hash link-info))
                           :on-change (fn [key value]
                                        (swap! state assoc-in [:selected-links index key] value))
                           :on-drop
                           (fn [evt]
                             (reset! dropping 0)
                             (.preventDefault evt)
                             (when-let [drop-info (extract-drop-info evt)]
                               (add-selected-link drop-info index))))]))
        [:div.weekly-link.empty-link
         {:class         [#_(when (zero? @dropping) "invisible")
                          (when-not (zero? @dropping-empty) "dropping")]
          :on-drop       (fn [evt]
                           (reset! dropping-empty 0)
                           (reset! dropping 0)
                           (.preventDefault evt)
                           (when-let [drop-info (extract-drop-info evt)]
                             (add-selected-link drop-info)))
          :on-drag-over  #(.preventDefault %)
          :on-drag-leave (fn [evt] (swap! dropping-empty #(max (dec %) 0)))
          :on-drag-enter (fn [evt] (swap! dropping-empty inc) (.preventDefault evt))}
         "drop here"]]
       (when (seq (:selected-links @state))
         [:div.submission-form
          [:div.notice "Ampie will create a public page with the links above and open it in this tab"]
          [:button.submit
           {:on-click submit-links
            :disabled (:submitting @state)}
           (if (:submitting @state)
             "Loading"
             "Share!")]])])))

(defn past-links []
  [:div.past-links
   [:h3 "You spent most time on"]
   [:div.links-list
    (when (empty? (:long-visits @state))
      [:div.empty "You haven't spent more than 5 minutes on any page last week"])
    (for [{visit-hash :visit-hash :as visit} (:long-visits @state)]
      ^{:key visit-hash} [components.visit/visit {:visit visit}])]])

(defn error []
  (when-let [error (:submission-error @state)]
    [:div.error
     {:on-click #(swap! state dissoc :submission-error)}
     error]))

(defn weekly-links-page []
  [:div.weekly-links-page
   [:div.header
    (let [date (-> (js/Date.)
                 time/get-start-of-week
                 (.getTime)
                 time/timestamp->date)]
      [:h1.title (str "Share your links for the week of " date)])
    [:div.notice
     "Choose the best links you have read last week and share them on ampie. "
     "Choose only those that you might like to revisit in a year "
     "(ampie might remind you about them at some point!). "
     "You can drag links from the list on the left or add your own URLs. "
     "Explaining why a particular link is good or adding other comments is encouraged!"]]
   [:div.content
    [past-links]
    [weekly-links-form]]
   [error]])

(defn get-candidate-visits []
  (->
    (visits.db/get-long-visits-since
      (- (js/Date.now) (* 7 24 60 60 1000))
      300)
    (.then
      (fn [long-visits]
        (swap! state assoc :long-visits long-visits)))))

(defn handle-wheel [^js evt]
  (when-let [selected-links-el (.querySelector js/document ".weekly-links-form")]
    (let [rectangle     (.getBoundingClientRect selected-links-el)
          top           (.-top rectangle)
          bottom        (.-bottom rectangle)
          window-height (.. js/window -innerHeight)
          delta-y       (.-deltaY evt)]
      (cond (and (> delta-y 0)
              (<= (- top delta-y) 0)
              (>= (+ bottom 1) window-height)
              (> (- bottom delta-y) window-height))
            (set! (.. selected-links-el -style -top) (str (- top delta-y) "px"))

            (and (< delta-y 0)
              (< top 0))
            (set! (.. selected-links-el -style -top)
              (str (min (- top delta-y) 0) "px"))))))

(defn ^:dev/before-load stop []
  (. js/window removeEventListener "wheel" handle-wheel)
  (mount/stop))

(defn ^:dev/after-load init []
  (get-candidate-visits)
  (mount/start)
  (. js/window addEventListener "wheel" handle-wheel #js {:passive true})
  (rdom/render [weekly-links-page]
    (. js/document getElementById "weekly-links-holder")))
