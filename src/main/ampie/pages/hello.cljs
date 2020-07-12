(ns ampie.pages.hello
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [taoensso.timbre :as log]
            [ampie.components.visit :as components.visit]
            [ampie.components.basics :as b]
            [ampie.db :refer [db]]
            [ampie.visits :as visits]
            [ampie.visits.db :as visits.db]
            [ampie.time :as time]
            [ampie.settings :refer [settings]]
            [ampie.background.backend :refer [user-info] :as backend]
            ["webextension-polyfill" :as browser]
            [mount.core :as mount]))

(def state (r/atom {}))

(defn hello-page []
  [:div.hello-page.content
   [:div.header [:h1 "Hello from ampie!"]]
   [:div.hello-container
    (if-not @@user-info
      [:p "First, please sign up at "
       [:a (b/ahref-opts "https://ampie.app/register") "ampie.app"] "."]
      [:p "Give ampie ten minutes to download and unpack its caches, "
       "it might be CPU-intensive for 10 minutes while unpacking."])
    [:p
     "On some webpages you will now see this bar:"
     [:img.mini-tags {:src (.. browser -runtime (getURL "assets/images/mini-tags.png"))}]
     "The numbers indicate the following:"]
    [:ul
     [:li [:div.ampie-mini-tag-icon.ampie-history-icon] " " [:b "Seen at"]
      ": the number of times "
      "you have seen the page you are on referenced on other pages you have been to. "]
     [:li [:div.ampie-mini-tag-icon.ampie-twitter-icon] " " [:b "Tweets"]
      ": the number of times the page has appeared "
      "in your twitter feed."]
     [:li [:div.ampie-mini-tag-icon.ampie-hn-icon] " " [:b "HN stories"]
      ": the number of times the page appeared on "
      [:a (b/ahref-opts "https://news.ycombinator.com") "Hacker News"]
      " and got at least 5 upvotes."]
     [:li [:div.ampie-mini-tag-icon.domain-links-icon] " " [:b "Links on this domain"]
      ": the number of pages on the same domain that have appeared in your "
      "twitter feed or on hacker news."]]

    [:h3 "Browsing history"]
    [:p "Click on the ampie icon in the toolbar to the right of the browser address bar, "
     [:span.margin-note
      [:img
       {:style {:height "36px"}
        :src   (.. browser -runtime (getURL "assets/images/chrome-toolbar-icon.png"))}]
      "Chrome might also hide the ampie icon behind that puzzle button."]
     "and you will see what links you followed to get to the current page:"]
    [:img.history {:src (.. browser -runtime (getURL "assets/images/history.png"))}]
    [:p
     "Ampie also lets you see your browsing "
     [:a (b/ahref-opts (.. browser -runtime (getURL "history.html"))) "history"]
     " in a tree."]
    [:p "Now ampie’s history page is empty because "
     " ampie records your browsing history itself, not using existing browser's history. But the history "
     "page will start filling up now."]

    [:h3 "Weekly links"]
    [:p "Every Sunday ampie will offer you to choose the “weekly links” — "
     "the best links you have found last week. "
     "They don't have to be text posts: "
     "anything goes, as long as you "
     "find it interesting, important, and you might want to revisit it months or years later. "]

    [:h3 "What about privacy?"]
    [:p "Your browsing history is not sent anywhere. "
     "The extension sends the URL of the page you are on to the server "
     "only when you click the bar in the bottom right corner of the page or "
     "click an ampie ampersand near a link."]

    [:h3 "What now?"]
    [:p "Click on the ampie icon in the toolbar to keep track of whether it is "
     "done downloading and unpacking caches. "
     "Please DM me any feedback you have "
     [:a (b/ahref-opts "https://twitter.com/posobin") "@posobin"] "."]
    [:p "Hope you find ampie useful!"]]]
  )

(defn ^:dev/after-load init []
  (mount/start)
  (rdom/render [hello-page]
    (. js/document getElementById "hello-content")))
