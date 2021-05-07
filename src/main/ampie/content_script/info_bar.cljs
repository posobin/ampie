(ns ampie.content-script.info-bar
  (:require ["webextension-polyfill" :as browser]
            ["react-shadow-dom-retarget-events" :as retargetEvents]
            [reagent.dom :as rdom]
            [reagent.core :as r]
            [ampie.url :as url]
            [ajax.core :refer [GET]]
            [clojure.string :as string]
            [ampie.components.basics :as b]
            [ampie.content-script.demo
             :refer [is-demo-url? get-current-url send-message-to-page]]
            [ampie.time]
            [ampie.content-script.amplify :as amplify]
            [ampie.links :as links]
            [ampie.utils]
            [ampie.content-script.info-bar.tweet :refer [tweet hydrate-tweets]]
            [mount.core :as mount :refer [defstate]]))

(defn tweets [tweets-info selected-normalized-url]
  [:div.tweets.pane
   [:div.header [:span.icon.twitter-icon] "Tweets"]
   (if (= tweets-info :loading)
     [:div.loading "Loading"]
     (for [{:keys [id_str] :as tweet-info} tweets-info]
       ^{:key id_str} [tweet tweet-info selected-normalized-url]))])

(defn hn-item-url [item-id]
  (str "https://hacker-news.firebaseio.com/v0/item/" item-id ".json"))

