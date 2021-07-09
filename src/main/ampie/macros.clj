(ns ampie.macros)

(defmacro |vv [& forms]
  `(->> ~@(reverse forms)))

(defmacro then-fn [promise args & body]
  `(.then (or ~promise (js/Promise.resolve nil)) (fn ~args ~@body)))
