(ns ampie.content-script.badge
  (:require ["webextension-polyfill" :as browser]
            [ampie.interop :as i]
            [ampie.url :as url]
            [taoensso.timbre :as log]
            [mount.core]
            [clojure.string :as string]
            [cljs.core.async :refer [go go-loop >! <! alts! chan]])
  (:require-macros [mount.core :refer [defstate]]))

(defn- send-urls-to-background
  "Sends the given urls to background so that it saves them to seen-urls,
  and returns the map url -> [visits on which saw the url]."
  [urls response-handler]
  (when (seq urls)
    (-> (.. browser -runtime
          (sendMessage (clj->js {:type     :add-seen-urls
                                 ;; TODO don't send the same normalized url twice
                                 :urls     (vec (set urls))
                                 :page-url (.. js/window -location -href)})))
      (.then
        (fn [urls-info]
          (let [urls-info    (js->clj urls-info :keywordize-keys true)
                current-nurl (url/normalize (.. js/window -location -href))
                cleaned-up   (->> urls-info
                               (filter #(and (not= (:normalized-url %) current-nurl)
                                          (or (seq (:twitter %)) (seq (:hn %))
                                            (seq (:visits %)))))
                               (map #(vector (:url %) %))
                               (into {}))]
            (response-handler cleaned-up)))))))

(defn- get-z-index
  "Compute z-index of the given element."
  [element]
  (let [style   (. js/window getComputedStyle element)
        z-index (.getPropertyValue style "z-index")]
    (when-not (= z-index "auto")
      (int z-index))))

(defn- is-visible?
  "Check if the element is displayed on the page, `element` has to be not null."
  [element]
  (let [style     (. js/window getComputedStyle element)
        transform (= (. style getPropertyValue "transform")
                    "matrix(1, 0, 0, 0, 0, 0)")
        hidden    (= (. style getPropertyValue "visibility") "hidden")
        display   (= (. style getPropertyValue "display") "none")]
    (not (or transform hidden display))))

(defn- table-element?
  "Check if the element is td, th or table.
  Useful because positioning is disallowed in the offsetParents of those kinds."
  [element]
  (contains? #{"td" "th" "table"}
    (string/lower-case (.-tagName element))))

