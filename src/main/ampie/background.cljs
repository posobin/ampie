(ns ampie.background
  (:require [ampie.history :as history]))

(defonce active-tab-interval-id (atom nil))

(defn parent-event? [evt] (= ((js->clj evt) "parentFrameId") -1))
;; TODO: figure out how to clean urls better and what to do with the real url
;; (whether to save it too or not)
(defn clean-up-url [url]
  (clojure.string/replace url #"^https?://(www.)?|#.*$" ""))
(defn preprocess-navigation-evt [navigation-evt]
  (-> navigation-evt
      (js->clj :keywordize-keys true)
      (update :url clean-up-url)))


;; The main event handlers will dispatch to these functions

(defn opened-link-same-tab [evt]
  (let [tab-id        (:tabId evt)
        prev-tab-info (@history/open-tabs tab-id)
        parent-hash   (:visit-hash prev-tab-info)
        visit         (history/evt->visit evt parent-hash)
        visit-hash    (history/generate-visit-hash visit)
        tab-info      (history/add-new-visit-to-tab prev-tab-info visit-hash)]
    (history/add-new-visit! visit-hash visit)
    (history/update-tab! tab-id tab-info)))

(defn opened-link-new-tab [evt]
  (let [tab-id          (:tabId evt)
        parent-tab-id   (:sourceTabId evt)
        parent-tab-info (@history/open-tabs parent-tab-id)
        parent-hash     (:visit-hash parent-tab-info)
        visit           (history/evt->visit evt parent-hash)
        visit-hash      (history/generate-visit-hash visit)
        tab-info        (history/generate-new-tab visit-hash)]
    (history/add-new-visit! visit-hash visit)
    (history/open-tab! tab-id tab-info)))

(defn closed-tab [tab-id]
  (history/close-tab! tab-id))

;; TODO check if the URL was modified from the previous one
;;      and set the previous URL as the parent then
(defn opened-from-typed-url [evt]
  (let [tab-id            (:tabId evt)
        in-existing-tab?  (contains? @history/open-tabs tab-id)
        existing-tab-info (@history/open-tabs tab-id)
        parent-hash       (:visit-hash existing-tab-info)
        visit             (history/evt->visit evt parent-hash)
        visit-hash        (history/generate-visit-hash visit)
        tab-info          (if in-existing-tab?
                            (history/add-new-visit-to-tab
                              existing-tab-info visit-hash)
                            (history/generate-new-tab visit-hash))]
    (history/add-new-visit! visit-hash visit)
    (if in-existing-tab?
      (history/update-tab! tab-id tab-info)
      (history/open-tab! tab-id tab-info))))

;; If the tab was restored, check if the last closed tab we have has the same
;; URL. If yes, pop it from the closed tabs and onto the open tabs list.
;; If no, create a new visit for this URL, without a parent visit, and a new tab.
(defn restored-tab [evt]
  (let [tab-id (:tabId evt)
        url    (:url evt)]
    (.then
      (history/maybe-restore-last-tab tab-id url :n 3)
      (fn [found-tab?]
        (when found-tab?
          (println "Restored tab" tab-id "with url" url))
        (when (not found-tab?)
          (let [visit      (history/evt->visit evt)
                visit-hash (history/generate-visit-hash visit)
                tab-info   (history/generate-new-tab visit-hash)]
            (history/add-new-visit! visit-hash visit)
            (history/open-tab! tab-id tab-info)))))))

;; If user went back of forward in history inside one tab,
;; find the URL they went to in our stored visits history for this tab
;; (choose the closest one to the currently open URL if there are several),
;; and update our history of the tab accordingly.
;; If the URL is not found in our version of the history,
;; move the whole history to history-back, generate a new visit, and add it
;; to the tab.
(defn went-back-or-fwd-in-tab [evt]
  (let [tab-id       (:tabId evt)
        url          (:url evt)
        tab-info     (@history/open-tabs tab-id)
        history-back (:history-back tab-info)
        history-fwd  (:history-fwd tab-info)]
    (.then
      (js/Promise.all
        #js [(history/get-first-visit-with-url history-back url)
             (history/get-first-visit-with-url history-fwd url)])
      (fn [js-result]
        (let [back-hash    (aget js-result 0)
              fwd-hash     (aget js-result 1)

              [back-1 back-2]
              (split-with #(not= % back-hash) history-back)
              back-count   (when (seq back-2) (count back-1))

              [fwd-1 fwd-2]
              (split-with #(not= % fwd-hash) history-fwd)
              fwd-count    (when (seq fwd-2) (count fwd-1))

              back-better? (and back-count
                                (or (nil? fwd-count)
                                    (< back-count fwd-count)))
              fwd-better?  (and (not back-better?) fwd-count)
              not-found?   (and (not back-better?) (not fwd-better?))

              visit        (history/evt->visit evt)
              new-back     (condp = [(boolean back-better?) (boolean fwd-better?)]
                             [true false] back-2
                             [false true]
                             (conj (concat (reverse fwd-1) history-back)
                                   (first fwd-2))
                             [false false]
                             (conj (concat (reverse history-fwd) history-back)
                                   (history/generate-visit-hash visit)))
              new-fwd      (condp = [(boolean back-better?) (boolean fwd-better?)]
                             [true false] (concat (reverse back-1) history-fwd)
                             [false true] (rest fwd-2)
                             [false false] ())
              visit-hash   (first new-back)
              new-tab-info (history/generate-new-tab visit-hash
                                                     new-back
                                                     new-fwd)]
          (when not-found?
            (history/add-new-visit! visit-hash visit))
          (history/update-tab! tab-id new-tab-info))))))

;; Not doing anything right now if it is just a simple reload.
(defn reloaded-tab [_])


;; Listeners for the webNavigation events that pre-process events and
;; dispatch to the functions above

(defn on-tab-removed [tab-id evt-info]
  (println "onTabRemoved" tab-id evt-info)
  (closed-tab tab-id))

(defn on-created-navigation-target [evt]
  (let [evt (preprocess-navigation-evt evt)]
    (println "onCreatedNavigationTarget" evt)
    (opened-link-new-tab evt)))

(defn on-committed [evt]
  (when (parent-event? evt)
    (let [evt                   (preprocess-navigation-evt evt)
          url                   (:url evt)
          tab-id                (:tabId evt)
          transition-type       (:transitionType evt)
          transition-qualifiers (:transitionQualifiers evt)
          current-visit-promise (-> tab-id
                                    (@history/open-tabs)
                                    :visit-hash
                                    (history/get-visit-by-hash))]
      (println "Got event:" evt)
      (println "Transition qualifiers:" transition-qualifiers)
      (.then
        current-visit-promise
        (fn [current-visit]
          (cond
            (= (:url current-visit) url)                    ; Nothing changed, treat as reload
            (do
              (println "URL is the same, doing nothing")
              (reloaded-tab evt))

            (some #(= "forward_back" %) transition-qualifiers)
            (went-back-or-fwd-in-tab evt)

            (or (= transition-type "link")
                (= transition-type "client_redirect"))
            (opened-link-same-tab evt)

            (= transition-type "typed")
            (opened-from-typed-url evt)

            (or (= transition-type "generated")
                (= transition-type "auto_bookmark"))
            (opened-from-typed-url evt)

            (and (= transition-type "reload")
                 (not (contains? @history/open-tabs tab-id)))
            (restored-tab evt)

            (and (= transition-type "reload"))
            (reloaded-tab evt)))))
    (println "tabs:" @history/open-tabs)))

(defn on-history-state-updated [evt]
  (on-committed evt))

(defn on-message-get-past-visits-parents [{url :url} sender send-response]
  (-> (history/get-past-visits-to-the-url url 5)
      (.then
        (fn [past-visits]
          (->> (js->clj past-visits :keywordize-keys true)
               (map :parent)
               (filter some?)
               history/get-visits-info)))
      (.then
        (fn [past-visits-parents]
          (->
            (map #(select-keys % [:url :firstOpened]) past-visits-parents)
            clj->js
            send-response)))))

(defn on-message-send-links-on-page [{:keys [links page-url]}
                                     sender send-response]
  (let [page-url (clean-up-url page-url)
        links    (map clean-up-url links)
        sender   (js->clj sender :keywordize-keys true)
        tab-id   (-> sender :tab :id)]
    (history/add-seen-links tab-id page-url links)
    (.then
      (history/find-where-saw-urls links)
      #(send-response (clj->js %)))
    ;; Return true not to close the send-response channel.
    true))

(defn on-message-received [request sender send-response]
  (let [request      (js->clj request :keywordize-keys true)
        request-type (:type request)]
    (cond (= request-type "get-past-visits-parents")
          (on-message-get-past-visits-parents request sender send-response)

          (= request-type "send-links-on-page")
          (on-message-send-links-on-page request sender send-response)))
  ;; Need to return true to let browser know that we will call
  ;; send-response asynchronously.
  true)

;; We need this event in addition to the check-active-tab interval because
;; interval may not be called when the computer is put to sleep, for example.
;; But this event will be called.
(defn on-window-focus-changed [window]
  (when (= window (.. js/chrome -windows -WINDOW_ID_NONE))
    (history/no-tab-in-focus)))

(defn check-active-tab []
  (.. js/chrome -windows
      (getLastFocused
        #js {:populate true}
        (fn [window]
          (let [{tabs :tabs focused :focused :as window}
                (js->clj window :keywordize-keys true)
                {tab-id :id} (first (filter :active tabs))]
            (if focused
              (history/tab-in-focus tab-id)
              (history/no-tab-in-focus)))))))

(defn on-tab-updated [tab-id change-info tab-info]
  (let [{title :title} (js->clj change-info :keywordize-keys true)
        {dirty-url :url} (js->clj tab-info :keywordize-keys true)
        url (clean-up-url dirty-url)]
    ;; Only fire when the title was updated
    (when title
      (println "Title of" url "updated to" title)
      (history/update-tab-title tab-id title url))))

(defn process-already-open-tabs [tabs]
  (let [tabs (js->clj tabs :keywordize-keys true)]
    (doseq [{:keys [id url title] :as tab} tabs]
      (let [evt {:tabId     id
                 :url       (clean-up-url url)
                 :title     title
                 :timeStamp (.getTime (js/Date.))}]
        (if (contains? @history/open-tabs id)
          (went-back-or-fwd-in-tab evt)
          (restored-tab evt)))))
  (js/setTimeout (fn [] (println @history/open-tabs)) 1000))

(defn ^:dev/before-load remove-listeners []
  (println "Removing listeners")

  (.close history/db)


  (. js/window clearInterval @active-tab-interval-id)
  (reset! active-tab-interval-id nil)

  (.. js/chrome -tabs -onUpdated
      (removeListener on-tab-updated))
  (.. js/chrome -windows -onFocusChanged
      (removeListener on-window-focus-changed))
  (.. js/chrome -runtime -onMessage
      (removeListener on-message-received))
  (.. js/chrome -tabs -onRemoved
      (removeListener on-tab-removed))
  (.. js/chrome -webNavigation -onHistoryStateUpdated
      (removeListener on-history-state-updated))
  (.. js/chrome -webNavigation -onCommitted
      (removeListener on-committed))
  (.. js/chrome -webNavigation -onCreatedNavigationTarget
      (removeListener on-created-navigation-target)))


(defn ^:dev/after-load init []
  (println "Hello from the background world!")
  (history/init-db)
  (.open history/db)

  #_(.. js/chrome -tabs (create #js {:url "history.html"}))

  #_(.. js/chrome -tabs
      (query #js {} process-already-open-tabs))

  (when (some? @active-tab-interval-id)
    (. js/window clearInterval @active-tab-interval-id)
    (reset! active-tab-interval-id nil))

  (reset! active-tab-interval-id
          (. js/window setInterval
             check-active-tab
             1000))

  (.. js/chrome -tabs -onUpdated
      (addListener on-tab-updated))
  (.. js/chrome -windows -onFocusChanged
      (addListener on-window-focus-changed))
  (.. js/chrome -runtime -onMessage
      (addListener on-message-received))
  (.. js/chrome -tabs -onRemoved
      (addListener on-tab-removed))
  (.. js/chrome -webNavigation -onHistoryStateUpdated
      (addListener on-history-state-updated))
  (.. js/chrome -webNavigation -onCommitted
      (addListener on-committed))
  (.. js/chrome -webNavigation -onCreatedNavigationTarget
      (addListener on-created-navigation-target)))
