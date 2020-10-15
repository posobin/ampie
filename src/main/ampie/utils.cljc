(ns ampie.utils)

(defn assoc-when [coll cond & kvs]
  (if cond
    (apply assoc coll kvs)
    coll))
