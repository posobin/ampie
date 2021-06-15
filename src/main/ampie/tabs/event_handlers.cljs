(ns ampie.tabs.event-handlers
  (:require [ampie.tabs.core :as tabs]
            [ampie.visits :as visits]
            [ampie.visits.db :as visits.db]
            [ampie.tabs.core :as tabs]
            [ampie.url :as url]
            [ampie.interop :as i]
            [ampie.logging]
            [taoensso.timbre :as log]
            [clojure.string :as string]
            ["webextension-polyfill" :as browser]))

(defn parent-event? [^js evt] (= (.-parentFrameId evt) -1))
(defn preprocess-navigation-evt [navigation-evt]
  (let [evt (i/js->clj navigation-evt)]
    (assoc evt :normalized-url (url/normalize (:url evt)))))


;; The main event handlers will dispatch to these functions

(defn opened-link-same-tab [evt]
  (letfn [(maybe-add-visit-title [visit stored-title]
            (if (= (:url stored-title) (:url visit))
              (assoc visit :title (:title stored-title))
              visit))]
    (let [tab-id        (:tab-id evt)
          prev-tab-info (@@tabs/open-tabs tab-id)
          parent-hash   (:visit-hash prev-tab-info)
          origin-hash   (:origin-hash prev-tab-info)
          visit         (-> (visits/evt->visit evt parent-hash origin-hash)
                          (maybe-add-visit-title (:stored-title prev-tab-info)))
          visit-hash    (:visit-hash visit)
          origin-hash   (:origin visit)
          tab-info      (-> (tabs/add-new-visit-to-tab
                              (or prev-tab-info
                                (tabs/generate-new-tab visit-hash origin-hash))
                              visit-hash)
                          (dissoc :stored-title))]
      (visits.db/add-new-visit! visit-hash visit)
      (tabs/update-tab! tab-id tab-info))))

(defn opened-link-new-tab [evt]
  (let [tab-id          (:tab-id evt)
        parent-tab-id   (:source-tab-id evt)
        parent-tab-info (@@tabs/open-tabs parent-tab-id)
        parent-hash     (:visit-hash parent-tab-info)
        origin-hash     (:origin-hash parent-tab-info)
        visit           (visits/evt->visit evt parent-hash origin-hash)
        visit-hash      (:visit-hash visit)
        origin-hash     (:origin visit)
        tab-info        (tabs/generate-new-tab visit-hash origin-hash)]
    (visits.db/add-new-visit! visit-hash visit)
    (tabs/open-tab! tab-id tab-info)))

(defn closed-tab [tab-id]
  (tabs/close-tab! tab-id))

;; We need this event in addition to the check-active-tab interval because
;; interval may not be called when the computer is put to sleep, for example.
;; But this event will be called.
(defn window-focus-changed [window]
  (when (= window (.. browser -windows -WINDOW_ID_NONE))
    (tabs/no-tab-in-focus!)))

;; TODO check if the URL was modified from the previous one
;;      and set the previous URL as the parent then
(defn opened-from-typed-url [evt]
  (let [tab-id            (:tab-id evt)
        in-existing-tab?  (contains? @@tabs/open-tabs tab-id)
        existing-tab-info (@@tabs/open-tabs tab-id)
        parent-hash       (:visit-hash existing-tab-info)
        origin-hash       (:origin-hash existing-tab-info)
        visit             (visits/evt->visit evt parent-hash origin-hash)
        visit-hash        (:visit-hash visit)
        origin-hash       (:origin visit)
        tab-info          (if in-existing-tab?
                            (tabs/add-new-visit-to-tab
                              existing-tab-info visit-hash)
                            (tabs/generate-new-tab visit-hash
                              origin-hash))]
    (visits.db/add-new-visit! visit-hash visit)
    (if in-existing-tab?
      (tabs/update-tab! tab-id tab-info)
      (tabs/open-tab! tab-id tab-info))))

;; If the tab was restored, check if the last closed tab we have has the same
;; URL. If yes, pop it from the closed tabs and onto the open tabs list.
;; If no, create a new visit for this URL, without a parent visit, and a new tab.
(defn restored-tab [evt]
  (let [tab-id (:tab-id evt)
        url    (:url evt)]
    (.then
      (tabs/maybe-restore-last-tab tab-id url :n 3)
      (fn [found-tab?]
        (when found-tab?
          (log/info "Restored tab" tab-id "with url" url))
        (when (not found-tab?)
          (let [visit       (visits/evt->visit evt)
                visit-hash  (:visit-hash visit)
                origin-hash (:origin visit)
                tab-info    (tabs/generate-new-tab visit-hash origin-hash)]
            (visits.db/add-new-visit! visit-hash visit)
            (tabs/open-tab! tab-id tab-info)))))))

