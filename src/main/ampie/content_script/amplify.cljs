(ns ampie.content-script.amplify
  (:require ["webextension-polyfill" :as browser]
            ["react-shadow-dom-retarget-events" :as retargetEvents]
            [reagent.dom :as rdom]
            [reagent.core :as r]
            [ampie.content-script.demo :refer [is-demo-url? send-message-to-page]]
            [ampie.components.basics :as b]
            [ampie.visits.db :as visits.db]
            [taoensso.timbre :as log]
            [mount.core :as mount :refer [defstate]]
            [clojure.string]))

(declare my-last-visits)

(defn- get-current-hashless-url []
  (-> (.. js/document -location -href)
    ;; Split on hashes not followed by !
    (clojure.string/split #"#(?!!)")
    first))

(defn- group-visits
  "Returns a map with keys :timeline, :tag->visit, :tag->journey.
  `timeline` contains a vector of maps, each either {:type :journey :journey-tag journey-tag}
  or {:type :visit :visit-tag visit-tag}, in the order they should be shown.
  Only root-level visits that are not in a journey are in this list and journeys,
  each only once. `tag->visit` maps visit tag to a visit, plus each visit has :child-tags -
  a vector with all its childrens tags.
  `tag->journey` maps journey tag to a journey, plus each journey has a :child-tags -
  a vector with all the root-level visits with this journey."
  [visits]
  (let [tags           (set (map :visit/tag visits))
        tag->visit     (->> (map (juxt :visit/tag identity) visits)
                         (into {}))
        roots          (remove (comp tags :visit/parent-tag) visits)
        [tag->visit
         tag->journey] ;; Add child-tags
        (reduce (fn [[tag->visit tag->journey]
                     {:visit/keys [tag parent-tag journey-tag]}]
                  [(cond-> tag->visit
                     parent-tag
                     (update-in [parent-tag :child-tags] (fnil #(conj % tag) [])))
                   (cond-> tag->journey
                     (and journey-tag
                       ;; Only add root-level nodes to the journeys
                       (not (tags parent-tag)))
                     (update-in [journey-tag :child-tags] (fnil #(conj % tag) [])))])
          [tag->visit {}] visits)
        {:keys [timeline]}
        (reduce (fn [{:keys [seen-journeys] :as result} {:visit/keys [journey-tag tag] :as visit}]
                  (cond (not (:visit/journey-tag visit))
                        (update result :timeline conj {:type :visit :visit-tag tag})
                        (not (seen-journeys (:visit/journey-tag visit)))
                        (-> (update result :timeline conj {:type :journey :journey-tag journey-tag})
                          (update result :seen-journeys conj journey-tag))
                        :else result))
          {:seen-journeys #{}
           :timeline      []}
          roots)]
    {:timeline     timeline
     :tag->visit   tag->visit
     :tag->journey tag->journey}))

(defn amplify-page!
  "Sends a message to the extension backend to amplify the current page, updates
  the `amplify-info` state atom accordingly."
  [amplify-info]
  (if (:amplified @amplify-info)
    (do (swap! amplify-info assoc :mode :edit :interacted true)
        (try
          (.. js/document
            (querySelector ".ampie-amplify-dialog-holder")
            -shadowRoot
            (querySelector ".comment-field")
            (focus))
          (catch :default _)))
    (let [url (get-current-hashless-url)]
      (when (zero? (:uploading @amplify-info))
        (if (is-demo-url? (.. js/document -location -href))
          (do
            (send-message-to-page {:type :ampie-amplify-page :url url})
            (swap! amplify-info assoc
              :interacted true
              :amplified true
              :failure false
              :submission-tag nil
              :mode :edit))
          (do
            (.. browser -runtime
              (sendMessage (clj->js {:type :saw-amplify-dialog
                                     :url  url})))
            (swap! amplify-info
              (fn [{:keys [uploading mode] :as info}]
                (assoc info
                  :uploading (inc uploading)
                  :mode (or mode :suggest-sharing)
                  :failure false
                  :interacted true)))
            (-> (.. browser -runtime
                  (sendMessage (clj->js {:type :amplify-page :url url})))
              (.then #(js->clj % :keywordize-keys true))
              (.then (fn [response]
                       (swap! amplify-info update :uploading dec)
                       (if (:fail response)
                         (swap! amplify-info assoc
                           :mode :suggest-sharing
                           :failure true
                           :error (:message response))
                         (swap! amplify-info assoc
                           :amplified true
                           :failure false
                           :submission-tag (:submission-tag response)
                           :mode :edit))))
              ;; TODO(journeys)
              #_(.then #(.. browser -runtime
                          (sendMessage (clj->js {:type :get-my-last-visits}))))
              #_(.then #(js->clj % :keywordize-keys true))
              #_(.then (fn [response]
                         (reset! my-last-visits
                           (group-visits (:visits response))))))))))))

(defn update-amplified-page!
  "Takes the amplify-info atom, sends the message to extension backend to update
  the info for the corresponding visit."
  [amplify-info]
  (swap! amplify-info update :uploading inc)
  (swap! amplify-info assoc :failure false :interacted true)
  (->
    (if (is-demo-url? (.. js/document -location -href))
      (js/Promise.resolve #js {:result :ok})
      (.. browser -runtime
        (sendMessage (clj->js {:type           :update-amplified-page
                               :comment        (:comment @amplify-info)
                               :reaction       (:reaction @amplify-info)
                               :submission-tag (:submission-tag @amplify-info)}))))
    (.then #(js->clj % :keywordize-keys true))
    (.then (fn [response]
             (swap! amplify-info update :uploading dec)
             (if (:fail response)
               (swap! amplify-info assoc
                 :failure true
                 :error (:message response))
               (swap! amplify-info assoc
                 :updated true))))))

(defn delete-amplified-page!
  [amplify-info]
  (swap! amplify-info update :uploading inc)
  (swap! amplify-info assoc :failure false :interacted true)
  (->
    (if (is-demo-url? (.. js/document -location -href))
      (js/Promise.resolve #js {:result :ok})
      (.. browser -runtime
        (sendMessage (clj->js {:type           :delete-amplified-page
                               :submission-tag (:submission-tag @amplify-info)}))))
    (.then #(js->clj % :keywordize-keys true))
    (.then (fn [response]
             (swap! amplify-info update :uploading dec)
             (if (:fail response)
               (swap! amplify-info assoc
                 :failure true
                 :error (:message response))
               (swap! amplify-info assoc
                 :mode :suggest-sharing
                 :comment nil
                 :reaction nil
                 :submission-tag nil
                 :amplified false
                 :updated false))))))

(defn vote-on-page! [amplify-info upvote?]
  (if (:amplified @amplify-info)
    (do (swap! amplify-info assoc :mode :edit :interacted true)
        (try
          (.. js/document
            (querySelector ".ampie-amplify-dialog-holder")
            -shadowRoot
            (querySelector ".comment-field")
            (focus))
          (catch :default _)))
    (let [url (get-current-hashless-url)]
      (when (zero? (:uploading @amplify-info))
        (if (is-demo-url? (.. js/document -location -href))
          (comment
            (send-message-to-page {:type :ampie-amplify-page :url url})
            (swap! amplify-info assoc
              :interacted true
              :amplified true
              :failure false
              :submission-tag nil
              :mode :edit))
          (do
            (.. browser -runtime
              (sendMessage (clj->js {:type :saw-amplify-dialog
                                     :url  url})))
            (swap! amplify-info
              (fn [{:keys [uploading mode] :as info}]
                (assoc info
                  :uploading (inc uploading)
                  :mode (or mode :suggest-sharing)
                  :failure false
                  :interacted true)))
            (-> (.. browser -runtime
                  (sendMessage (clj->js {:type :vote-on-page :upvote? upvote?
                                         :url  url})))
              (.then #(js->clj % :keywordize-keys true))
              (.then (fn [response]
                       (swap! amplify-info update :uploading dec)
                       (if (:fail response)
                         (swap! amplify-info assoc
                           :mode :suggest-sharing
                           :failure true
                           :error (:message response))
                         (swap! amplify-info assoc
                           :amplified true
                           :upvoted true
                           :failure false
                           :vote-id (:url-vote/id response)
                           :mode :edit)))))))))))

(defn close-amplify-dialog [amplify-info]
  (swap! amplify-info assoc :mode nil :interacted true))

(defn comment-updater [amplify-info]
  (fn [text-change-event]
    (let [old-comment (:comment @amplify-info)
          new-comment (.. text-change-event -target -value)]
      (when-not (= old-comment new-comment)
        (swap! amplify-info assoc :comment new-comment)))))

(def shortcuts (r/atom {}))

(defn load-enabled-shortcuts []
  (.then
    (.. browser -runtime
      (sendMessage (clj->js {:type :get-command-shortcuts})))
    (fn [^js js-shortcuts]
      (->> (js->clj js-shortcuts :keywordize-keys true)
        (reduce #(assoc %1 (keyword (:name %2)) %2) {})
        (reset! shortcuts)))))

(defn suggest-sharing [amplify-info]
  [:div.amplify-dialog.suggest-sharing
   {:class (when (:fullscreen @amplify-info) :upper-right)}
   [:div (if (pos? (:uploading @amplify-info))
           [:button.small {:disabled true} "Sending"]
           [:button.small {:on-click #(amplify-page! amplify-info)}
            "Amplify"])
    (let [s (-> @shortcuts :amplify_page :shortcut)]
      (when-not (clojure.string/blank? s)
        [:span.shortcut s]))]
   ;; TODO(page_votes)
   #_[:div {:role     :button
            :on-click #(vote-on-page! amplify-info true)}
      (let [s (-> @shortcuts :upvote_page :shortcut)]
        (when-not (clojure.string/blank? s)
          [:span.shortcut s]))
      "Upvote"]
   ;; TODO(page_votes)
   #_[:div {:role     :button
            :on-click #(vote-on-page! amplify-info false)}
      (let [s (-> @shortcuts :downvote_page :shortcut)]
        (when-not (clojure.string/blank? s)
          [:span.shortcut s]))
      "Downvote"]
   [:div.close {:on-click    #(close-amplify-dialog amplify-info)
                :role        "button"
                :tab-index   0
                :on-key-down #(when (or (= (.-code %) "Enter")
                                      (= (.-code %) "Space"))
                                (.stopPropagation %)
                                (close-amplify-dialog amplify-info))}
    [:span.shortcut "Esc"]
    [:span.icon.close-icon]]
   (when (:failure @amplify-info)
     [:p.error "Couldn't upload the data. " (:error @amplify-info)])])

(defonce my-last-visits (r/atom nil))

(defonce chosen-visit-tag (r/atom nil))

(defn is-ancestor? [tag-child tag-ancestor]
  (if (= tag-child tag-ancestor)
    true
    (let [child (get-in @my-last-visits [:tag->visit tag-child])]
      (if-let [parent-tag (:visit/parent-tag child)]
        (is-ancestor? parent-tag tag-ancestor)
        false))))

(defn visit-component [{:keys [amplify-info tag]}]
  (let [state (r/atom {})]
    (fn [{:keys [amplify-info tag]}]
      (let [visit (get-in @my-last-visits [:tag->visit tag])]
        [:div.visit
         {:class             (when (= tag (:submission-tag @amplify-info)) "this-visit")
          :draggable         (when (= tag (:submission-tag @amplify-info)) "true")
          #_#_:on-drag-end   #(reset! dragging false)
          :on-drag-start     (fn [^js evt]
                               (.. evt -dataTransfer (setData "text/plain" (:link/original visit)))
                               (.. evt -dataTransfer (setData "text/uuid" tag))
                               #_(.. evt -dataTransfer
                                   (setData "edn"
                                     (pr-str (select-keys link
                                               [:title :url :time-spent
                                                :hash :comment]))))
                               (set! (.. evt -dataTransfer -effectAllowed) "move")
                               #_(reset! dragging true))
          :on-drag-over      #(.preventDefault %)
          #_#_:on-drag-leave (fn [evt] (swap! dropping-empty #(max (dec %) 0)))
          #_#_:on-drag-enter (fn [evt] (swap! dropping-empty inc) (.preventDefault evt))
          :on-drop
          (fn [^js evt]
            (.preventDefault evt)
            (when-let [drop-tag (.. evt -dataTransfer (getData "text/uuid"))]
              (when-not (is-ancestor? tag drop-tag)
                (.stopPropagation evt)
                (swap! my-last-visits
                  (fn [my-last-visits]
                    (let [{:visit/keys [journey-tag parent-tag]}
                          (get-in my-last-visits [:tag->visit drop-tag])]
                      (cond-> (update-in my-last-visits [:tag->visit drop-tag]
                                merge {:visit/journey-tag (:visit/journey-tag visit)
                                       :visit/parent-tag  tag})
                        parent-tag  (update-in [:tag->visit parent-tag :child-tags]
                                      #(filterv (complement #{drop-tag}) %))
                        journey-tag (update-in [:tag->journey journey-tag :child-tags]
                                      #(filterv (complement #{drop-tag}) %))
                        true        (update-in
                                      [:tag->visit tag :child-tags]
                                      (fnil #(conj % drop-tag) [])))))))))
          #_#_:on-drop       (fn [evt] (reset! dropping 0) (on-drop evt))
          #_#_:on-drag-over  (fn [evt] (.preventDefault evt))
          #_#_:on-drag-leave (fn [evt] (swap! dropping #(max (dec %) 0)))
          #_#_:on-drag-enter (fn [evt] (swap! dropping inc) (.preventDefault evt))
          #_#_:on-click
          #(swap! amplify-info
             (fn [{:keys [parent-tag] :as amplify-info}]
               (assoc amplify-info
                 :parent-tag
                 (if (not= parent-tag tag)
                   tag
                   nil))))}
         [:div.top-line
          (if-let [favicon-url (:page/favicon-url visit)]
            [:img.icon {:src favicon-url}]
            [:div.icon])
          [:div.title (:visit/title visit)]]
         [:div.children
          (for [child-tag (:child-tags visit)]
            ^{:key child-tag}
            [visit-component {:amplify-info amplify-info
                              :tag          child-tag}])]]))))

(defn journey-component [amplify-info journey-tag]
  (let [journey (get-in @my-last-visits [:tag->journey journey-tag])]
    [:div.journey
     [:div.journey-name (:journey/name journey)]
     [:div.children
      (for [child-tag (:child-tags journey)]
        ^{:key child-tag}
        [visit-component {:amplify-info amplify-info
                          :tag          child-tag}])]]))

(defn last-amplified-pages [amplify-info]
  (let [{:keys [timeline tag->visit]} @my-last-visits
        current-url                   (get-current-hashless-url)
        amplified-tag                 (:submission-tag @amplify-info)
        amplified-visit               (get-in @my-last-visits [:tag->visit amplified-tag])]
    (when (seq timeline)
      [:div
       [:p "Drag this page on one you amplified previously to create a journey."]
       (when-not (or (:visit/parent-tag amplified-visit) (:visit/journey-tag amplified-visit))
         (js/console.log amplified-visit)
         [:div.my-visits
          [visit-component {:amplify-info amplify-info
                            :tag          amplified-tag}]])
       [:div.my-visits
        (doall
          (for [{:keys [type visit-tag journey-tag]}
                timeline
                :when (not= (:link/original (tag->visit visit-tag))
                        current-url)]
            (with-meta
              (case type
                :visit   [visit-component {:amplify-info amplify-info :tag visit-tag}]
                :journey [journey-component journey-tag])
              {:key (or visit-tag journey-tag)})))]])))

(defn edit-amplify [_amplify-info]
  (let [comment-focused (r/atom false)]
    (fn [amplify-info]
      [:div.amplify-dialog.expanded
       {:class [(when (:fullscreen @amplify-info) :upper-right)]}
       [:div.close {:on-click    #(close-amplify-dialog amplify-info) :tab-index 0
                    :role        "button"
                    :on-key-down #(when (or (= (.-code %) "Enter")
                                          (= (.-code %) "Space"))
                                    (close-amplify-dialog amplify-info))}
        [:span.shortcut "Esc"]
        [:span.icon.close-icon]]
       [:div.header
        (cond
          (pos? (:uploading @amplify-info))
          [:<> (if (:amplified @amplify-info) "Updating" "Sending")
           [:span.spinner]]
          (:failure @amplify-info) [:<> "Error"]
          (:updated @amplify-info) [:<> "Updated" [:span.checkmark]]
          :else                    [:<> "Page amplified" [:span.checkmark]])]
       ;; TODO(journeys)
       #_[last-amplified-pages amplify-info]
       [:p "Your followers will now see that you found this page worth visiting. "
        "The pages you amplify are available at "
        [:a (b/ahref-opts "https://ampie.app") "ampie.app"] "."]
       [:div.comment-holder
        (let [s (-> @shortcuts :amplify_page :shortcut)]
          (cond @comment-focused
                [:div.shortcut.appearing.right "Shift-↵"]
                (not (clojure.string/blank? s))
                [:div.shortcut.appearing s]))
        ;; Without key reagent re-renders the textarea on focus on atom
        ;; update.
        ^{:key "comment-field"}
        [:textarea.comment-field
         ;; Using ref because on-focus wouldn't work, probably because
         ;; we are in shadow DOM. Didn't figure it out though, maybe
         ;; take a look some time how to make on-focus work.
         ;; I am using the retarget events library, so expected it
         ;; to handle everything.
         {:ref
          (fn [el]
            (when el
              (reset! comment-focused (= el (.. el (getRootNode) -activeElement)))
              (.addEventListener el
                "focus"
                #(reset! comment-focused true))))
          :max-length  1000
          :on-blur     #(reset! comment-focused false)
          :auto-focus  true
          :on-key-down (fn [^js evt]
                         (when (and (= (.-key evt) "Enter")
                                 (.-shiftKey evt)
                                 (zero? (:uploading @amplify-info)))
                           (.preventDefault evt)
                           (update-amplified-page! amplify-info)))
          :on-change   (comment-updater amplify-info)
          :value       (:comment @amplify-info)
          :placeholder "Add a comment"}]]
       [:ul.reaction.choice
        (let [chosen-reaction (:reaction @amplify-info)]
          (doall
            (for [value (into ["like" "to read"]
                          (when chosen-reaction ["clear"]))
                  :when (or (nil? chosen-reaction)
                          (= value chosen-reaction)
                          (= value "clear"))]
              ^{:key value}
              [:li {:class [(when (= chosen-reaction value) "selected")
                            (when (= value "clear") "clear")]}
               [:button.inline
                {:on-click
                 (fn []
                   (when (= value "clear")
                     (.. js/document
                       (querySelector ".ampie-amplify-dialog-holder")
                       -shadowRoot
                       (querySelector "ul.reaction.choice li.selected button")
                       (focus)))
                   (swap! amplify-info assoc
                     :reaction (when-not (= value "clear") value))
                   (update-amplified-page! amplify-info))}
                value]])))]
       (when (:focused @amplify-info)
         [:span {:style {:font-weight "initial" :margin-left "1em"}}
          "Press" [:span.shortcut "Tab"]])
       [:div.float-right
        [:div.delete
         ;; Need the key tag because otherwise when tabbing from textarea
         ;; focused is updated and this button is re-rendered
         ^{:key "delete-button"}
         [:button.small {:on-click #(delete-amplified-page! amplify-info)}
          "Delete"]]
        [:div.update
         ^{:key "update-button"}
         [:button.small {:on-click #(update-amplified-page! amplify-info)
                         :disabled (pos? (:uploading @amplify-info))}
          "Update"]]]
       (when (:failure @amplify-info)
         [:p.error "Couldn't upload the data. " (:error @amplify-info)])])))

(defn amplify-dialog [{:keys [amplify-info]}]
  (when-let [mode (:mode @amplify-info)]
    (cond
      (and (not (:text-focused @amplify-info))
        (= mode :suggest-sharing))
      [suggest-sharing amplify-info]

      (= mode :edit)
      [edit-amplify amplify-info])))

(defn process-key-press [amplify-info]
  (fn [e]
    (when (zero? (:uploading @amplify-info))
      (-> (case (:mode @amplify-info)
            :suggest-sharing
            (cond
              (or (= (.-key e) "Escape") (= (.-key e) "Esc"))
              (close-amplify-dialog amplify-info)
              :else :pass)
            :edit
            (cond
              (or (= (.-key e) "Escape") (= (.-key e) "Esc"))
              (close-amplify-dialog amplify-info)
              :else :pass)
            nil
            :pass)
        (= :pass)
        (when-not (.stopPropagation e))))))

(defstate time-info-atom :start (atom {:ms-spent 0 :last-start nil}))

(defn get-total-ms-spent []
  (let [{:keys [ms-spent last-start]} @@time-info-atom]
    (+ ms-spent (when last-start (- (js/Date.) last-start)))))

(defn on-blur-time-count []
  (swap! @time-info-atom
    (fn [{:keys [last-start ms-spent idleness-timeout-id] :as time-info}]
      (js/clearTimeout idleness-timeout-id)
      (dissoc
        (if last-start
          (assoc time-info
            :last-start nil
            :ms-spent (+ ms-spent (- (js/Date.) last-start)))
          time-info)
        :idleness-timeout-id))))

(defn is-video-playing? []
  (let [video        (. js/document querySelector "video")
        current-time (and video (.-currentTime video))
        paused       (and video (.-paused video))
        ended        (and video (.-ended video))
        playing      (and video (pos? current-time) (not paused) (not ended))]
    playing))

(defn on-active-time-count []
  (let [new-timeout-id
        (js/setTimeout
          (fn try-to-blur []
            (if-not (is-video-playing?)
              (on-blur-time-count)
              (let [try-again-later-id (js/setTimeout try-to-blur 30000)]
                (swap! @time-info-atom
                  assoc :idleness-timeout-id try-again-later-id))))
          30000)]
    (swap! @time-info-atom
      (fn [{:keys [last-start idleness-timeout-id] :as time-info}]
        (js/clearTimeout idleness-timeout-id)
        (assoc (if-not last-start
                 (assoc time-info :last-start (js/Date.))
                 time-info)
          :idleness-timeout-id new-timeout-id)))))

(defn show-amplify-dialog-if-fresh [amplify-info]
  (.. browser -runtime
    (sendMessage (clj->js {:type :saw-amplify-dialog
                           :url  (-> (.. js/document -location -href)
                                   (clojure.string/split #"#")
                                   first)})))
  (let [{:keys [mode amplified deleted interacted]} @amplify-info]
    (when-not (or mode amplified deleted interacted)
      (swap! amplify-info assoc :mode :suggest-sharing))))

(defn can-show-amplify-dialog? [] (not (is-video-playing?)))

(def on-active-targets-and-events
  [[js/window ["focus" "scroll"]]
   [js/document ["mousedown" "keydown" "touchstart"]]])

(defn start-counting-time [amplify-info]
  (when (.hasFocus js/document) (on-active-time-count))
  (doseq [[target events] on-active-targets-and-events
          event           events]
    (. target addEventListener event on-active-time-count))
  (. js/window addEventListener "blur" on-blur-time-count)
  (swap! amplify-info assoc :amplify-timer-interval-id
    (js/setInterval
      (fn []
        (when (and (> (get-total-ms-spent) (* 2 60 1000))
                (can-show-amplify-dialog?))
          (show-amplify-dialog-if-fresh amplify-info))
        (when-not (can-show-amplify-dialog?)
          (swap! amplify-info
            (fn [{:keys [interacted mode] :as amplify-info}]
              (assoc amplify-info :mode (and interacted mode)))))
        (when (:interacted @amplify-info)
          (swap! amplify-info
            (fn [{:keys [amplify-timer-interval-id] :as amplify-info}]
              (when amplify-timer-interval-id
                (js/clearInterval amplify-timer-interval-id))
              (dissoc amplify-info :amplify-timer-interval-id)))))
      1000)))

(defn stop-counting-time [amplify-info]
  (doseq [[target events] on-active-targets-and-events
          event           events]
    (. target removeEventListener event on-active-time-count))
  (. js/window removeEventListener "blur" on-blur-time-count)
  (js/clearInterval (:amplify-timer-interval-id @amplify-info)))

(defn remove-amplify-dialog [on-key-down amplify-info]
  (let [element (.. js/document -body (querySelector ".ampie-amplify-dialog-holder"))]
    (when element (.. js/document -body (removeChild element))))
  (. js/document removeEventListener "keydown" on-key-down)
  (stop-counting-time amplify-info))

(defn create-amplify-dialog []
  (when (. js/document querySelector ".ampie-amplify-dialog-holder")
    (when goog.DEBUG #_(js/alert "ampie: amplify dialog already exists"))
    (throw "amplify dialog already exists"))
  (load-enabled-shortcuts)
  (let [amplify-dialog-div (. js/document createElement "div")
        shadow-root-el     (. js/document createElement "div")
        shadow             (. shadow-root-el (attachShadow #js {"mode" "open"}))
        shadow-style       (. js/document createElement "link")
        amplify-info       (r/atom {:mode nil :uploading 0})
        on-key-down        (process-key-press amplify-info)]
    (set! (.-rel shadow-style) "stylesheet")
    (.setAttribute shadow-root-el "style"  "display: none;")
    (set! (.-onload shadow-style) #(.setAttribute shadow-root-el "style" ""))
    (set! (.-href shadow-style) (.. browser -runtime (getURL "assets/amplify-dialog.css")))
    (set! (.-className shadow-root-el) "ampie-amplify-dialog-holder")
    (set! (.-className amplify-dialog-div) "amplify-dialog-container")
    (rdom/render [amplify-dialog {:amplify-info amplify-info}] amplify-dialog-div)
    (. shadow (appendChild shadow-style))
    (. shadow (appendChild amplify-dialog-div))
    (retargetEvents shadow)
    (. js/document addEventListener "keydown" on-key-down)
    ;; Hiding the amplify dialog when focused in a text field
    (let [text-node-types
          #{"text" "password" "number" "email" "tel" "url" "search" "date"
            "datetime" "datetime-local" "time" "month" "week"}
          is-text-node?
          (fn is-text-node? [el]
            (let [tag-name (.. el -tagName (toLowerCase))]
              (or (= (.-contentEditable el) "true")
                (= tag-name "textarea")
                (and (= tag-name "input")
                  (contains? text-node-types (.. el -type (toLowerCase)))))))]
      (. js/document addEventListener "focusin"
        (fn [_]
          (when (is-text-node? (.-activeElement js/document))
            (swap! amplify-info assoc :text-focused true))))
      (. js/document addEventListener "focusout"
        (fn [_]
          (swap! amplify-info assoc :text-focused false)))
      (. js/document addEventListener "fullscreenchange"
        (fn [_]
          (if (.-fullscreenElement js/document)
            (swap! amplify-info assoc :fullscreen true)
            (swap! amplify-info assoc :fullscreen false)))))
    (. shadow-root-el addEventListener "focus" #(swap! amplify-info assoc :focused true))
    (. shadow-root-el addEventListener "focusout" #(swap! amplify-info assoc :focused false))
    (.. js/document -body (appendChild shadow-root-el))
    (.then
      (js/Promise.all
        (array
          #_(.. browser -runtime
              (sendMessage (clj->js {:type :get-time-spent-on-url})))
          (.. browser -runtime
            (sendMessage (clj->js {:type :amplify-dialog-enabled?})))
          (.. browser -runtime
            (sendMessage
              (clj->js
                {:type :saw-amplify-before?
                 :url  (-> (.. js/document -location -href)
                         (clojure.string/split #"#")
                         first)})))))
      (fn [[enabled saw-amplify-before]]
        ;; time-spent is in seconds
        (when (and #_(< time-spent 120) enabled (not saw-amplify-before))
          (swap! @time-info-atom assoc :ms-spent 0)
          (start-counting-time amplify-info))))
    {:show-dialog  #(swap! amplify-info assoc :mode :suggest-sharing)
     :amplify-page #(amplify-page! amplify-info)
     :vote-on-page #(vote-on-page! amplify-info %)
     :on-key-down  on-key-down
     :amplify-info amplify-info}))

(defstate amplify
  :start (create-amplify-dialog)
  :stop (remove-amplify-dialog (:on-key-down @amplify) (:amplify-info @amplify)))
