(ns ampie.content-script.sidebar.sticky-controller
  (:require [reagent.core :as r]
            ["react" :as react]))

(defonce sticky-context (react/createContext "sticky-controller-context"))

(def Provider (.-Provider sticky-context))
(def Consumer (.-Consumer sticky-context))

(defn- els->top-els [sorted-els scroll-top]
  (loop [[el & rest]   sorted-els
         height-so-far 0
         result        []]
    (if (and el (< (.-offsetTop el) (+ scroll-top height-so-far)))
      (recur rest
        (+ height-so-far (.-offsetHeight el))
        (conj result el))
      result)))

(defn- els->bottom-els [reverse-sorted-els scroll-bottom]
  (loop [[el & rest]   reverse-sorted-els
         height-so-far 0
         result        []]
    (if (and el (> (+ (.-offsetTop el) (.-offsetHeight el))
                  (- scroll-bottom height-so-far)))
      (recur rest
        (+ height-so-far (.-offsetHeight el))
        (conj result el))
      (rseq result))))

(defn sticky-controller []
  (let [el->render-fn   (atom {})
        top-elements    (r/atom [])
        bottom-elements (r/atom [])
        on-scroll
        (fn [container-el]
          (let [sorted-els (->> (map first @el->render-fn)
                             (sort-by #(.-offsetTop %))
                             vec)
                new-top    (els->top-els sorted-els (.-scrollTop container-el))
                new-bottom (els->bottom-els (rseq sorted-els)
                             (+ (.-scrollTop container-el)
                               (.-offsetHeight container-el)))]
            (reset! top-elements new-top)
            (reset! bottom-elements new-bottom)
            (js/console.log new-top new-bottom)))
        add-sticky-element
        (fn [el render-fn]
          (when el
            (swap! el->render-fn assoc el render-fn)))]
    {:on-scroll on-scroll
     :render-context-provider
     (fn [scroll-container]
       (let [shared-classes    [:absolute :left-0 :right-0 :flex :flex-col :pl-2]
             render-for-sticky (fn [el]
                                 [:div {:on-click #(.scrollIntoView el)}
                                  [(@el->render-fn el)]])]
         [:> Provider {:value add-sticky-element}
          (into [:div.top-0 {:class shared-classes}]
            (map render-for-sticky @top-elements))
          (into [:div.bottom-0 {:class shared-classes}]
            (map render-for-sticky @bottom-elements))
          scroll-container]))}))

(defn sticky-element [& children]
  [:> Consumer {}
   (fn [add-sticky-element]
     (r/as-element
       (into
         [:div {:ref #(add-sticky-element %
                        (fn [] (into [:div.bg-white.width-full] children)))}]
         children)))])