;; If user went back of forward in history inside one tab,
;; find the URL they went to in our stored visits history for this tab
;; (choose the closest one to the currently open URL if there are several),
;; and update our history of the tab accordingly.
;; If the URL is not found in our version of the history,
;; move the whole history to history-back, generate a new visit, and add it
;; to the tab.
(defn went-back-or-fwd-in-tab [evt]
  (let [tab-id       (:tab-id evt)
        url          (:url evt)
        tab-info     (@@tabs/open-tabs tab-id)
        history-back (:history-back tab-info)
        history-fwd  (:history-fwd tab-info)]
    (.then
      (js/Promise.all
        #js [(visits.db/get-first-visit-with-url history-back url)
             (visits.db/get-first-visit-with-url history-fwd url)])
      (fn [js-result]
        (let [back-hash (aget js-result 0)
              fwd-hash  (aget js-result 1)

              [back-1 back-2] (split-with #(not= % back-hash) history-back)
              back-count      (when (seq back-2) (count back-1))

              [fwd-1 fwd-2] (split-with #(not= % fwd-hash) history-fwd)
              fwd-count     (when (seq fwd-2) (count fwd-1))

              back-better? (and back-count
                             (or (nil? fwd-count)
                               (< back-count fwd-count)))
              fwd-better?  (and (not back-better?) fwd-count)
              not-found?   (and (not back-better?) (not fwd-better?))

              ;; Visit for the case when the url was not found in either
              ;; back or fwd history.
              visit        (visits/evt->visit evt
                             (:visit-hash tab-info)
                             (:origin-hash tab-info))
              new-back     (condp = [(boolean back-better?) (boolean fwd-better?)]
                             [true false] back-2
                             [false true]
                             (conj (concat (reverse fwd-1) history-back)
                               (first fwd-2))
                             [false false]
                             (conj (concat (reverse history-fwd) history-back)
                               (:visit-hash visit)))
              new-fwd      (condp = [(boolean back-better?) (boolean fwd-better?)]
                             [true false]  (concat (reverse back-1) history-fwd)
                             [false true]  (rest fwd-2)
                             [false false] ())
              visit-hash   (first new-back)
              new-tab-info (tabs/generate-new-tab visit-hash
                             (:origin-hash tab-info)
                             new-back
                             new-fwd)]
          (when not-found?
            (visits.db/add-new-visit! visit-hash visit))
          (tabs/update-tab! tab-id new-tab-info))))))

;; Not doing anything right now if it is just a simple reload.
(defn reloaded-tab [_])

(defn tab-updated [tab-id change-info tab-info]
  (let [{title :title new-url :url} (i/js->clj change-info)
        {url :url}                  (i/js->clj tab-info)
        normalized-url              (url/normalize url)]
    ;; Only fire when the title was updated
    #_(when title
        (log/info "Title of" url "updated to" title)
        (tabs/update-tab-title tab-id title url))

    (when new-url
      (.catch
        (.. browser -tabs (sendMessage tab-id (clj->js {:type :url-updated
                                                        :url  new-url})))
        (fn [error]
          #_(log/debug "Couldn't send a url-updated"
              new-url " message to tab" tab-id))))))

(defn redirect-tab [{:keys [url tab-id]} {:keys [visit-hash]}]
  (log/info "Tab redirect" tab-id url visit-hash)
  (tabs/update-visit-url! tab-id visit-hash url))

;; Listeners for the webNavigation events that pre-process events and
;; dispatch to the functions above

(defn tab-removed [tab-id evt-info]
  (log/info "onTabRemoved" tab-id evt-info)
  (closed-tab tab-id))

(defn created-navigation-target [evt]
  (let [evt (preprocess-navigation-evt evt)]
    (opened-link-new-tab evt)))

(defn committed [evt]
  (when (parent-event? evt)
    (let [{:keys
           [url normalized-url tab-id transition-type transition-qualifiers]
           :as evt}             (preprocess-navigation-evt evt)
          current-visit-promise (-> tab-id
                                  (@@tabs/open-tabs)
                                  :visit-hash
                                  (visits.db/get-visit-by-hash :keep-visit-hash true))]
      (log/info "Got event:" evt)
      (log/info "Transition qualifiers:" transition-qualifiers)
      (-> current-visit-promise
        (.then
          (fn [current-visit]
            (log/info (:url current-visit))
            (cond
              ;; Ignore the new tab page and all extension pages
              (or (= url "chrome-search://local-ntp/local-ntp.html")
                (= url "chrome://newtab/")
                (= url "chrome://new-tab-page/")
                (string/starts-with? url "chrome-extension://")
                (string/starts-with? url "moz-extension://"))
              :ignore

              (= (:url current-visit) url) ; Nothing changed, treat as reload
              (do
                (log/info "URL is the same, doing nothing")
                (reloaded-tab evt))

              (some #(= "forward_back" %) transition-qualifiers)
              (went-back-or-fwd-in-tab evt)

              (and current-visit
                (or (= transition-type "client_redirect")
                  (and (= transition-type "link")
                    ;; Less than a second has passed since opening
                    (<= (- (-> (js/Date.) (.getTime))
                          (:first-opened current-visit))
                      2000))))
              (redirect-tab evt current-visit)

              (or (= transition-type "link")
                (= transition-type "client_redirect"))
              (opened-link-same-tab evt)

              (= transition-type "typed")
              (opened-from-typed-url evt)

              (or (= transition-type "generated")
                (= transition-type "auto_bookmark"))
              (opened-from-typed-url evt)

              (and (= transition-type "reload")
                (not (contains? @@tabs/open-tabs tab-id)))
              (restored-tab evt)

              (= transition-type "reload")
              (reloaded-tab evt))))
        (.catch
          (fn [error]
            (log/error "Error while processing event" evt error)))))))

(defn history-state-updated [evt]
  (js/console.log "History state updated" evt)
  (committed evt))