(defn load-hn-items [item-ids]
  (js/Promise.all
    (for [item-id item-ids]
      (js/Promise.
        (fn [resolve]
          (GET (hn-item-url item-id)
            {:response-format :json
             :keywords?       true
             :handler         #(resolve %)}))))))

(defn element->hiccup [^js el]
  (let [children-seq
        (doall
          (for [[idx child] (map-indexed vector (.-childNodes el))]
            (vary-meta
              (element->hiccup child)
              assoc :key idx)))]
    (vary-meta
      (case (.-nodeName el)
        "BODY"  children-seq
        "PRE"   [:pre (-> (.-childNodes el)
                        first
                        element->hiccup)]
        "P"     [:p children-seq]
        "A"     [:a (b/ahref-opts (.-href el)) (.-innerText el)]
        "I"     [:i children-seq]
        "CODE"  [:code (.-innerText el)]
        "#text" [:<> (.-textContent el)])
      assoc :text-length (count (or (.-innerText el)
                                  (.-textContent el))))))

(defn hn-comment [{:keys [by text time id kids]} depth]
  (let [parser          (js/DOMParser.)
        paragraphs      (filter (comp pos? :text-length meta)
                          (-> (.parseFromString parser text "text/html")
                            (.querySelector "body")
                            element->hiccup))
        p-lengths       (reductions + 0 (map (comp :text-length meta) paragraphs))
        p-with-length   (map vector paragraphs p-lengths)
        showing-all     (r/atom (<= (-> p-with-length last second) 280))
        loaded-kids     (r/atom [])
        children-hidden (r/atom false)]
    (fn [{:keys [by text time id kids]} depth]
      [:div.hn-comment
       [:div.text
        (doall
          (for [[p prior-length] p-with-length
                :when            (or (<= prior-length 280) @showing-all)]
            (with-meta p {:key prior-length})))]
       [:div.info
        (when-not @showing-all
          [:button.inline
           {:on-click #(reset! showing-all true)} "Full comment"])
        [:div.author by]
        [:a.date (b/ahref-opts (str "https://news.ycombinator.com/item?id=" id))
         (ampie.time/timestamp->date (* time 1000))]
        (when (seq kids)
          (if (not (seq @loaded-kids))
            ^{:key :load}
            [:button.inline
             {:on-click (fn []
                          (.then (load-hn-items kids)
                            #(reset! loaded-kids %)))}
             "Load " (count kids)
             (if (= (count kids) 1) " child" " children")]
            ^{:key :hide-or-show}
            [:button.inline {:on-click #(swap! children-hidden not)}
             (if @children-hidden "Show " "Hide ") "children"]))]
       (when (and (not @children-hidden) (seq @loaded-kids))
         [:div.children {:class (when (even? depth) "white")}
          (for [kid   @loaded-kids
                :when (seq (:text kid))]
            ^{:key (:id kid)}[hn-comment kid (inc depth)])])])))

(defn hn-story [{:keys [by title time score descendants id kids]
                 :as   story-info}]
  (let [kids-info (r/atom {:kids [] :loaded-ids #{}})
        load-more
        (fn [count]
          (-> (take count (remove (:loaded-ids @kids-info) kids))
            (load-hn-items)
            (.then
              (fn [loaded-info]
                (swap! kids-info
                  (fn [kids-info]
                    (-> kids-info
                      (update :kids into loaded-info)
                      (update :loaded-ids into (map :id loaded-info)))))))))]
    (load-more 5)
    (fn [{:keys [by title time score descendants id kids]
          :as   story-info}]
      [:div.hn-story.row
       [:a.title (b/ahref-opts (str "https://news.ycombinator.com/item?id=" id)) title]
       [:div.info
        [:div.author by] [:div.n-comments (str descendants " comments")]
        [:div.score (str score " points")]
        [:div.date (ampie.time/timestamp->date (* time 1000))]]
       [:div.children
        (for [kid-info (:kids @kids-info)
              :when    (seq (:text kid-info))]
          ^{:key (:id kid-info)} [hn-comment kid-info 0])
        (let [loaded-kids-count (count (:kids @kids-info))
              kids-count        (count kids)]
          (when (< loaded-kids-count kids-count)
            [:button.inline {:on-click #(load-more 10) :role "button"}
             "Load " (min 10 (- kids-count loaded-kids-count)) " more comments"]))]])))

(defn hn-stories [hn-stories-info]
  [:div.hn-stories.pane
   [:div.header [:span.icon.hn-icon] "HN stories"]
   (if (= hn-stories-info :loading)
     [:div.loading "Loading"]
     (for [{:keys [id] :as story-info} hn-stories-info]
       ^{:key id} [hn-story story-info]))])

(defn visit [{:keys [username comment reaction created-at] :as visit-info}]
  (let [date (ampie.time/timestamp->date (* created-at 1000))]
    [:div.ampie-visit.row
     (when comment [:div.comment comment])
     [:div.info
      [:div.author username]
      [:div.reaction {:class reaction}
       (or reaction "amplified this page")]
      [:div.date date]]]))

(defn visits-component [visits]
  [:div.hn-stories.pane
   [:div.header [:span.icon.ampie-icon] "Ampie"]
   (for [{:keys [id info]} (sort-by (comp :created-at :info) visits)]
     ^{:key id} [visit info])])

(defn seen-at [sources]
  (when (seq sources)
    [:div.seen-at.pane
     [:div.header [:span.icon.history-icon] "Previously seen at"]
     (for [{:keys [url visit-hash first-opened title] :as info} sources]
       ^{:key visit-hash}
       [:div.previous-sighting.row
        [:a.title (b/ahref-opts url) title]
        [:div.info
         [:div.domain (url/get-domain url)]
         [:div.date (ampie.time/timestamp->date first-opened)]]])]))

(defn other-links-with-prefix? [links normalized-url]
  (or (> (count links) 1)
    (and (= (count links) 1)
      (not= normalized-url
        (-> links first :normalized-url)))))

(def shortcuts (r/atom {}))

(defn load-enabled-shortcuts []
  (.then
    (.. browser -runtime
      (sendMessage (clj->js {:type :get-command-shortcuts})))
    (fn [^js js-shortcuts]
      (->> (js->clj js-shortcuts :keywordize-keys true)
        (reduce #(assoc %1 (keyword (:name %2)) %2) {})
        (reset! shortcuts)))))

(defn open-context-in-new-tab-button [url]
  (let [show-shortcut (= ((fnil url/normalize "") url) (url/normalize (.. js/document -location -href)))]
    [:div.new-tab
     {:data-tooltip-text
      (let [shortcut (-> @shortcuts :open_page_context :shortcut)]
        (cond (not show-shortcut) "Open context \nin a new tab"
              (string/blank? shortcut)
              "Open context \nin a new tab \n(add shortcut \nin settings)"
              :else               (str "Open context \nin a new tab \n" shortcut)))
      :on-click (fn [e] (.stopPropagation e)
                  (.. browser -runtime
                    (sendMessage (clj->js {:type :open-page-context
                                           :url  url})))
                  nil)
      :role     "button"}
     [:span.icon.open-in-new-tab-icon]]))

(defn bottom-row
  [{:keys [normalized-url prefixes-info close-page-info
           show-prefix-info]}]
  (let [domain-len         (string/index-of normalized-url "/")
        url-parts          (url/get-parts-normalized normalized-url)
        url-parts          (map #(update % 0 dec)
                             (cons [1 0 (-> url-parts second last)]
                               (drop 2 url-parts)))
        domain-parts       (take-while #(<= (last %) domain-len) url-parts)
        reversed-url-parts (concat
                             (map (fn [[idx start end]]
                                    [idx (- domain-len end) (- domain-len start)])
                               (reverse domain-parts))
                             (drop (count domain-parts) url-parts))
        reversed-normalized-url
        (url/reverse-lower-domain normalized-url)]
    [:div.bottom-row
     (into [:div.url]
       (apply concat
         (for [[idx start end] reversed-url-parts
               :let
               [substr (subs reversed-normalized-url start end)
                info (nth prefixes-info idx)
                links (second info)
                prev-info (when (pos? idx)
                            (nth prefixes-info (dec idx)))
                prev-links (second prev-info)
                after (cond
                        (< end domain-len) "."
                        (= end domain-len) "/"
                        :else              (subs normalized-url end (inc end)))
                highlight?
                (other-links-with-prefix? links normalized-url)]]
           ;; TODO reproduce the bug with the lambda function being cached
           ;; even though highlight? changes if not including highlight?
           ;; into the key.
           [^{:key (str start highlight?)}
            [:span.part
             (when highlight?
               {:on-click #(show-prefix-info info)
                :role     "link"
                :class    "highlight"})
             (js/decodeURI substr)]
            (when-not (empty? after) after)])))
     [open-context-in-new-tab-button reversed-normalized-url]
     [:div.close {:on-click close-page-info :role "button"}
      [:span.icon.close-icon]]]))

(defn window [{:keys [overscroll-handler window-atom tight class]}]
  (letfn [(change-height [el propagate delta-y]
            (let [current-height    (js/parseFloat
                                      (. (js/getComputedStyle el)
                                        getPropertyValue "height"))
                  min-height        28
                  lowest-child-rect (.. el -lastElementChild
                                      getBoundingClientRect)
                  children-height   (- (+ (. lowest-child-rect -y)
                                         (. lowest-child-rect -height))
                                      (.. el getBoundingClientRect -y))
                  max-height        (+ children-height 8) ;; 8 for bottom padding
                  new-height        (+ current-height
                                      delta-y)]
              (cond
                (< new-height min-height)
                (do (set! (.. el -style -height) (str min-height "px"))
                    (when propagate
                      (overscroll-handler :down (- min-height new-height))))

                (> new-height max-height)
                (do (set! (.. el -style -height) (str max-height "px"))
                    ;; Return false not to propagate the scroll
                    false)

                :else
                (do (set! (.. el -style -height) (str new-height "px"))
                    ;; Return false not to propagate the scroll
                    false))))]
    (into [:div.window
           {:class [(when tight "tight") class]
            :ref
            (fn [el]
              (when el
                (swap! window-atom assoc :ref el)
                (swap! window-atom assoc
                  :update-height (partial change-height el true))
                (set! (.-onwheel el)
                  (fn [evt]
                    (change-height el true
                      ;; Account for firefox using deltamode = 1
                      ;; and its deltaY being in lines scrolled, not in pixels
                      (* (if (= (.-deltaMode evt) 1) 16 1)
                        (. evt -deltaY)))))))}]
      (r/children (r/current-component)))))

(defn elements-stack []
  (let [children       (r/children (r/current-component))
        children-info  (for [_ (range (count children))] (r/atom {}))
        update-heights (fn [index direction delta]
                         (if (= index (dec (count children)))
                           ;; Don't propagate the scroll event
                           false
                           ((:update-height
                             @(nth children-info (inc index)))
                            (if (= direction :up)
                              delta
                              (- delta)))))]
    (into [:div.stack]
      (map #(vector window
              {:window-atom        %2
               :class              (-> %1 meta :window-class)
               :tight              (-> %1 meta :tight)
               :overscroll-handler (partial update-heights %3)}
              %1)
        (r/children (r/current-component)) children-info (range)))))

(defn mini-tag [source-key count]
  (if (pos? count)
    [:div.mini-tag
     [:span.icon {:class (str (if (= source-key :visits)
                                "ampie"
                                (name source-key)) "-icon")}]
     (if (< count 1000)
       count
       (str (quot count 1000) "k"))]
    [:div.mini-tag]))

(defn adjacent-link-row [{:keys [normalized-url seen-at url title blinking]}
                         prefix load-page-info]
  (let [reversed-normalized-url     (url/reverse-lower-domain normalized-url)
        reversed-prefix             (url/reverse-lower-domain prefix)
        {:keys [visits hn twitter]} seen-at
        who-shared
        (->> (concat visits twitter)
          (map #(or (-> % second :v-username)
                  (-> % second :t-author-name)))
          (filter identity)
          frequencies
          (map #(str (key %) (when (> (val %) 1) (str "×" (val %))))))]
    [:div.row.adjacent-link
     [:div.upper-part
      [:div.title-and-url
       (when title [:div.title title])
       [:div.url
        (into
          [:a (b/ahref-opts (or url (str "http://" reversed-normalized-url)))]
          (if-let [index (clojure.string/index-of reversed-normalized-url
                           reversed-prefix)]
            (let [start (subs reversed-normalized-url 0 index)
                  end   (subs reversed-normalized-url (+ index (count prefix)))]
              [(js/decodeURI start)
               [:span.prefix (js/decodeURI reversed-prefix)]
               (js/decodeURI end)])
            [reversed-normalized-url]))]]
      [:div.inline-mini-tags {:class    (when blinking :blinking)
                              :on-click #(load-page-info reversed-normalized-url)}
       [mini-tag :visits (links/count-visits visits)]
       [mini-tag :hn (links/count-hn hn)]
       [mini-tag :twitter (links/count-tweets twitter)]]
      [open-context-in-new-tab-button url]]
     (when (or (seq twitter) (seq visits))
       (let [batch (cond (= (count who-shared) 6)
                         who-shared
                         :else
                         (take 5 who-shared))]
         [:div.shared-usernames
          (string/join " " batch)
          (cond (> (count who-shared) (count batch))
                (str " + " (- (count who-shared) (count batch))
                  " more"))]))]))

(defn adjacent-links [[normalized-url links] load-page-info only-local-data]
  (let [pages-info          (r/atom {:loaded              []
                                     :n-loading-or-loaded (if only-local-data
                                                            (count links)
                                                            0)
                                     :not-loaded          links})
        clicked-on-blinking (r/atom false)
        loading             (r/atom false)
        get-link-id
        (fn [{:keys [seen-at]}]
          (or (-> seen-at :hn first first)
            (-> seen-at :visits first first)
            (-> seen-at :twitter first first)))
        batch-size          5
        load-next-batch
        (fn []
          (let [{:keys [loaded not-loaded]} @pages-info
                next-batch                  (take batch-size not-loaded)]
            (reset! loading true)
            (swap! pages-info update :n-loading-or-loaded + batch-size)
            (->
              (.. browser -runtime
                (sendMessage
                  (clj->js {:type     :get-links-pages-info
                            :link-ids (map get-link-id next-batch)})))
              (.then #(js->clj % :keywordize-keys true))
              (.then (fn [next-batch-info]
                       (swap! pages-info
                         (fn [val]
                           (-> val
                             (update :loaded into next-batch-info)
                             (update :not-loaded #(drop batch-size %)))))))
              (.then (js/Promise. #(js/setTimeout % 3000)))
              (.finally #(reset! loading false)))))]
    (when-not only-local-data (load-next-batch))
    (fn [[normalized-url links] load-page-info only-local-data]
      [:div.adjacent-links.pane
       [:div.header [:span.icon.domain-links-icon]
        "Links at " (url/reverse-lower-domain normalized-url)
        (when @loading [:span.spinner])]
       (when only-local-data
         [:p {:style {:margin-top "4px" :margin-bottom 0}}
          "This list was created from your local cache. To request page titles, click the link above"])
       (let [{:keys [n-loading-or-loaded loaded not-loaded]} @pages-info]
         [:<>
          (doall
            (for [{link-url :normalized-url title :title :as link}
                  (map #(merge %1 %2) (take n-loading-or-loaded links)
                    (concat loaded (repeat nil)))
                  :let [blinking
                        (and (= normalized-url "com.eugenewei")
                          (= link-url "com.eugenewei/blog/2019/2/19/status-as-a-service")
                          (is-demo-url? (.. js/document -location -href))
                          (not @clicked-on-blinking))]]
              ^{:key (str link-url " " title)}
              [adjacent-link-row
               (assoc link :blinking blinking)
               normalized-url
               (fn [& args]
                 (when blinking (reset! clicked-on-blinking true))
                 (apply load-page-info args))]))
          (when (and (not only-local-data) (seq not-loaded))
            [:div.row.load-more
             (if @loading
               "Loading"
               [:a {:on-click (fn [e] (.stopPropagation e) (load-next-batch))}
                "Load more"])])])])))

(defn this-page-preview [normalized-url counts load-page-info]
  (let [reversed-normalized-url (url/reverse-lower-domain normalized-url)]
    [:div.this-page-preview.pane
     {:on-click #(load-page-info reversed-normalized-url)}
     [:div.header
      [:a (if (or (pos? (:visits counts))
                (pos? (:hn counts))
                (pos? (:twitter counts)))
            "Request page info"
            "Load page titles")]]
     [:div.inline-mini-tags
      [mini-tag :visits (:visits counts)]
      [mini-tag :hn (:hn counts)]
      [mini-tag :twitter (:twitter counts)]]]))

(defn domain-links-notice []
  [:div.notice.pane
   [:div.header "Blimey, why did this thing pop up?!"]
   [:p "This popup appears on a domain you "
    "haven't visited before to show you interesting links. "
    "The data comes from your local cache, so your browsing history "
    "is your secret to keep. When you click on the twitter or HN icon "
    "next to a link, ampie queries its server for previous discussions of that link. "
    "To hide these popups, click on the ampie icon in the extensions toolbar and go to settings."]])
(defn subdomains-notice []
  [:div.notice.pane
   [:p "Try clicking the underlined parts of the URL below."]])

(defn load-failed-message [fail-message]
  [:div.notice.pane
   [:p "Couldn't load data from the server. "
    fail-message]])

(defn share-page-notice []
  [:div.share-page.pane
   [:div.header {:on-click (fn [e] (.stopPropagation e)
                             ((:amplify-page @amplify/amplify)))}
    [:a "Amplify this page"]]])

(defn info-bar [{:keys       [page-info close-page-info show-prefix-info
                              index load-page-info hidden]
                 :style/keys [opacity right]}]
  (let [{{:keys [history hn twitter visits]} :seen-at
         :keys
         [normalized-url prefixes-info prefix-info only-local-data counts
          show-auto-open-notice show-subdomains-notice fail fail-message]}
        page-info]
    [:div.info-bar {:class [(when hidden :hidden)
                            (str "info-bar--" index)]
                    :style (merge (when opacity {:opacity opacity})
                             (when right {:right right}))}
     (into
       [elements-stack]
       (filter identity
         [(when (= (get-current-url) (:url page-info))
            ^{:key :share-page-notice :tight true}
            [share-page-notice])
          (when fail
            ^{:key :fail-message :tight true}
            [load-failed-message fail-message])
          (when (and only-local-data
                  show-auto-open-notice
                  prefix-info (seq (second prefix-info)))
            ^{:key :domain-links-notice :tight true}
            [domain-links-notice])
          (when (and only-local-data
                  (or (pos? (:twitter counts))
                    (pos? (:visits counts))
                    (pos? (:hn counts))
                    (and prefix-info (seq (second prefix-info)))))
            ^{:key :this-page-preview :tight true}
            [this-page-preview normalized-url counts load-page-info])
          (when history ^{:key :seen-at} [seen-at history])
          (when visits ^{:key :visits} [visits-component visits])
          (when twitter ^{:key :tweets :window-class :twitter-window}
            [tweets twitter normalized-url])
          (when hn ^{:key :hn-stories :window-class :hn-window}
            [hn-stories hn])
          (when (and prefix-info (seq (second prefix-info)))
            ^{:key [:prefix-info (first prefix-info)]}
            [adjacent-links prefix-info load-page-info
             only-local-data])
          (when show-subdomains-notice
            ^{:key :subdomains-notice :tight true} [subdomains-notice])]))
     [bottom-row
      {:normalized-url   normalized-url
       :prefixes-info    prefixes-info
       :close-page-info  close-page-info
       :show-prefix-info show-prefix-info}]]))

(defn mini-tags [{{domain-links :on-this-domain
                   :keys        [counts normalized-url show-weekly]} :page-info
                  :keys
                  [open-info-bar close-mini-tags]}]
  [:div.mini-tags {:role     "button"
                   :on-click open-info-bar}
   (when show-weekly
     [:div.weekly
      {:role     "link"
       :on-click (fn [evt]
                   (.stopPropagation evt)
                   (.. browser -runtime
                     (sendMessage (clj->js {:type :open-weekly-links}))))}
      "Weekly"])
   (for [[source-key count] counts
         :when              (pos? count)]
     ^{:key source-key}
     [:div.mini-tag
      [:span.icon {:class (str (name source-key) "-icon")}]
      count])
   (when (other-links-with-prefix? domain-links normalized-url)
     [:div.mini-tag
      [:span.icon.domain-links-icon]
      (let [count (count domain-links)]
        (if (>= count 200)
          (str count "+")
          count))])
   [open-context-in-new-tab-button (get-current-url)]
   [:div.close {:on-click (fn [e] (.stopPropagation e) (close-mini-tags) nil)
                :role     "button"}
    [:span.icon.close-icon]]])

(defn hydrate-hn [hn-stories]
  (let [stories-ids (map (comp :item-id :info) hn-stories)]
    (load-hn-items stories-ids)))

(defn load-page-info [url pages-info only-local-data]
  (send-message-to-page {:type :ampie-load-page-info :url url})
  (letfn [(get-prefixes-info []
            (.then (.. browser -runtime
                     (sendMessage (clj->js {:type :get-prefixes-info
                                            :url  url})))
              #(js->clj % :keywordize-keys true)))]
    (.then (.. browser -runtime
             (sendMessage (clj->js {:type (if only-local-data
                                            :get-local-url-info
                                            :get-url-info)
                                    :url  url})))
      (fn [js-url->where-seen]
        (let [{:keys [hn twitter history visits fail message] :as seen-at}
              (js->clj js-url->where-seen :keywordize-keys true)
              new-page-info
              {:url             url
               :normalized-url  (url/normalize url)
               :only-local-data only-local-data
               :seen-at
               (merge
                 {:history (seq history)}
                 (when-not only-local-data
                   {:twitter (when (pos? (count twitter)) :loading)
                    :visits  (seq visits)
                    :hn      (when (pos? (count hn)) :loading)}))
               :counts          {:history (count history)
                                 :twitter (links/count-tweets twitter)
                                 :hn      (links/count-hn hn)
                                 :visits  (links/count-visits visits)}
               :fail            fail
               :fail-message    message}]
          (let [idx            (-> (swap! pages-info update
                                     :info-bars conj new-page-info)
                                 :info-bars count dec)
                normalized-url (url/normalize url)
                swap-info-bars!
                (fn [& args]
                  (swap! pages-info
                    (fn [{:keys [info-bars] :as pages-info}]
                      ;; Check that the info bar hasn't been closed
                      (if (> (count info-bars) idx)
                        (apply update-in pages-info [:info-bars idx]
                          args)
                        pages-info))))]
            (when only-local-data
              (.then (.. browser -runtime
                       (sendMessage (clj->js {:type :show-domain-links-notice?})))
                (fn [show?]
                  (swap-info-bars! assoc :show-auto-open-notice show?)))
              (js/setTimeout
                (fn []
                  ;; Mark the domains notice as seen only if the info bar hasn't
                  ;; been detached after 5 seconds
                  (when-not (:removed @pages-info)
                    (.. browser -runtime
                      (sendMessage (clj->js {:type :saw-domain-links-notice})))))
                5000))
            (.then (get-prefixes-info)
              (fn [prefixes-info]
                (let [filtered (filter
                                 (fn [[_ links]]
                                   (other-links-with-prefix?
                                     links normalized-url))
                                 prefixes-info)]
                  (when (and (> (count filtered) 1)
                          (not (is-demo-url? (.. js/document -location -href))))
                    (.then (.. browser -runtime
                             (sendMessage
                               (clj->js {:type :subdomains-notice?})))
                      #(swap-info-bars! assoc :show-subdomains-notice %))))
                (swap-info-bars! assoc :prefixes-info prefixes-info)
                (let [;; Find the entry for the domain among prefixes
                      domain-info (->> (remove #(clojure.string/includes?
                                                  (first %) "/") prefixes-info)
                                    last)
                      links       (second domain-info)]
                  ;; Show only if there are links besides the one the user is reading
                  ;; about
                  (when (other-links-with-prefix? links (url/normalize url))
                    (swap-info-bars! assoc :prefix-info domain-info)))))
            (when (and (not only-local-data) (seq twitter))
              (.then (hydrate-tweets twitter)
                (fn [twitter-info]
                  (swap-info-bars! assoc-in [:seen-at :twitter] twitter-info))))
            (when (and (not only-local-data) (seq hn))
              (.then (hydrate-hn hn)
                (fn [hn-info]
                  (swap-info-bars! assoc-in [:seen-at :hn] hn-info))))))))))

(defn info-bars-and-mini-tags [{:keys [pages-info close-info-bar]}]
  (when-not (:removed @pages-info)
    [:div.info-bars-and-mini-tags
     [:<>
      (when (and (:open (:mini-tags @pages-info))
              (not (:hidden @pages-info)))
        ^{:key :mini-tags}
        [mini-tags {:page-info (:mini-tags @pages-info)
                    :open-info-bar
                    #(load-page-info
                       (get-current-url)
                       pages-info
                       false)
                    :close-mini-tags
                    #(swap! pages-info assoc-in [:mini-tags :open] false)}])
      (doall
        (for [[index page-info] (map-indexed vector (:info-bars @pages-info))
              :let              [index-from-end (- (count (:info-bars @pages-info)) (inc index))]]
          ^{:key [index (:url page-info)]}
          [info-bar {:page-info       page-info
                     :index           index
                     :hidden          (:hidden @pages-info)
                     :load-page-info  (fn [url] (load-page-info url pages-info false))
                     :close-page-info (fn []
                                        (send-message-to-page
                                          {:type :ampie-infobar-closed
                                           :url  (-> @pages-info :info-bars peek :url)})
                                        (swap! pages-info update :info-bars pop))
                     :style/opacity   (- 1.0 (* index-from-end 0.2))
                     :style/right     (str (* index-from-end 10) "px")
                     :show-prefix-info
                     (fn [prefix-info]
                       (swap! pages-info
                         update :info-bars
                         (fn [info-bars]
                           (let [current
                                 (get-in info-bars
                                   [(dec (count info-bars)) :prefix-info])]
                             (when-not (= current prefix-info)
                               (.. browser -runtime
                                 (sendMessage (clj->js
                                                {:type :clicked-subdomain})))))
                           (assoc-in info-bars
                             [(dec (count info-bars)) :prefix-info]
                             prefix-info))))}]))]]))

(defn reset-current-page-info! [pages-info]
  (let [current-url    (get-current-url)
        normalized-url (url/normalize current-url)]
    (reset! pages-info {:mini-tags {:url            current-url
                                    :normalized-url normalized-url}
                        :info-bars []})
    (.then (.. browser -runtime
             (sendMessage (clj->js {:type :should-show-weekly?})))
      #(swap! pages-info assoc-in [:mini-tags :show-weekly] %))
    (.then (.. browser -runtime
             (sendMessage (clj->js {:type :get-local-url-info
                                    :url  current-url})))
      (fn [js-url->where-seen]
        (let [{:keys [hn twitter history visits] :as seen-at}
              (js->clj js-url->where-seen :keywordize-keys true)]
          (swap! pages-info assoc-in [:mini-tags :seen-at :history] (seq history))
          (swap! pages-info assoc-in [:mini-tags :counts]
            {:history (count history)
             :twitter (links/count-tweets twitter)
             :visits  (links/count-visits visits)
             :hn      (links/count-hn hn)})
          (when (or (seq visits) (seq history) (seq twitter) (seq hn))
            (swap! pages-info assoc-in [:mini-tags :open] true)))))
    (.then (.. browser -runtime
             (sendMessage (clj->js {:type :get-prefixes-info
                                    :url  current-url})))
      (fn [prefixes-info]
        (let [prefixes-info (js->clj prefixes-info :keywordize-keys true)
              domain-links  (-> (remove #(clojure.string/includes? (first %) "/")
                                  prefixes-info)
                              last
                              second)]
          (swap! pages-info assoc-in [:mini-tags :on-this-domain] domain-links)
          (when (other-links-with-prefix? domain-links normalized-url)
            (swap! pages-info assoc-in [:mini-tags :open] true)
            (when (not (is-demo-url? (.. js/document -location -href)))
              (-> (.. browser -runtime
                    (sendMessage (clj->js {:type :should-show-domain-links?
                                           :url  current-url})))
                (.then
                  #(when % (load-page-info current-url pages-info true)))))))))))

(defn remove-info-bar []
  (let [element (.. js/document -body (querySelector ".ampie-info-bar-holder"))]
    (when element (.. js/document -body (removeChild element)))))

(defn display-info-bar
  "Mount the info bar element,
  display the mini tags if necessary, return the function
  that needs to be called with a url to be displayed in a new info bar."
  []
  (when (. js/document querySelector ".ampie-info-bar-holder")
    (when goog.DEBUG
      (js/alert "ampie: attaching a second info bar")
      (js/console.trace))
    (throw "Info bar already exists"))
  (load-enabled-shortcuts)
  (let [info-bar-div   (. js/document createElement "div")
        shadow-root-el (. js/document createElement "div")
        shadow         (. shadow-root-el (attachShadow #js {"mode" "open"}))
        shadow-style   (. js/document createElement "link")
        pages-info     (r/atom {})]
    (set! (.-rel shadow-style) "stylesheet")
    (.setAttribute shadow-root-el "style"  "display: none;")
    (set! (.-onload shadow-style) #(.setAttribute shadow-root-el "style" ""))
    (set! (.-href shadow-style) (.. browser -runtime (getURL "assets/info-bar.css")))
    (set! (.-className shadow-root-el) "ampie-info-bar-holder")
    (set! (.-className info-bar-div) "info-bar-container")
    (reset-current-page-info! pages-info)
    (rdom/render [info-bars-and-mini-tags {:pages-info pages-info}] info-bar-div)
    (. shadow (appendChild shadow-style))
    (. shadow (appendChild info-bar-div))
    (retargetEvents shadow)
    (. js/document
      (addEventListener "keyup"
        (fn [e]
          (when (and (or (= (.-key e) "Escape") (= (.-key e) "Esc"))
                  (seq (:info-bars @pages-info)))
            (.stopPropagation e)
            (swap! pages-info update :info-bars pop)))))
    ;; Hiding the info bar when full screen and when focused in a text field
    (. js/document addEventListener "focusin"
      (fn [_]
        (when (ampie.utils/is-text-node? (.-activeElement js/document))
          (swap! pages-info assoc :hidden true))))
    (. js/document addEventListener "focusout"
      (fn [_]
        (when (not (.-fullscreenElement js/document))
          (swap! pages-info assoc :hidden false))))
    (. js/document addEventListener "fullscreenchange"
      (fn [_]
        (if (.-fullscreenElement js/document)
          (swap! pages-info assoc :hidden true)
          (swap! pages-info assoc :hidden false))))
    (.. js/document -body (appendChild shadow-root-el))
    ;; Return a function to be called with a page url we want to display an info
    ;; bar for.
    {:reset-page      (fn [] (reset-current-page-info! pages-info))
     :show-info       (fn [page-url] (load-page-info page-url pages-info false))
     :remove-info-bar (fn [] (swap! pages-info assoc :removed true))}))

(defstate info-bar-state
  :start (display-info-bar)
  :stop (do (remove-info-bar)
            ((:remove-info-bar @info-bar-state))))
