(ns ampie.pages.update
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [taoensso.timbre :as log]
            [ampie.components.visit :as components.visit]
            [ampie.components.basics :as b]
            [clojure.string]
            ["webextension-polyfill" :as browser]))

(def state (r/atom {}))

(defn subheader [text]
  [:h3.pt-4.pb-2.text-2xl text])

(defn highlight [text bad?]
  [:span.rounded.pl-0dot5.pr-0dot5
   {:class (if bad? :bg-indigo-200 :bg-yellow-200)}
   text])

(defn mac? [] (clojure.string/starts-with? (.-platform js/navigator) "Mac"))

(defn update-page []
  [:div.font-sans.text-lg.leading-snug.ml-auto.mr-auto.w-fit-content.p-4.rounded-xl.shadow-xl.m-4.border.relative
   [:h1.text-2xl.font-italic.pb-3 "Ampie updated"]
   [:div.max-w-md
    [:p "Hello!"]

    (when (not (mac?))
      [:<>
       [subheader "Changed Alt-Alt to Ctrl-Ctrl on non-Macs"]
       [:p "The Alt-Alt shorcut didn't work on windows (and on some linux systems as well). "
        "I changed it to " [highlight "Ctrl-Ctrl" false] ". "
        "Now you can finally close/open the sidebar from your keyboard!"]])

    [subheader "Sidebar opens once per domain"]
    [:p "Previously, when a domain had interesting links, sidebar would show up twice on the domain. "
     "It will show up " [highlight "only once now" false]
     ", except for pages that have their own context to show. "
     "For those pages sidebar would also be shown only on the first visit."]

    [subheader "Top 10k domains are ignored"]
    [:p "The above doesn't apply to the top 10k popular domains: "
     "only those pages that have their own mentions will pop up the sidebar."]

    [subheader "Ampie context on youtube"]
    [:p "Now you will see ampie context below youtube videos on search page and in recommended videos:"]
    [:p [:img {:src   "assets/images/youtube-tags.png"
               :style {:width "279px"}}]]

    [:p.pt-4 "Tell me what you think (through the form at the bottom of the sidebar or any other way)!"]

    [:p.pt-2 "& "
     [:a.text-underline (b/ahref-opts "https://twitter.com/posobin") "posobin"] " &"]]])

(defn ^:dev/after-load init []
  (rdom/render [update-page]
    (. js/document getElementById "update-content")))
