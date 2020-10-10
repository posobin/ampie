(ns ampie.macros)

(defmacro |vv [& forms]
  `(->> ~@(reverse forms)))
