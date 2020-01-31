(ns ampie.content)

(defn ^:dev/after-load reloaded []
  (println "The content script was reloaded!"))

(defn init []
  (println "Hello World"))
