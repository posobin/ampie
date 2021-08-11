(ns ampie.components.basics)

(defn ahref-opts [href]
  {:href                  href
   :target                "_blank"
   :data-ampie-click-info (pr-str {:type :ahref})
   :rel                   "noreferrer noopener"})
