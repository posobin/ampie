(ns ampie.content-script.sidebar.sticky-manager
  (:require [reagent.core :as r]
            [reagent.ratom :as ratom]
            ["react" :as react]))

(defonce sticky-context (react/createContext "sticky-controller-context"))

(def Provider (.-Provider sticky-context))
(def Consumer (.-Consumer sticky-context))

(defn one-side-manager [top scroll-into-view]
  (let [el-id->render-info (r/atom {})
        sorted-els         (ratom/reaction
                             (->> @el-id->render-info
                               (sort-by (comp #(.-offsetTop %) :element val))
                               vec))
        height             (r/atom 0)
        scroll-top         (r/atom 0)
        container-height   (r/atom 0)
        edge               (ratom/reaction
                             (+ @scroll-top
                               (if top
                                 @height
                                 (- @container-height @height))))
        rendered-refs      (r/atom {})
        elements
        (ratom/reaction
          (if top
            (filterv
              (fn [[id {:keys [element]}]]
                (< (.-offsetTop element)
                  (- @edge
                    (or (some-> (@rendered-refs id) (.-offsetHeight)) 0))))
              @sorted-els)
            (filterv
              (fn [[id {:keys [element]}]]
                (> (+ (.-offsetTop element) (.-offsetHeight element))
                  (+ @edge
                    (or (some-> (@rendered-refs id) (.-offsetHeight)) 0))))
              @sorted-els)))
        resize-observer
        (js/ResizeObserver.
          (fn [[entry]] (reset! height (.. entry -target -offsetHeight))))
        on-scroll
        (fn [container-el]
          (reset! scroll-top (.-scrollTop container-el))
          (reset! container-height (.-offsetHeight container-el)))
        add-sticky-element
        (fn [id el render-fn]
          (swap! el-id->render-info assoc id
            {:element   el
             :render-fn render-fn}))
        remove-sticky-element
        (fn [id] (swap! el-id->render-info dissoc id))
        id->scroll-offset
        (fn [id]
          (if-let [ref (@rendered-refs id)]
            (.-offsetTop ref)
            @height))]
    {:on-scroll             on-scroll
     :add-sticky-element    add-sticky-element
     :remove-sticky-element remove-sticky-element
     :id->scroll-offset     id->scroll-offset
     :render-fn
     (fn []
       (into [:div {:class (conj [:absolute :left-0 :right-0 :flex :flex-col]
                             (if top :top-0 :bottom-0))
                    :ref   (fn [el] (when el (.observe resize-observer el)))}]
         (for [[id {:keys [element render-fn]}] @elements]
           [:div {:on-click #(scroll-into-view id element id->scroll-offset)
                  :class    (if top :border-b :border-t)
                  :role     :button
                  :ref      (fn [sticky-el]
                              (if sticky-el
                                (swap! rendered-refs assoc id sticky-el)
                                (swap! rendered-refs dissoc id)))}
            [render-fn]])))}))

(defn sticky-manager []
  (let [container (atom nil)
        top
        (one-side-manager true
          (fn [id el id->scroll-offset]
            (when @container
              (set! (.-scrollTop @container)
                (- (.-offsetTop el) (id->scroll-offset id))))))
        scroll-into-view-external
        (fn [el]
          (when @container
            (set! (.-scrollTop @container)
              ;; Calling id->scroll-offset with nil will just return the height
              ;; of the top sticky panel.
              (- (.-offsetTop el) ((:id->scroll-offset top) nil)))))
        bottom
        (one-side-manager false
          (fn [id el _]
            (when @container
              (set! (.-scrollTop @container)
                (- (.-offsetTop el) ((:id->scroll-offset top) id))))))
        add-sticky-element
        (fn [id el render-fn]
          ((:add-sticky-element top) id el render-fn)
          ((:add-sticky-element bottom) id el render-fn))
        remove-sticky-element
        (fn [id]
          ((:remove-sticky-element top) id)
          ((:remove-sticky-element bottom) id))]
    {:on-scroll        (fn [el] ((:on-scroll top) el) ((:on-scroll bottom) el))
     :on-resize        (fn [el] ((:on-scroll top) el) ((:on-scroll bottom) el))
     :container-ref    (fn [container-el] (reset! container container-el))
     :scroll-into-view scroll-into-view-external
     :render-context-provider
     (fn [scroll-container]
       [:> Provider {:value (clj->js {:add-sticky-element    add-sticky-element
                                      :remove-sticky-element remove-sticky-element})}
        [(:render-fn top)]
        [(:render-fn bottom)]
        scroll-container])}))

(defn generate-random-id []
  (.. js/Math random (toString 36) (substr 2 9)))

(defn sticky-element [in-flow in-sticky]
  (let [id (generate-random-id)]
    [:> Consumer {}
     (fn [js-value]
       (let [{:keys [add-sticky-element remove-sticky-element]}
             (js->clj js-value :keywordize-keys true)]
         (r/as-element
           [:div
            {:ref
             (fn [el]
               (if el
                 (add-sticky-element id el
                   (fn [] [:div.bg-white.width-full (or in-sticky in-flow)]))
                 (remove-sticky-element id)))}
            in-flow])))]))
