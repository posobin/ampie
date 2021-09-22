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
   {:class (if bad? :bg-indigo-200 :bg-yellow-200)}
   text])

(defn update-page []
  [:div.font-sans.text-lg.leading-snug.ml-auto.mr-auto.w-fit-content.p-4.rounded-xl.shadow-xl.m-4.border.relative
   [:h1.text-2xl.font-italic.pb-3 "Ampie updated"]
   [:div.max-w-md
    [:p "Hello!"]
    [subheader "Sidebar opens twice per domain"]
    [:p "Previously, if a domain had interesting links, sidebar would show up on every page. "
     "Now it shows up " [highlight "twice per domain" false]
     ", except for pages that have their own context to show. "
     "For those pages sidebar would also be shown only on the first two visits. "
     "If you still want to bring the sidebar up, press Alt-Alt."]

    [subheader "Simpler close/open shortcut"]
    [:p "Now you can "
     [highlight "press Alt-Alt" false]
     " to close/open the sidebar, instead of Alt-Shift - Alt-Shift."]

    [subheader "Feedback form"]
    [:p "If you want to give any thoughts about ampie (or anything else) "
     "you can "
     [highlight "scroll to the bottom of the sidebar" false]
     " and easily reach me. "
     "Looking forward to hearing from you =)"]

    [:p.pt-4 "Tell me what you think (through the form or any other way)!"]

    [:p.pt-2 "& "
     [:a.text-underline (b/ahref-opts "https://twitter.com/posobin") "posobin"] " &"]]])

(defn ^:dev/after-load init []
  (rdom/render [update-page]
    (. js/document getElementById "update-content")))
