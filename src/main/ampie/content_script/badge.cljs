(ns ampie.content-script.badge
  (:require ["webextension-polyfill" :as browser]
            [ampie.interop :as i]
            [ampie.url :as url]
            [taoensso.timbre :as log]
            [mount.core :refer [defstate]]
            [clojure.string :as string]
            [ampie.links :as links]
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

(defn generate-tooltip [{:keys [twitter visits] :as target-info}]
  (let [tooltip-div (. js/document createElement "div")]
    (set! (.-className tooltip-div) "ampie-badge-tooltip")
    (doseq [[source-tag count-fn]
            [[:history count] [:hn links/count-hn]
             [:twitter links/count-tweets] [:visits links/count-visits]]
            :when (source-tag target-info)
            :let  [n-entries (count-fn (source-tag target-info))]
            :when (pos? n-entries)
            :let  [mini-tag (.createElement js/document "div")
                   icon (.createElement js/document "span")
                   count-el (.createTextNode js/document (str n-entries))]]
      (set! (.-className mini-tag) "ampie-badge-mini-tag")
      (set! (.-className icon) (str "ampie-mini-tag-icon ampie-"
                                 (name source-tag) "-icon"))
      (.appendChild mini-tag icon)
      (.appendChild mini-tag count-el)
      (.appendChild tooltip-div mini-tag))
    (let [who-shared
          (->> (concat visits twitter)
            (map #(or (-> % second :v-username)
                    (-> % second :t-author-name)))
            (filter identity)
            frequencies
            (map #(str (key %) (when (> (val %) 1) (str "×" (val %))))))]
      (when (seq who-shared)
        (let [batch   (cond (= (count who-shared) 6)
                            who-shared
                            :else
                            (take 5 who-shared))
              text-line
              (str (string/join " " batch)
                (cond (> (count who-shared) (count batch))
                      (str " + " (- (count who-shared) (count batch))
                        " more")))
              el      (.createElement js/document "div")
              el-text (.createTextNode js/document text-line)]
          (.. el -classList (add "ampie-who-shared"))
          (.appendChild el el-text)
          (.appendChild tooltip-div el))))
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


(defstate existing-badges :start (atom {}))
(defstate visible-badges :start (atom #{}))

(defstate seen-badges-ids :start (atom #{}))
(def intersection-observer
  (js/IntersectionObserver.
    (fn [^js evts]
      (doseq [evt  (array-seq evts)
              :let [element (.-target evt)
                    intersection-ratio (.-intersectionRatio evt)
                    badge-id (js/parseInt (.getAttribute element "ampie-badge-id"))
                    already-seen (contains? @@seen-badges-ids badge-id)]]
        (if (< intersection-ratio 1)
          (swap! @visible-badges disj badge-id)
          (swap! @visible-badges conj badge-id))
        (when (and (> intersection-ratio 0)
                (not already-seen))
          (let [badge-target
                (. js/document querySelector
                  (str "[processed-by-ampie=\"" badge-id "\"]"))
                target-url (get-target-url badge-target)]
            (swap! @seen-badges-ids conj badge-id)
            (.. browser -runtime
              (sendMessage (clj->js {:type :inc-badge-sightings
                                     :url  target-url})))))))
    #js {:threshold 1.0}))

(defstate on-badge-remove :start (atom {}))
(defn add-ampie-badge [^js target target-id target-info on-badge-click]
  (let [badge-div  (. js/document createElement "span")
        badge-icon (. js/document createElement "span")
        tooltip    (generate-tooltip target-info)
        bold       (or (>= (count (:hn target-info)) 3)
                     (>= (count (:twitter target-info)) 5)
                     (>= (count (:visits target-info)) 1))]
    (.observe intersection-observer badge-div)
    (set! (.-className badge-div)
      (str "ampie-badge" (when bold " ampie-badge-bold")))
    (.setAttribute badge-div "role" "button")
    (.addEventListener badge-div
      "click"
      (fn [e]
        (.preventDefault e)
        (on-badge-click (get-target-url target))))
    (set! (.-onclick tooltip) #(on-badge-click (get-target-url target)))
    (set! (.-className badge-icon) "ampie-badge-icon")
    (.appendChild badge-div badge-icon)
    (.setAttribute badge-div "ampie-badge-id" target-id)
    (let [mouse-out? (atom true)
          on-mouse-over
          (fn []
            (reset! mouse-out? false)
            (show-tooltip badge-icon tooltip))
          on-mouse-out
          (fn []
            (reset! mouse-out? true)
            (js/setTimeout #(when @mouse-out? (.remove tooltip)) 200))]
      (.addEventListener badge-div "mouseover" on-mouse-over)
      (.addEventListener target "mouseover" on-mouse-over)
      (.addEventListener tooltip "mouseover" #(reset! mouse-out? false))
      (doseq [el [badge-div target tooltip]]
        (.addEventListener el "mouseout" on-mouse-out))
      (swap! @existing-badges assoc target-id
        {:show #(show-tooltip badge-icon tooltip)
         :hide #(when @mouse-out? (.remove tooltip))})
      (swap! @on-badge-remove assoc target-id
        (fn []
          (swap! @existing-badges dissoc target-id)
          (.removeEventListener target "mouseover" on-mouse-over)
          (.removeEventListener target "mouseout" on-mouse-out))))
    (.appendChild target badge-div)))

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
      (when (or (nil? target) (nil? badge))
        (do (when badge (.remove badge))
            (when target (.removeAttribute target "processed-by-ampie"))
            (when-let [on-remove (@@on-badge-remove target-id)]
              (on-remove)
              (swap! @on-badge-remove dissoc target-id))
            (swap! target-ids disj target-id))))))

(defn on-alt-down [^js evt]
  (when (= (.-key evt) "Alt")
    (doseq [badge-id @@visible-badges
            :let     [f (:show (@@existing-badges badge-id))]
            :when    f]
      (f))))

(defn on-alt-up [^js evt]
  (when (= (.-key evt) "Alt")
    (doseq [badge-id @@visible-badges
            :let     [f (:hide (@@existing-badges badge-id))]
            :when    f]
      (f))))

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
    (. js/document addEventListener "keydown" on-alt-down)
    (. js/document addEventListener "keyup" on-alt-up)
    (. js/window addEventListener "resize" on-resize)
    (process-child-links js/document.body target-ids next-target-id on-badge-click)
    (js/setTimeout #(update-page update-cancelled) 2000)
    {:next-target-id   next-target-id
     :target-ids       target-ids
     :update-cancelled update-cancelled
     :on-resize        on-resize}))

(defn stop [{:keys [next-target-id target-ids update-cancelled on-resize]}]
  (. js/document removeEventListener "keydown" on-alt-down)
  (. js/document removeEventListener "keyup" on-alt-up)
  (reset! update-cancelled true)
  (doseq [[badge-id on-remove] @@on-badge-remove] (on-remove))
  (.. js/document (querySelectorAll ".ampie-badge") (forEach #(.remove %)))
  (.. js/document (querySelectorAll "[processed-by-ampie]")
    (forEach #(.removeAttribute % "processed-by-ampie")))
  (.removeEventListener js/window "resize" on-resize))
