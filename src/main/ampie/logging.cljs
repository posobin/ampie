(ns ampie.logging
  (:require [taoensso.timbre :as log]))

;; Taken from https://github.com/ptaoussanis/timbre/issues/132#issuecomment-180268825

(def devtools-level-to-fn
  {:fatal js/console.error,
   :error js/console.error,
   :warn  js/console.warn,
   :info  js/console.info,
   :debug js/console.debug,
   :trace js/console.trace})

(def devtools-appender
  "Simple js/console appender which avoids pr-str and uses cljs-devtools
  to format output"
  {:enabled?   true
   :async?     false
   :min-level  nil
   :rate-limit nil
   :output-fn  nil
   :fn
   (fn [data]
     (let [{:keys [level ?ns-str vargs_]} data
           vargs                          (list* (str ?ns-str ":") (force vargs_))
           f                              (get devtools-level-to-fn level js/console.log)]
       (f (to-array vargs))))})

#_(when (= "Google Inc." js/navigator.vendor)
    (set! log/*config*
      (-> log/*config*
        (assoc-in [:appenders :console] devtools-appender))))
