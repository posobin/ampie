(ns ampie.pages.hello
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [ampie.components.basics :as b]
            [ampie.background.backend :refer [user-info] :as backend]
            [clojure.string]
            [mount.core :as mount]))

(def state (r/atom {}))

(defn subheader [text]
  [:h3.pt-4.pb-2.text-2xl text])

(defn highlight [text bad?]
  [:span.rounded.pl-0dot5.pr-0dot5
   {:class (if bad? :bg-indigo-200 :bg-yellow-200)}
   text])

(defn mac? [] (clojure.string/starts-with? (.-platform js/navigator) "Mac"))

(defn hello-page []
  [:div.font-sans.text-lg.leading-snug.ml-auto.mr-auto.w-fit-content.p-4.rounded-xl.shadow-xl.m-4.border.relative
   [:h1.text-2xl.font-italic.pb-3 "Ampie installed"]
   [:div.max-w-md
    (cond
      (not @@user-info)
      [:p "First, please sign up at "
       [:a (b/ahref-opts "https://ampie.app/register") "ampie.app"] "."]
      (not (:twitter-username @@user-info))
      [:p.border.border-2.border-red-50.rounded-md.p-2 "Log in with twitter at "
       [:a (b/ahref-opts "https://ampie.app/") "ampie.app"]
       " to see tweets about pages you visit. Then read on."]
      :else
      [:p "Hi! Three things."])
    [subheader "Sidebar"]
    [:p "Easy: go to a page, sidebar pops up. "
     "Not to annoy you, it " [highlight "pops up at most twice per page" false] ". "
     "Use keyboard shortcuts, Luke: "
     (if (mac?) "Opt-Opt" "Ctrl-Ctrl")
     ", Shift-Shift."]

    [subheader "Amplify pages"]
    [:p "After two minutes on a page, "
     [highlight "ampie suggests to you to amplify it" false] ". "
     "Other ampie users will see who amplified the current page in their sidebar. "
     "You can disable this suggestion in settings: "
     "click on the ampie icon in the extensions menu near address bar."]

    [subheader "Leave feedback"]
    [:p [highlight "Scroll to the bottom of the sidebar" false]
     " to quickly leave any kind of feedback "
     "(questions? ideas? bugs? or just say hi!)."]

    [:p.pt-4 "That's enough to get you started, hope you like it!"]

    [:p.pt-2 "& "
     [:a.text-underline (b/ahref-opts "https://twitter.com/posobin") "posobin"] " &"]]])

(defn ^:dev/after-load init []
  (mount/start (mount/only #{#'ampie.background.backend/auth-token
                             #'ampie.background.backend/user-info
                             #'ampie.background.backend/cookie-watcher}))
  (rdom/render [hello-page]
    (. js/document getElementById "hello-content")))
