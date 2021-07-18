(ns ampie.macros)

(defmacro |vv [& forms]
  `(->> ~@(reverse forms)))

(defmacro then-fn [promise args & body]
  `(.then (or ~promise (js/Promise.resolve nil)) (fn ~args ~@body)))

(defmacro catch-fn [promise args & body]
  `(.catch (or ~promise (js/Promise.resolve nil)) (fn ~args ~@body)))
