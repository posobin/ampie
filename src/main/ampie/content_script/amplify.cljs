(ns ampie.content-script.amplify
  (:require ["webextension-polyfill" :as browser]
            ["react-shadow-dom-retarget-events" :as retargetEvents]
            [reagent.dom :as rdom]
            [reagent.core :as r]
            [ampie.url :as url]
            [taoensso.timbre :as log]
            [mount.core :as mount :refer [defstate]]))

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
          (catch :default e)))
    (let [url (.. js/document -location -href)]
      (swap! amplify-info update :uploading inc)
      (swap! amplify-info assoc :failure false :interacted true)
      (->
        (.. browser -runtime
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
                     :mode :edit))))))))

(defn update-amplified-page!
  "Takes the amplify-info atom, sends the message to extension backend to update
  the info for the corresponding visit."
  [amplify-info]
  (swap! amplify-info update :uploading inc)
  (swap! amplify-info assoc :failure false :interacted true)
  (->
    (.. browser -runtime
      (sendMessage (clj->js {:type           :update-amplified-page
                             :comment        (:comment @amplify-info)
                             :reaction       (:reaction @amplify-info)
                             :submission-tag (:submission-tag @amplify-info)})))
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
    (.. browser -runtime
      (sendMessage (clj->js {:type           :delete-amplified-page
                             :submission-tag (:submission-tag @amplify-info)})))
    (.then #(js->clj % :keywordize-keys true))
    (.then (fn [response]
             (swap! amplify-info update :uploading dec)
             (if (:fail response)
               (swap! amplify-info assoc
                 :failure true
                 :error (:message response))
               (swap! amplify-info assoc
                 :mode :suggest-sharing
                 :amplified false
                 :updated false))))))

(defn close-amplify-dialog [amplify-info]
  (swap! amplify-info assoc :mode nil :interacted true))

(defn comment-updater [amplify-info]
  (let [timeout-id (atom nil)]
    (fn [text-change-event]
      (let [old-comment (:comment @amplify-info)
            new-comment (.. text-change-event -target -value)]
        (when-not (= old-comment new-comment)
          (when @timeout-id (js/clearTimeout @timeout-id))
          (swap! amplify-info assoc :comment new-comment)
          (reset! timeout-id
            (js/setTimeout #(update-amplified-page! amplify-info) 500)))))))

(defn amplify-dialog [{:keys [amplify-info]}]
  (let [comment-focused (r/atom false)]
    (fn [{:keys [amplify-info]}]
      (when-let [mode (:mode @amplify-info)]
        (cond
          (= mode :suggest-sharing)
          [:div.amplify-dialog
           [:p
            (if (pos? (:uploading @amplify-info))
              [:button.small {:disabled true} "Sending"]
              [:button.small {:on-click #(amplify-page! amplify-info)}
               "Amplify"])
            [:span.shortcut "⌘-Shift-A"]]
           [:div.close {:on-click    #(close-amplify-dialog amplify-info)
                        :tab-index   0
                        :style       {:top "8px"}
                        :on-key-down #(when (or (= (.-code %) "Enter")
                                              (= (.-code %) "Space"))
                                        (.stopPropagation %)
                                        (close-amplify-dialog amplify-info))}
            [:span.shortcut "Esc"]
            [:span.icon.close-icon]]
           [:p (str "Click to let your followers "
                 "know that you have been to this URL.")]
           (when (:failure @amplify-info)
             [:p.error "Couldn't upload the data. " (:error @amplify-info)])]

          (= mode :edit)
          [:div.amplify-dialog
           {:class (when (seq (:comment @amplify-info))
                     "expanded")}
           [:div.close {:on-click    #(close-amplify-dialog amplify-info) :tab-index 0
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
           [:p "Your followers will now see that you have been to this page"]
           [:div.comment-holder
            [:div.shortcut.appearing
             {:class (when @comment-focused "hidden")}
             "⌘-Shift-A"]
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
              :on-key-down #(.stopPropagation %)
              :on-change   (comment-updater amplify-info)
              :value       (:comment @amplify-info)
              :placeholder "Add a comment"}]]
           [:ul.reaction.choice
            (let [chosen-reaction (:reaction @amplify-info)]
              (doall
                (for [value (into ["like" "meh" "dislike" "to read"]
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
           [:div.delete
            ;; Need the key tag because otherwise when tabbing from textarea
            ;; focused is updated and this button is re-rendered
            ^{:key "delete-button"}
            [:button.small {:on-click #(delete-amplified-page! amplify-info)}
             "Delete"]]
           (when (:focused @amplify-info)
             [:p "Press" [:span.shortcut "Tab"]])
           (when (:failure @amplify-info)
             [:p.error "Couldn't upload the data. " (:error @amplify-info)])])))))

(defn process-key-press [amplify-dialog-div amplify-info]
  (fn [e]
    (when (zero? (:uploading @amplify-info))
      (-> (case (:mode @amplify-info)
            :suggest-sharing
            (case (.-key e)
              ("Escape" "Esc") (close-amplify-dialog amplify-info)
              :pass)
            :edit
            (case (.-key e)
              ("Escape" "Esc") (close-amplify-dialog amplify-info)
              :pass)
            nil
            :pass)
        (= :pass)
        (when-not (.stopPropagation e))))))

(def time-info-atom (atom {:ms-spent 0 :last-start nil}))

(defn get-total-ms-spent []
  (let [{:keys [ms-spent last-start]} @time-info-atom]
    (+ ms-spent (when last-start (- (js/Date.) last-start)))))

(defn on-blur-time-count []
  (swap! time-info-atom
    (fn [{:keys [last-start ms-spent idleness-timeout-id] :as time-info}]
      (js/clearTimeout idleness-timeout-id)
      (dissoc
        (if last-start
          (assoc time-info
            :last-start nil
            :ms-spent (+ ms-spent (- (js/Date.) last-start)))
          time-info)
        :idleness-timeout-id))))

(defn on-active-time-count []
  (let [new-timeout-id (js/setTimeout on-blur-time-count 30000)]
    (swap! time-info-atom
      (fn [{:keys [last-start ms-spent idleness-timeout-id] :as time-info}]
        (js/clearTimeout idleness-timeout-id)
        (assoc (if-not last-start
                 (assoc time-info :last-start (js/Date.))
                 time-info)
          :idleness-timeout-id new-timeout-id)))))

(def on-active-targets-and-events
  [[js/window ["focus" "scroll"]]
   [js/document ["mousedown" "keydown" "touchstart"]]])

(defn show-amplify-dialog-if-fresh [amplify-info]
  (let [{:keys [mode amplified deleted interacted]} @amplify-info]
    (when-not (or mode amplified deleted interacted)
      (swap! amplify-info assoc :mode :suggest-sharing))))

(defn can-show-amplify-dialog? []
  (let [video        (. js/document querySelector "video")
        current-time (and video (.-currentTime video))
        paused       (and video (.-paused video))
        ended        (and video (.-ended video))
        playing      (and (pos? current-time) (not paused) (not ended))]
    (or (not video) (not playing))))

(defn start-counting-time [amplify-info]
  (when (.hasFocus js/document) (on-active-time-count))
  (doseq [[target events] on-active-targets-and-events
          event           events]
    (. target addEventListener event on-active-time-count))
  (. js/window addEventListener "blur" on-blur-time-count)
  (swap! amplify-info assoc :amplify-timer-interval-id
    (js/setInterval
      (fn []
        (when (and (> (get-total-ms-spent) (* 2 3 1000))
                (can-show-amplify-dialog?))
          (show-amplify-dialog-if-fresh amplify-info)
          (swap! amplify-info
            (fn [{interval-id :amplify-timer-interval-id :as amplify-info}]
              (when interval-id (js/clearInterval interval-id))
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
  (let [amplify-dialog-div (. js/document createElement "div")
        shadow-root-el     (. js/document createElement "div")
        shadow             (. shadow-root-el (attachShadow #js {"mode" "open"}))
        shadow-style       (. js/document createElement "link")
        amplify-info       (r/atom {:mode nil :uploading 0})
        on-key-down        (process-key-press amplify-dialog-div amplify-info)]
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
    (. shadow-root-el addEventListener "focus" #(swap! amplify-info assoc :focused true))
    (. shadow-root-el addEventListener "focusout" #(swap! amplify-info assoc :focused false))
    (.. js/document -body (appendChild shadow-root-el))
    (.then
      (.. browser -runtime
        (sendMessage (clj->js {:type :get-time-spent-on-url})))
      (fn [time-spent]
        ;; time-spent is in seconds
        (when (< time-spent 120)
          (swap! time-info-atom assoc :ms-spent (* time-spent 1000))
          (start-counting-time amplify-info))))
    {:show-dialog  #(swap! amplify-info assoc :mode :suggest-sharing)
     :amplify-page #(amplify-page! amplify-info)
     :on-key-down  on-key-down
     :amplify-info amplify-info}))

(defstate amplify
  :start (create-amplify-dialog)
  :stop (remove-amplify-dialog (:on-key-down @amplify) (:amplify-info @amplify)))
