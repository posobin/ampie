(ns ampie.url)

(defn clean-up-params [url]
  (-> url
      ;; Remove any params that are not in the list and more than one letter long
      (clojure.string/replace
        #"((\?)|&)(?!(\w|id|item|query|search|cites)=)[^=&#]*(=[^&?#]*)?(?=$|&|#)" "$2")
      (clojure.string/replace
        #"\?($|#)" "")))

;; TODO: figure out how to clean urls better and what to do with the real url
;; (whether to save it too or not)
(defn clean-up [url]
  (-> url
      clean-up-params
      (clojure.string/replace #"^https?://(www\.)?|#.*$" "")))

(defn get-domain [url]
  (or (clojure.string/replace
        url #"^[^/]*//+(www\.)?|(/|\?|#).*$" "")
      url))

(defn should-store-url? [url]
  (some? (re-matches #"^https?://.*" url)))
