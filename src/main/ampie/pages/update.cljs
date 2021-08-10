(ns ampie.pages.update
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [taoensso.timbre :as log]
            [ampie.components.visit :as components.visit]
            [ampie.components.basics :as b]
            ["webextension-polyfill" :as browser]))

(def state (r/atom {}))

(defn subheader [text]
  [:h3.pt-4.pb-2.text-2xl text])

(defn highlight [text bad?]
  [:span.rounded.pl-0dot5.pr-0dot5
   {:class (if bad? :bg-indigo-200 :bg-green-300)}
   text])

(defn update-page []
  [:div.font-sans.text-lg.leading-snug.ml-auto.mr-auto.w-fit-content.p-4.rounded-xl.shadow-xl.m-4.border.relative
   [:h1.text-2xl.font-italic.pb-3 "Ampie updated"]
   [:div.max-w-md
    [:p "Updates to ampie, "
     [highlight "with privacy implications for you." true]]

    [subheader "Sidebar"]
    [:div
     [:p "Ampie now shows a sidebar on every page it has context for. "
      [highlight
       "Every URL you visit will be sent to ampie's server" true]
      " to check the context. "
      "You can disable it on some domains in settings "
      [:span.tracking-tightest "——————————→"]]
     [:div.absolute.-right-4.top-0
      [:div.absolute.right-0.top-0.transform.translate-x-full
       [:img.border.rounded-md.shadow.max-w-none.transform.scale-75.origin-top-left
        {:src (.. browser -runtime (getURL "assets/images/settings-menu.png"))}]]]]
    [:p.pt-2
     [highlight "In return sidebar becomes more useful." false]
     " I encourage you to try it out on some blog posts you like first. "
     "Please reach out if you stop using ampie because of this."]

    [subheader "Bye badges =("]
    [:p "Many people told me how distracting badges can be."]
    [:p "Not anymore."]

    [subheader "Ampie in search results"]
    [:p "No more ampie search results on google and DDG. "]
    [:p "But now you will see "
     [highlight "page context previews in search results" false]
     " for google and ddg:"]
    [:img.border.rounded-md.mt-3.in-search-results-half-width
     {:src (.. browser -runtime (getURL "assets/images/in-search-results.png"))}]
    [:p.pt-3 "This requires " [highlight "sending every search result URL to ampie's server" true]
     ". Again, please tell me if you disable ampie because of this!"]

    [:p.pt-6 "Overall, these changes aim to "
     [highlight "simplify ampie and focus on what it does well." false]
     " "
     "I have more ideas for MVPs that build on what I already have, "
     "and will iterate until people start using one of them."]
    [:p.pt-4 "Tell me what you think!"]

    [:p.pt-2 [:a.text-underline (b/ahref-opts "https://twitter.com/posobin") "@posobin"]]]])

(defn ^:dev/after-load init []
  (rdom/render [update-page]
    (. js/document getElementById "update-content")))