(defn- is-fixed? [element]
  (and (some? element)
    (or (= (-> (. js/window getComputedStyle element) (. -position))
          "fixed")
      #_(recur (.-offsetParent element)))))

(defn- get-offsets
  ([badge element target-fixed?]
   (let [badge-rect    (.getBoundingClientRect badge)
         target-rect   (.getBoundingClientRect element)
         element-width (.-offsetWidth element)
         style         (. js/window getComputedStyle element)
         width         (if (and (= element-width 0)
                             (.-firstChild element))
                         (.. element -firstChild -offsetWidth)
                         element-width)
         x             (+ (- (.-left target-rect) (.-left badge-rect)
                            (js/parseFloat (.-paddingRight style)))
                         width)
         y             (+ (- (.-top target-rect) (.-top badge-rect))
                         (js/parseFloat (.-paddingTop style)))]
     [x (- y 4)]
     #_(if target-fixed?
         [x (- y 4)]
         (if-let [parent (find-non-table-offset-parent element)]
           [x (- y 4)]
           ;; If no offset parent, the element is either fixed or
           ;; has display: none;
           [0 0])))))

(defn set-badge-color
  "Sets the color of the `badge` to the color of the `target`."
  [target badge]
  (let [color (.. js/window (getComputedStyle target) -color)]
    (set! (.. badge -firstElementChild -style -color) color)))

(defn position-ampie-badge [target badge]
  (let [target-fixed?   (is-fixed? target)
        badge-fixed?    (.. badge -classList (contains "ampie-badge-fixed"))
        ;; Set visibility of the badge depending on the visibility of the target
        target-visible? (and (some? (.-offsetParent target))
                          (is-visible? (.-offsetParent target))
                          (is-visible? target))
        badge-hidden?   (.. badge -classList (contains "ampie-badge-hidden"))]
    (cond (and (not target-visible?) (not badge-hidden?))
          (.. badge -classList (add "ampie-badge-hidden"))

          (and target-visible? badge-hidden?)
          (.. badge -classList (remove "ampie-badge-hidden")))
    (when target-visible?
      (cond (and (= (.-parent badge) (.-body js/document))
              target-fixed?
              (not badge-fixed?))
            (.. badge -classList (add "ampie-badge-fixed"))

            (and (or (not= (.-parent badge)
                       (.-body js/document))
                   (not target-fixed?))
              badge-fixed?)
            (.. badge -classList (remove "ampie-badge-fixed")))
      ;; Set position to (0,0), find the distance to the correct location,
      ;; update the position to the correct value.
      (set! (.. badge -style -left) (str "0px"))
      (set! (.. badge -style -top) (str "0px"))
      (let [[pos-x pos-y] (get-offsets badge target target-fixed?)]
        (set! (.. badge -style -left) (str pos-x "px"))
        (set! (.. badge -style -top) (str pos-y "px")))
      (set! (.. badge -style -zIndex) (get-z-index target)))))

(defn generate-tooltip [target-info]
  (let [tooltip-div (. js/document createElement "div")]
    (set! (.-className tooltip-div) "ampie-badge-tooltip")
    (doseq [source-tag [:history :hn :twitter :visits]
            :when      (source-tag target-info)
            :let       [n-entries (count (source-tag target-info))]
            :when      (pos? n-entries)
            :let       [mini-tag (.createElement js/document "div")
                        icon (.createElement js/document "span")
                        count-el (.createTextNode js/document (str n-entries))]]
      (set! (.-className mini-tag) "ampie-badge-mini-tag")
      (set! (.-className icon) (str "ampie-mini-tag-icon ampie-"
                                 (name source-tag) "-icon"))
      (.appendChild mini-tag icon)
      (.appendChild mini-tag count-el)
      (.appendChild tooltip-div mini-tag))
    tooltip-div))

(defn show-tooltip [badge tooltip]
  (let [badge-rect    (.getBoundingClientRect badge)
        window-width  (. js/window -innerWidth)
        window-height (. js/window -innerHeight)]
    (when (not (.-parentElement tooltip))
      (. js/document.body appendChild tooltip)
      ;; Position the tooltip at (0,0) and find how much we need to move it
      ;; after that.
      (set! (.. tooltip -style -left) "0px")
      (set! (.. tooltip -style -top) "0px")
      (let [tooltip-rect (.getBoundingClientRect tooltip)
            client-x     (if (< (- window-width (.-left badge-rect)) 300)
                           (- (.-left badge-rect) (.-width tooltip-rect) 2)
                           (+ (.-right badge-rect) 2))
            client-y     (if (< (- window-height (.-bottom badge-rect))
                               150)
                           (- (.-bottom badge-rect) (.-height tooltip-rect) 2)
                           (.-top badge-rect))
            fixed        (is-fixed? badge)]
        (if (and fixed
              (not (.. badge -classList (contains "ampie-badge-tooltip-fixed"))))
          (.. tooltip -classList (add "ampie-badge-tooltip-fixed"))
          (.. tooltip -classList (remove "ampie-badge-tooltip-fixed")))
        (set! (.. tooltip -style -left)
          (str (- client-x (when-not fixed (.-left tooltip-rect))) "px"))
        (set! (.. tooltip -style -top)
          (str (- client-y (when-not fixed (.-top tooltip-rect))) "px"))))))

(defn find-non-table-offset-parent [element]
  (loop [parent (.-offsetParent element)]
    (if (and parent (table-element? parent))
      (recur (.-offsetParent parent))
      (or parent (.-body js/document)))))

(defn get-target-url
  "Returns the non-normalized url of the `target` element. May not match
  (.-href target) when we can easily figure out the redirect, e.g. for twitter."
  [target]
  (let [href  (.-href target)
        title (.-title target)]
    (if (and (string/starts-with? href "https://t.co/")
          (string/starts-with? title "http"))
      title
      href)))

(defn add-ampie-badge [target target-id target-info on-badge-click]
  (let [badge-div  (. js/document createElement "div")
        badge-icon (. js/document createElement "div")
        tooltip    (generate-tooltip target-info)
        bold       (or (>= (count (:hn target-info)) 3)
                     (>= (count (:twitter target-info)) 5)
                     (>= (count (:visits target-info) 1)))]
    (set! (.-className badge-div)
      (str "ampie-badge" (when bold " ampie-badge-bold")))
    (.setAttribute badge-div "role" "button")
    (set! (.-onclick badge-div) #(on-badge-click (get-target-url target)))
    (set! (.-onclick tooltip) #(on-badge-click (get-target-url target)))
    (set! (.-className badge-icon) "ampie-badge-icon")
    (.appendChild badge-div badge-icon)
    (.setAttribute badge-div "ampie-badge-id" target-id)
    (let [mouse-out? (atom false)
          on-mouse-out
          (fn []
            (reset! mouse-out? true)
            (js/setTimeout #(when @mouse-out? (.remove tooltip)) 200))]
      (.addEventListener badge-div "mouseover"
        (fn []
          (reset! mouse-out? false)
          (show-tooltip badge-div tooltip)))
      (.addEventListener tooltip "mouseover" #(reset! mouse-out? false))
      (.addEventListener badge-div "mouseout" on-mouse-out)
      (.addEventListener tooltip "mouseout" on-mouse-out))
    (.appendChild (find-non-table-offset-parent target) badge-div)
    (position-ampie-badge target badge-div)
    (set-badge-color target badge-div)))

(defn show-too-many-badges-message []
  (let [shadow-holder   (.createElement js/document "div")
        shadow          (. shadow-holder (attachShadow #js {"mode" "open"}))
        shadow-style    (. js/document createElement "link")
        message-element (.createElement js/document "div")
        message-text    (.createElement js/document "div")
        buttons         (.createElement js/document "div")
        continue-button (.createElement js/document "button")
        close-button    (.createElement js/document "button")
        ch              (chan)
        on-continue     (fn [] (.remove shadow-holder) (go (>! ch true)))
        on-close        (fn [] (.remove shadow-holder) (go (>! ch false)))]
    (log/info "Showing too many badges message")
    (set! (.-rel shadow-style) "stylesheet")
    (set! (.-href shadow-style) (.. browser -runtime (getURL "assets/message.css")))
    (set! (.-className shadow-holder) "ampie-message-holder")
    (set! (.-className message-element) "message")
    (.appendChild shadow shadow-style)
    (.appendChild shadow message-element)
    (set! (.-className message-text) "text")
    (.appendChild message-text
      (.createTextNode js/document
        "Too many links, pausing loading of ampie badges."))
    (set! (.-className continue-button) "button continue")
    (.appendChild continue-button
      (.createTextNode js/document "Continue loading badges"))
    (set! (.-onclick continue-button) on-continue)
    (.appendChild close-button
      (.createTextNode js/document "Close"))
    (set! (.-onclick close-button) on-close)
    (set! (.-className close-button) "button close")
    (set! (.-className buttons) "buttons")
    (.appendChild buttons continue-button)
    (.appendChild buttons close-button)
    (.appendChild message-element message-text)
    (.appendChild message-element buttons)
    (.. js/document -body (appendChild shadow-holder))
    ch))

(defn process-child-links
  "Go through all the links that are in the `element`'s subtree and add badges
  to them."
  [element target-ids next-target-id on-badge-click]
  (let [page-links        (array-seq (.querySelectorAll element "a[href]"))
        unprocessed-links (filter #(nil? (.getAttribute % "processed-by-ampie"))
                            page-links)
        current-nurl      (url/normalize (.. js/document -location -href))]
    (doseq [link-element unprocessed-links]
      (.setAttribute link-element "processed-by-ampie" ""))
    ((fn process-links-in-chunks [chunks badges-added]
       (when (seq chunks)
         (let [chunk         (first chunks)
               urls-to-query (->> chunk
                               (map get-target-url)
                               (filter url/should-store-url?))]
           (send-urls-to-background
             urls-to-query
             (fn [url->seen-at]
               (let [updated-badges-added
                     (+ badges-added
                       (count
                         (for [target chunk
                               :let   [target-url (get-target-url target)
                                       target-seen-at (url->seen-at target-url)]
                               :when  (and target-seen-at
                                        (not= (url/normalize target-url)
                                          current-nurl))]
                           (let [target-id (dec (swap! next-target-id inc))]
                             (add-ampie-badge target target-id target-seen-at
                               on-badge-click)
                             (.setAttribute target
                               "processed-by-ampie" target-id)
                             (swap! target-ids conj target-id)))))]
                 (if (and (< badges-added 250)
                       (>= updated-badges-added 250))
                   (go (when true #_(<! (show-too-many-badges-message))
                             (process-links-in-chunks (rest chunks)
                               updated-badges-added)))
                   (process-links-in-chunks (rest chunks)
                     updated-badges-added))))))))
     (partition 100 100 nil unprocessed-links) 0)))

(defn update-badge
  "Function to be run when `target` changes.
  Repositions/hides/shows the `badge` element depending on the state
  of `target`."
  [target badge]
  (let [badge-parent  (.-offsetParent badge)
        target-parent (find-non-table-offset-parent target)]
    (when-not (= badge-parent target-parent)
      (.remove badge)
      (.appendChild target-parent badge))
    (position-ampie-badge target badge)
    (set-badge-color target badge)))

(defn screen-update
  "Iterate over all the badge ids in the `target-ids` atom
  and update them, deleting the entries from `target-ids`
  that do not exist in the document."
  [target-ids]
  (doseq [target-id @target-ids]
    (let [target (. js/document querySelector
                   (str "[processed-by-ampie=\"" target-id "\"]"))
          badge  (. js/document querySelector
                   (str "[ampie-badge-id=\"" target-id "\"]"))]
      (if (nil? target)
        (do (when badge (.remove badge))
            (swap! target-ids disj target-id))
        (update-badge target badge)))))

(defn start [on-badge-click]
  (let [target-ids           (atom #{})
        next-target-id       (atom 0)
        badge-redraw-timeout (atom nil)
        badge-redraw
        (fn []
          (when-not @badge-redraw-timeout
            (reset! badge-redraw-timeout
              (js/setTimeout
                (fn [] (reset! badge-redraw-timeout nil)
                  (screen-update target-ids))
                5000))))

        update-cancelled (atom false)
        update-page
        (fn update-page [update-cancelled]
          (when-not @update-cancelled
            (when-not (.-hidden js/document)
              (process-child-links js/document.body target-ids next-target-id
                on-badge-click)
              (badge-redraw))
            (js/setTimeout #(update-page update-cancelled) 1000)))

        resize-event-timeout (atom nil)
        on-resize
        (fn []
          (js/clearTimeout @resize-event-timeout)
          (reset! resize-event-timeout
            (js/setTimeout #(screen-update target-ids) 25)))]
    (. js/window addEventListener "resize" on-resize)
    (process-child-links js/document.body target-ids next-target-id on-badge-click)
    (js/setTimeout #(update-page update-cancelled) 2000)
    {:next-target-id   next-target-id
     :target-ids       target-ids
     :update-cancelled update-cancelled
     :on-resize        on-resize}))

(defn stop [{:keys [next-target-id target-ids update-cancelled on-resize]}]
  (reset! update-cancelled true)
  (.. js/document (querySelectorAll ".ampie-badge") (forEach #(.remove %)))
  (.. js/document (querySelectorAll "[processed-by-ampie]")
    (forEach #(.removeAttribute % "processed-by-ampie")))
  (.removeEventListener js/window "resize" on-resize))
