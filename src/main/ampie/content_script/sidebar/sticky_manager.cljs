(ns ampie.content-script.sidebar.sticky-manager
  (:require [reagent.core :as r]
            [reagent.ratom :as ratom]
            ["react" :as react]))

(defonce sticky-context (react/createContext "sticky-controller-context"))

(def Provider (.-Provider sticky-context))
(def Consumer (.-Consumer sticky-context))

(defn one-side-manager [top scroll-into-view]
  (let [el->render-fn    (r/atom {})
        sorted-els       (ratom/reaction
                           (->> (keys @el->render-fn)
                             (sort-by #(.-offsetTop %))
                             vec))
        height           (r/atom 0)
        scroll-top       (r/atom 0)
        container-height (r/atom 0)
        edge             (ratom/reaction
                           (+ @scroll-top
                             (if top
                               @height
                               (- @container-height @height))))
        rendered-refs    (r/atom {})
        elements
        (ratom/reaction
          (if top
            (filterv #(< (.-offsetTop %)
                        (- @edge
                          (or (some-> (@rendered-refs %) (.-offsetHeight)) 0)))
              @sorted-els)
            (filterv #(> (+ (.-offsetTop %) (.-offsetHeight %))
                        (+ @edge
                          (or (some-> (@rendered-refs %) (.-offsetHeight)) 0)))
              @sorted-els)))
        resize-observer
        (js/ResizeObserver.
          (fn [[entry]] (reset! height (.. entry -target -offsetHeight))))
        on-scroll
        (fn [container-el]
          (reset! scroll-top (.-scrollTop container-el))
          (reset! container-height (.-offsetHeight container-el)))
        add-sticky-element
        (fn [el render-fn]
          (when el
            (swap! el->render-fn assoc el render-fn)))
        el->scroll-offset
        (fn [el]
          (if-let [ref (@rendered-refs el)]
            (.-offsetTop ref)
            @height))]
    {:on-scroll          on-scroll
     :add-sticky-element add-sticky-element
     :el->scroll-offset  el->scroll-offset
     :render-fn
     (fn []
       (into [:div {:class (conj [:absolute :left-0 :right-0 :flex :flex-col]
                             (if top :top-0 :bottom-0))
                    :ref   (fn [el] (when el (.observe resize-observer el)))}]
         (for [el @elements]
           [:div {:on-click #(scroll-into-view el el->scroll-offset)
                  :class    (if top :border-b :border-t)
                  :role     :button
                  :ref      (fn [sticky-el]
                              (if sticky-el
                                (swap! rendered-refs assoc el sticky-el)
                                (swap! rendered-refs dissoc el)))}
            [(@el->render-fn el)]])))}))

(defn sticky-manager []
  (let [container (atom nil)
        top
        (one-side-manager true
          (fn [el el->scroll-offset]
            (when @container
              (set! (.-scrollTop @container)
                (- (.-offsetTop el) (el->scroll-offset el))))))
        bottom
        (one-side-manager false
          (fn [el _]
            (when @container
              (set! (.-scrollTop @container)
                (- (.-offsetTop el) ((:el->scroll-offset top) el))))))
        add-sticky-element
        (fn [el render-fn]
          ((:add-sticky-element top) el render-fn)
          ((:add-sticky-element bottom) el render-fn))]
    {:on-scroll     (fn [el] ((:on-scroll top) el) ((:on-scroll bottom) el))
     :on-resize     (fn [el] ((:on-scroll top) el) ((:on-scroll bottom) el))
     :container-ref (fn [container-el] (reset! container container-el))
     :render-context-provider
     (fn [scroll-container]
       [:> Provider {:value add-sticky-element}
        [(:render-fn top)]
        [(:render-fn bottom)]
        scroll-container])}))

(defn sticky-element [in-flow in-sticky]
  [:> Consumer {}
   (fn [add-sticky-element]
     (r/as-element
       [:div {:ref #(add-sticky-element %
                      (fn [] [:div.bg-white.width-full (or in-sticky in-flow)]))}
        in-flow]))])
