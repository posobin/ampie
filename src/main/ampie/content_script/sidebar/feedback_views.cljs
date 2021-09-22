(ns ampie.content-script.sidebar.feedback-views
  (:require [reagent.core :as r]
            [clojure.string :as str]
            [ampie.content-script.sidebar.feedback :as feedback]))

(defn highlight [text bad?]
  [:span.rounded.pl-0dot5.pr-0dot5.bg-yellow-200 text])

(defn feedback-form []
  (r/with-let [contents (r/atom "")
               status (r/atom nil)
               error-message (r/atom nil)]
    [:div
     [:div.text-xl.pb-1 "Ideas for ampie"]
     [:div.flex.flex-col.gap-1 {:class ["w-3/4"]}
      [:div "Put any ideas/bug reports/questions here, I'll get an email and respond to you ASAP. "
       [highlight "Or just say hi =)"]
       " If you want me to know this page's URL, make sure to add it as well."]
      [:textarea.border.focus:outline-none.focus:border-blue-300.h-auto.rounded-md.w-full.resize-none.p-2
       {:rows      5
        :disabled  (= @status :sending)
        :value     @contents
        :on-change #(reset! contents (.. % -target -value))}]
      (when (= @status :success)
        [:div.p-1.border.border-green-300.rounded-md.self-start
         "Feedback sent! Thank you so much, I'll be in touch =)"])
      (when (= @status :error)
        [:div.p-1.border.border-red-300.rounded-md.self-start
         "Couldn't send feedback"
         (some->> @error-message (str ": "))])
      (let [disabled (or (= @status :sending) (str/blank? @contents))]
        [:button.p-4.pt-1.pb-1.rounded-md.self-end.border
         {:class    ["focus:outline-none focus:border-blue-300"
                     (if disabled
                       "bg-transparent text-gray-400 cursor-default"
                       "bg-yellow-100 hover:bg-yellow-200")]
          :disabled disabled
          :on-click (fn [] (reset! status :sending)
                      (.then (feedback/send-feedback! @contents)
                        (fn [{:keys [fail message] :as result }]
                          (js/console.log result)
                          (reset! status (if fail :error :success))
                          (reset! error-message (when fail message))
                          (when-not fail
                            (reset! contents "")))))}
         "Send!"])]]))
