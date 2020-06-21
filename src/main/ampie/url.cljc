(ns ampie.url
  (:require [taoensso.timbre :as log]
            [clojure.string :as string]))

(def prefix-junk #"^https?://|(?<=^|//)www\.")
(def anchor-junk #"#([^!/].*)?$")
(def path-suffix-junk #"(/|/index\.html?|/default\.aspx?)$")
(def allowed-query-param-keys #{"q" "id" "item-id" "item" "query" "search"
                                "itemid" "objectid" "postid"})

(defn query-param-allowed?
  "Takes a key=value string and returns if it is allowed"
  [query-param]
  (let [key (first (string/split query-param #"="))]
    (or (= (count key) 1)
      (contains? allowed-query-param-keys key))))

(defn remove-junk [str junk-regex]
  (string/replace str junk-regex ""))

(defn normalize-custom-url
  [url]
  (cond
    ;; Remove the product name from the amazon url
    (string/starts-with? url "amazon.")
    (-> url
      (string/replace #"^amazon\..{1,5}(/[^/]*)?/(gp/product|dp)/" "amazon.com/dp/")
      (string/replace #"/[^/]*=[^/]*$" ""))

    ;; Youtube url shortener
    (string/starts-with? url "youtu.be")
    (-> url
      (string/replace #"youtu\.be/" "youtube.com/watch?v="))

    :else
    url))

(defn should-keep-query? [main-part]
  (cond
    (re-matches #"^twitter.com/.*/status/.*" main-part)
    false

    :else
    true))

(defn normalize
  "Takes a url and returns its normalized form."
  [url]
  (let [lower-case             (-> url string/trim string/lower-case)
        no-junk                (-> lower-case
                                 (remove-junk prefix-junk)
                                 (remove-junk anchor-junk))
        [main-part query-part] (string/split no-junk #"\?" 2)
        no-junk-path           (-> main-part
                                 (remove-junk path-suffix-junk)
                                 normalize-custom-url)]
    (if (or (empty? query-part)
          (not (should-keep-query? main-part)))
      no-junk-path
      (let [query-params          (string/split query-part #"&")
            sorted-allowed-params (->> query-params
                                    (filter query-param-allowed?)
                                    sort)
            sorted-allowed-query  (string/join "&" sorted-allowed-params)]
        (if (empty? sorted-allowed-query)
          no-junk-path
          (str no-junk-path "?" sorted-allowed-query))))))

(defn get-domain [url]
  (or (clojure.string/replace
        url #"^[^/]*//+(www\.)?|(/|\?|#).*$" "")
    url))

(defn get-top-domain [url]
  (let [domain (get-domain url)]
    (->>
      (clojure.string/split domain #"\.")
      (take-last 2)
      (clojure.string/join "."))))

(defn get-domain-normalized [normalized-url]
  (first (clojure.string/split normalized-url #"/")))

(defn get-path-normalized [normalized-url]
  (->> (clojure.string/split normalized-url #"/")
    rest
    (clojure.string/join "/")))

(defn get-prefixes-normalized [normalized-url]
  (let [domain       (get-domain-normalized normalized-url)
        path         (get-path-normalized normalized-url)
        domain-parts (->> (clojure.string/split domain #"\.")
                       reverse
                       (reductions #(str %2 "." %1))
                       rest)
        path-parts   (->> (clojure.string/split path #"/|\?")
                       (reductions #(str %1 "/" %2)))]
    (concat domain-parts (map #(str (last domain-parts) "/" %)
                           path-parts))))

(defn get-parts-normalized
  "Splits the `normalized-url` into parts and returns a vector
  [[idx start end]+], where idx is the index as returned by
  get-prefixes-normalized, start is the start of the corresponding
  part, end is the index of the next character after the end of the
  corresponding part. Result is sorted by `start`."
  [normalized-url]
  (let [parts          (clojure.string/split normalized-url #"/|\?")
        domain-parts   (clojure.string/split (first parts) #"\.")
        top-domain     (clojure.string/join "." (take-last 2 domain-parts))
        domain-parts   (-> domain-parts pop pop (conj top-domain))
        path-parts     (rest parts)
        domain-starts  (drop-last (reductions #(+ %1 (count %2) 1) 0
                                    domain-parts))
        domain-endings (map #(+ %1 (count %2)) domain-starts domain-parts)
        path-starts    (drop-last (reductions #(+ %1 (count %2) 1)
                                    (inc (last domain-endings)) path-parts))
        path-endings   (map #(+ %1 (count %2)) path-starts path-parts)
        starts         (concat domain-starts path-starts)
        endings        (concat domain-endings path-endings)]
    (map vector (concat (-> (count domain-parts) range reverse)
                  (range (count domain-parts)
                    (+ (count domain-parts)
                      (count path-parts))))
      starts endings)))

#_(defn ss [s]
    (for [[idx start end] (get-parts-normalized s)]
      (println (subs s start end))))
#_(ss "images.google.com/search/query?q=12312")

(defn should-store-url? [url]
  (some? (re-matches #"^https?://.*" url)))
