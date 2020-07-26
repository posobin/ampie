(ns ampie.background.link-cache-sync
  (:require [ampie.background.backend :as backend]
            [taoensso.timbre :as log]
            [mount.core :refer [defstate]]))

(defstate link-cache-sync
  :start (letfn [(run-cache-check [time]
                   (when @@link-cache-sync (js/clearTimeout @@link-cache-sync))
                   (log/info "Running cache check...")
                   (-> (backend/check-and-update-link-caches)
                     (.finally
                       (fn [_]
                         (log/info "Cache check complete")
                         (let [timeout (js/setTimeout run-cache-check time time)]
                           (reset! @link-cache-sync timeout))))))]
           (when-not false ;;goog.DEBUG
             (js/setTimeout
               (fn [] (backend/on-logged-in :link-cache-sync
                        #(run-cache-check (* 30 60 1000))))
               5000))
           (atom nil))
  :stop (do (when @@link-cache-sync
              (js/clearTimeout @@link-cache-sync)
              (reset! @link-cache-sync nil))
            (backend/remove-on-logged-in :link-cache-sync)))
