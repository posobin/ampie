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
   [:div.header [:h1 "Ampie updated"]]
   [:div.update-container
    [:p "Some changes I have made require clearing and redownloading the caches, "
     "so ampie might use a lot of CPU for the next five minutes or so. "
     "Sorry for the inconvenience!"]

    [:h3 "Amplify"]
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
     "Any suggestions how to make this more useful are welcome!"]


    [:h3 "Badges"]
    [:p
     "For any particular URL the badge will now be displayed at most three times "
     "and is not shown after that to prevent visual clutter. "
     "E.g. the menu items on hacker news will stop getting badges after you "
     "go to hacker news three times. "
     "You can still disable the badges in the settings of the extension."]

    [:h3 "Links cache sync"]
    [:p "I have added incremental updates to the link cache. "
     "Previously if the link cache got updated on the server, the extension "
     "would download the whole cache with all the links it already had. "
     "Now it will download only the links that were added in the new cache."]]])

(defn ^:dev/after-load init []
  (mount/start)
  (rdom/render [update-page]
    (. js/document getElementById "update-content")))
