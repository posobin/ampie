(ns ampie.utils)

(defn assoc-when [coll cond & kvs]
  (if cond
    (apply assoc coll kvs)
    coll))

(def text-node-types
  #{"text" "password" "number" "email" "tel" "url" "search" "date"
    "datetime" "datetime-local" "time" "month" "week"})

(defn is-text-node? [^js el]
  (let [tag-name (.. el -tagName (toLowerCase))]
    (or (= (.-contentEditable el) "true")
      (= tag-name "textarea")
      (and (= tag-name "input")
        (contains? text-node-types (.. el -type (toLowerCase)))))))

(defn is-safari? []
  (boolean
    (re-find
      #"(?i)^((?!chrome|android).)*safari"
      (.-userAgent js/navigator))))
