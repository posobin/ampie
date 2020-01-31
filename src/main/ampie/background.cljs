(ns ampie.background)

(def open-tabs (atom {}))
(def closed-tabs (atom ()))
(def visits (atom {}))
(def visited-urls (atom {}))

(defn parent-event? [evt] (= ((js->clj evt) "parentFrameId") -1))
(defn clean-up-url [url]
  (clojure.string/replace url #"^https?://(www.)?|#.*$" ""))
(defn preprocess-navigation-evt [navigation-evt]
  (-> navigation-evt
      (js->clj :keywordize-keys true)
      (update :url clean-up-url)))
(defn select-nav-keys [tab-info]
  (select-keys tab-info [:url :transitionQualifiers :transitionType :timeStamp]))


;; Functions for managing visits and tabs

(defn generate-visit-hash [visit]
  (let [{time-stamp :first-opened
         url        :url
         parent     :parent} visit]
    (hash [time-stamp url parent])))

(defn add-new-visit! [visit-hash visit]
  (when (:parent visit)
    (swap! visits update-in [(:parent visit) :children]
           #(conj % visit-hash)))
  (swap! visits assoc visit-hash visit)
  (swap! visited-urls update (:url visit)
         (fn [visits] (conj (or visits []) visit-hash))))

(defn open-tab! [tab-id tab-info]
  (swap! open-tabs assoc tab-id tab-info))

(defn update-tab! [tab-id tab-info]
  (swap! open-tabs assoc tab-id tab-info))

(defn close-tab! [tab-id]
  (let [tab-info (@open-tabs tab-id)]
    (when tab-info
      (swap! open-tabs dissoc tab-id)
      (swap! closed-tabs conj tab-info))))

(defn reopen-last-tab! [tab-id]
  (let [tab-info (first @closed-tabs)]
    (open-tab! tab-id tab-info)
    (swap! closed-tabs rest)))

(defn evt->visit
  ([evt] (evt->visit evt nil))
  ([evt parent-hash]
   {:url          (:url evt)
    :first-opened (:timeStamp evt)
    :time-spent   0
    :children     []
    :parent       parent-hash}))

(defn generate-new-tab
  ([visit-hash] (generate-new-tab visit-hash (list visit-hash) ()))
  ([visit-hash history-back history-fwd]
   {:visit-hash   visit-hash
    :history-back history-back
    :history-fwd  history-fwd}))


;; The main event handlers will dispatch to these functions

(defn add-new-visit-to-tab [tab-info visit-hash]
  (let [history (:history-back tab-info)
        new-history (conj history visit-hash)]
    {:visit-hash   visit-hash
     :history-back new-history
     :history-fwd  ()}))

(defn opened-link-same-tab [evt]
  (let [tab-id (:tabId evt)
        prev-tab-info (@open-tabs tab-id)
        parent-hash (:visit-hash prev-tab-info)
        visit (evt->visit evt parent-hash)
        visit-hash (generate-visit-hash visit)
        tab-info (add-new-visit-to-tab prev-tab-info visit-hash)]
    (add-new-visit! visit-hash visit)
    (update-tab! tab-id tab-info)))

(defn opened-link-new-tab [evt]
  (let [tab-id (:tabId evt)
        parent-tab-id (:sourceTabId evt)
        parent-tab-info (@open-tabs parent-tab-id)
        parent-hash (:visit-hash parent-tab-info)
        visit (evt->visit evt parent-hash)
        visit-hash (generate-visit-hash visit)
        tab-info (generate-new-tab visit-hash)]
    (add-new-visit! visit-hash visit)
    (open-tab! tab-id tab-info)))

(defn closed-tab [tab-id]
  (close-tab! tab-id))

;; TODO check if the URL was modified from the previous one
;;      and set the previous URL as the parent then
(defn opened-from-typed-url [evt]
  (let [tab-id (:tabId evt)
        in-existing-tab? (contains? @open-tabs tab-id)
        existing-tab-info (@open-tabs tab-id)
        parent-hash (:visit-hash existing-tab-info)
        visit (evt->visit evt parent-hash)
        visit-hash (generate-visit-hash visit)
        tab-info (if in-existing-tab?
                   (add-new-visit-to-tab existing-tab-info visit-hash)
                   (generate-new-tab visit-hash))]
    (add-new-visit! visit-hash visit)
    (if in-existing-tab?
      (update-tab! tab-id tab-info)
      (open-tab! tab-id tab-info))))

;; If the tab was restored, check if the last closed tab we have has the same
;; URL. If yes, pop it from the closed tabs and onto the open tabs list.
;; If no, create a new visit for this URL, without a parent visit, and a new tab.
(defn restored-tab [evt]
  (let [tab-id (:tabId evt)
        url (:url evt)
        last-closed-tab (first @closed-tabs)
        matches? (= url (get-in visits [(:visit-hash last-closed-tab)
                                        :url]))]
    (if matches?
      (reopen-last-tab! tab-id)
      (let [visit (evt->visit evt)
            visit-hash (generate-visit-hash visit)
            tab-info (generate-new-tab visit-hash)]
        (add-new-visit! visit-hash visit)
        (open-tab! tab-id tab-info)))))

;; If user went back of forward in history inside one tab,
;; find the URL they went to in our stored visits history for this tab
;; (choose the closest one to the currently open URL if there are several),
;; and update our history of the tab accordingly.
;; If the URL is not found in our version of the history,
;; move the whole history to history-back, generate a new visit, and add it
;; to the tab.
(defn went-back-or-fwd-in-tab [evt]
  (let [tab-id (:tabId evt)
        url (:url evt)
        tab-info (@open-tabs tab-id)
        history-back (:history-back tab-info)
        history-fwd (:history-fwd tab-info)

        [back-1 back-2]
        (split-with #(not= url (get-in @visits [% :url])) history-back)
        back-count (when (seq back-2) (count back-1))

        [fwd-1 fwd-2]
        (split-with #(not= url (get-in @visits [% :url])) history-fwd)
        fwd-count (when (seq fwd-2) (count fwd-1))

        back-better? (and back-count
                          (or (nil? fwd-count)
                              (< back-count fwd-count)))
        fwd-better? (and (not back-better?) fwd-count)
        not-found? (and (not back-better?) (not fwd-better?))

        visit (evt->visit evt)
        new-back (condp = [(boolean back-better?) (boolean fwd-better?)]
                   [true false] back-2
                   [false true]
                   (conj (concat (reverse fwd-1) history-back)
                         (first fwd-2))
                   [false false]
                   (conj (concat (reverse history-fwd) history-back)
                         (generate-visit-hash visit)))
        new-fwd (condp = [(boolean back-better?) (boolean fwd-better?)]
                  [true false] (concat (reverse back-1) history-fwd)
                  [false true] (rest fwd-2)
                  [false false] ())
        visit-hash (first new-back)
        new-tab-info (generate-new-tab visit-hash
                                       new-back
                                       new-fwd)]
    (println not-found? back-better? fwd-better? back-count fwd-count)
    (when not-found?
      (add-new-visit! visit-hash visit))
    (update-tab! tab-id new-tab-info)))

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
    (let [evt (preprocess-navigation-evt evt)
          url (:url evt)
          tab-id (:tabId evt)
          current-visit (-> tab-id
                            (@open-tabs)
                            :visit-hash
                            (@visits))
          transition-type (:transitionType evt)
          transition-qualifiers (:transitionQualifiers evt)]
      (println "Got event:" evt)
      (println "Transition qualifiers:" transition-qualifiers)
      (cond
        (= (:url current-visit) url)                        ; Nothing changed, treat as reload
        (do
          (println "URL is the same, doing nothing")
          (reloaded-tab evt))

        (some #(= "forward_back" %) transition-qualifiers)
        (went-back-or-fwd-in-tab evt)

        (= transition-type "link")
        (opened-link-same-tab evt)

        (= transition-type "typed")
        (opened-from-typed-url evt)

        (= transition-type "generated")
        (opened-from-typed-url evt)

        (and (= transition-type "reload")
             (not (contains? @open-tabs tab-id)))
        (restored-tab evt)

        (and (= transition-type "reload"))
        (reloaded-tab evt)))
    (println "visits:" @visits)
    (println "tabs:" @open-tabs)))

(defn on-history-state-updated [evt]
  (on-committed evt))

(defn ^:dev/before-load remove-listeners []
  (println "Removing listeners")

  (.. js/chrome -tabs -onRemoved
      (removeListener on-tab-removed))
  (.. js/chrome -webNavigation -onHistoryStateUpdated
      (removeListener on-history-state-updated))
  (.. js/chrome -webNavigation -onCommitted
      (removeListener on-committed))
  (.. js/chrome -webNavigation -onCreatedNavigationTarget
      (removeListener on-created-navigation-target)))

;; TODO initiate open-tabs at the load
(defn ^:dev/after-load init []
  (println "Hello from the background world!")

  (.. js/chrome -tabs -onRemoved
      (addListener on-tab-removed))
  (.. js/chrome -webNavigation -onHistoryStateUpdated
      (addListener on-history-state-updated))
  (.. js/chrome -webNavigation -onCommitted
      (addListener on-committed))
  (.. js/chrome -webNavigation -onCreatedNavigationTarget
      (addListener on-created-navigation-target)))
