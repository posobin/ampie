(ns ampie.pages.update
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

(defn update-page []
  [:div.update-page.content
   [:div.header [:h1 "Ampie updated (again)"]]
   [:div.update-container
    [:p "Some changes I have made require clearing and redownloading the caches, "
     "so ampie might use a lot of CPU for the next five minutes or so. "
     "(for real this time, not like the last time) "
     "Sorry for the inconvenience!"]

    [:h3 "Feedback"]
    [:p "Click the ampie icon in the extensions toolbar and go "
     "to the feedback form. Don't hesitate to use it, especially if you spot bugs! "
     "I will respond to all feedback you send."]

    [:h3 "History"]
    [:p "I have disabled history tracking for now. "
     "I initially added it because I wanted to add the public browsing mode, but "
     "have now decided against that. Having the extension collect history might "
     "be scary even if the extension is open source. "
     "I might reuse the code for it if I decide to add public "
     "browsing after all."]

    [:h3 "Weekly links"]
    [:p "Weekly links page is temporarily disabled since I have disabled "
     "history tracking. I'll enable it when I integrate it with the amplify "
     "button."]

    [:h3 "HN discussions"]
    [:p "Ampie now shows HN comments for the page you are on, "
     "no need to go to HN to read them. Twitter is next!"]
    [:p "Moreover, instead of the number of HN threads with that link "
     "ampie now shows the number of comments in all the threads with that link. "
     "And using the number of different people who have tweeted about the link "
     "instead of the number of tweets."]

    [:h3 "Page titles"]
    [:p "Previously it was impossible to use the recommended links list "
     "on websites that don't have readable urls (e.g. youtube, arxiv, etc). "
     "Now ampie will show the titles of the pages. It is not fast since I put in "
     "some basic throttling to prevent the ampie server from being banned by "
     "the websites for too many requests, but it is very usable still."]

    [:h3 "Amplify (repeating just in case)"]
    [:p "After you spend two minutes on a page, ampie will show you the amplify dialog "
     "at the bottom of the screen."]
    [:img.amplify {:src (.. browser -runtime (getURL "assets/images/amplify-dialog.png"))}]
    [:p "The idea is to let you share the pages that are interesting more easily. "
     "There is no expectation that you have read the page, or that you agree with it. "
     "This is to help your followers find this page in the future. "
     "After clicking amplify, you can add a comment and a reaction. "
     "Use the keyboard shortcuts to share quickly!"]
    [:img.amplify-edit {:src (.. browser -runtime (getURL "assets/images/amplify-dialog-edit.png"))}]
    [:p "You can disable this dialog by going to the extension's settings "
     "(click the ampie icon in the extension toolbar for that). "
     "Plus you can disable it only on some specific websites there."]
    [:p "I will add some more useful features related to this, "
     "e.g. archiving the pages you amplify, or getting notified "
     "when someone you follow leaves a comment on a page you have marked as to read. "
     "Any suggestions how to make this more useful are welcome!"]]])

(defn ^:dev/after-load init []
  (mount/start)
  (rdom/render [update-page]
    (. js/document getElementById "update-content")))
