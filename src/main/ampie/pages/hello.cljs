(ns ampie.pages.hello
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [taoensso.timbre :as log]
            [ampie.components.basics :as b]
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
     #_[:li [:div.ampie-mini-tag-icon.ampie-history-icon] " " [:b "Seen at"]
        ": the number of times "
        "you have seen the page you are on referenced on other pages you have been to. "]
     [:li [:div.ampie-mini-tag-icon.ampie-visits-icon] " " [:b "Amplified"]
      ": how many times this page has been “amplified” (shared with ampie) "
      "by people you follow."]
     [:li [:div.ampie-mini-tag-icon.ampie-twitter-icon] " " [:b "Tweets"]
      ": the number of people you follow who have tweeted about this page."]
     [:li [:div.ampie-mini-tag-icon.ampie-hn-icon] " " [:b "HN stories"]
      ": the total number of comments the page has gotten on "
      [:a (b/ahref-opts "https://news.ycombinator.com") "Hacker News"]]
     [:li [:div.ampie-mini-tag-icon.domain-links-icon] " " [:b "Links on this domain"]
      ": the number of pages on the same domain that have appeared in your "
      "twitter feed, on hacker news or that someone you follow has amplified with ampie."]]
    [:p "Click the bar to get more details about the page and the domain. "
     "Keep in mind that this sends a request to ampie's server with the current URL."]

    [:h3 "What about privacy?"]
    [:p "Your browsing history is not sent anywhere. "
     "The extension sends the URL of the page you are on to the server "
     "only when you click the bar in the bottom right corner of the page, "
     "click an ampie ampersand near a link, or amplify the page."]

    [:h3 "Amplify"]
    [:p "After you spend two minutes on a page, ampie will show you the amplify dialog "
     "at the bottom of the screen."]
    [:img.amplify {:src (.. browser -runtime (getURL "assets/images/amplify-dialog.png"))}]
    [:p "The idea is to let you share the pages that are interesting more easily. "
     "There is no expectation that you have read the page, or that you agree with it. "
     "Ideally you amplify the pages that you might want to return back to months or years later. "
     "After clicking amplify, you can add a comment and a reaction."]
    [:img.amplify-edit {:src (.. browser -runtime (getURL "assets/images/amplify-dialog-edit.png"))}]
    [:p "You can disable this dialog by going into the extension's settings. "]
    [:p "I will add some more useful features related to this, "
     "e.g. archiving the pages you amplify, or getting notified "
     "when someone you follow leaves a comment on a page you have marked as to read. "
     "Any suggestions how to make this more useful welcome!"]
    [:p "You can change the shortcut to amplify the page in "
     [:a (b/ahref-opts "chrome://extensions/shortcuts") "settings"]
     "."]

    [:h3 "Badges"]
    [:p "Near some links ampie will display little ampersands (badges), they mark "
     "the links that someone has amplified / tweeted / posted on HN. "
     "Click on the badge to see more info about the link. "
     "For any particular URL the badge is displayed at most three times "
     "and is not shown after that to prevent visual clutter."]

    #_[:h3 "Weekly links"]
    #_[:p "Every Sunday ampie will offer you to choose the “weekly links” — "
       "the best links you have found last week. "
       "They don't have to be text posts: "
       "anything goes, as long as you "
       "find it interesting, important, and you might want to revisit it months or years later. "]

    [:h3 "Feedback"]
    [:p "Your feedback will help me a lot to make ampie better. "
     "You can DM me your thoughts ("
     [:a (b/ahref-opts "https://twitter.com/posobin") "@posobin"] "), or click "
     "on the ampie icon in the extensions toolbar and click \"give feedback\" "
     "to fill out a google form. I'll get an email with your feedback and will "
     "definitely read it and respond."]

    [:h3 "What now?"]
    [:p "Click on the ampie icon in the toolbar to keep track of whether it is "
     "done downloading and unpacking caches. "]
    [:p "Hope you find ampie useful!"]]])

(defn ^:dev/after-load init []
  (mount/start (mount/only #{#'ampie.background.backend/auth-token
                             #'ampie.background.backend/user-info
                             #'ampie.background.backend/cookie-watcher}))
  (rdom/render [hello-page]
    (. js/document getElementById "hello-content")))
