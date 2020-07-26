(ns ampie.url
  (:require [taoensso.timbre :as log]
            [clojure.string :as string]))

(def prefix-junk #"^(\w{1,6}://)?(www\.)?")
(def anchor-junk #"#([^!/].*)?$")
(def path-suffix-junk #"(/|index\.html?|default\.aspx?)$")
(def allowed-query-param-keys #{"q" "id" "item-id" "item" "itemid" "query"
                                "search" "objectid" "post" "postid" "node" "nodeid" "site"
                                "list" "object" "story" "bug" "doi" "itemname" "which" "key"
                                "topic"})
(def domains-with-kept-params #{"wiki.c2.com"})

(defn query-param-allowed?
  "Takes a key=value string and returns if it is allowed"
  [query-param main-part]
  (or (contains? domains-with-kept-params
        (first (string/split main-part #"/" 2)))
    (let [;; or is needed because (split "=" #"=") evils to []
          key (or (first (string/split query-param #"=")) "")]
      (or (= (count key) 1)
        (contains? allowed-query-param-keys (string/lower-case key))))))

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

    ;; Youtube url shortened
    (string/starts-with? url "youtu.be/")
    (-> url
      (string/replace #"youtu\.be/" "youtube.com/watch?v="))

    (string/starts-with? url "web.archive.org/web/")
    (-> url
      (string/replace #"web.archive.org/web/\d+/(\w{1,6}://?)?(www\.)?" ""))

    :else
    url))

(defn should-keep-query? [main-part]
  (cond
    (re-matches #"^twitter.com/[^\/]*/status/[^\/]*" main-part)
    false

    :else
    true))

(defn add-trailing-slash [main-part]
  (if (re-matches #".*[^/]/.*" main-part)
    main-part
    (str main-part "/")))

(defn reverse-domain-part [domain]
  (string/join "." (-> domain (string/split #"\.") reverse)))

(defn reverse-lower-domain [normalized-url]
  (let [[domain-part path-part] (string/split normalized-url #"/" 2)
        with-reversed-domain    (str (-> domain-part
                                       reverse-domain-part
                                       string/lower-case)
                                  (when path-part "/") path-part)]
    with-reversed-domain))

(defn remove-anchor [url]
  (string/replace url #"#.*$" ""))

(defn normalize
  "Takes a url and returns its normalized form."
  [url]
  (let [no-junk                (-> (string/trim url)
                                 (remove-junk prefix-junk)
                                 (remove-junk anchor-junk))
        [main-part query-part] (string/split no-junk #"\?" 2)
        no-junk-path           (-> main-part
                                 (remove-junk path-suffix-junk)
                                 normalize-custom-url
                                 add-trailing-slash)
        with-reversed-domain   (reverse-lower-domain no-junk-path)]
    (if (or (empty? query-part)
          (not (should-keep-query? main-part)))
      with-reversed-domain
      (let [query-params          (string/split query-part #"&")
            sorted-allowed-params (->> query-params
                                    (filter #(query-param-allowed? % main-part))
                                    sort)
            sorted-allowed-query  (string/join "&" sorted-allowed-params)]
        (if (empty? sorted-allowed-query)
          with-reversed-domain
          (str with-reversed-domain "?" sorted-allowed-query))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Parts for the extension

(defn get-domain [url]
  (or (clojure.string/replace
        url #"^[^/]*//+(www\.)?|(/|\?|#).*$" "")
    url))

(defn get-domain-normalized [normalized-url]
  (first (clojure.string/split normalized-url #"/")))

(defn get-top-domain-normalized [normalized-url]
  (let [domain (get-domain-normalized normalized-url)]
    (->>
      (clojure.string/split domain #"\.")
      (take 2)
      (clojure.string/join "."))))

(defn get-path-normalized [normalized-url]
  (->> (clojure.string/split normalized-url #"/")
    rest
    (clojure.string/join "/")))

(defn get-prefixes-normalized [normalized-url]
  (let [domain       (get-domain-normalized normalized-url)
        path         (get-path-normalized normalized-url)
        domain-parts (->> (clojure.string/split domain #"\.")
                       (reductions #(str %1 "." %2)))
        path-parts   (when (seq path)
                       ;; Needed to deal with a trailing slash after the domain
                       (->> (clojure.string/split path #"/|\?")
                         (reductions #(str %1 "/" %2))))]
    (concat domain-parts (map #(str domain "/" %) path-parts))))

(defn get-parts-normalized
  "Splits the `normalized-url` into parts and returns a vector
  [[idx start end]+], where idx is the index as returned by
  get-prefixes-normalized, start is the start of the corresponding
  part, end is the index of the next character after the end of the
  corresponding part. Result is sorted by `start`."
  [normalized-url]
  (let [parts          (clojure.string/split normalized-url #"/|\?")
        domain-parts   (clojure.string/split (first parts) #"\.")
        path-parts     (rest parts)
        domain-starts  (drop-last (reductions #(+ %1 (count %2) 1) 0
                                    domain-parts))
        domain-endings (map #(+ %1 (count %2)) domain-starts domain-parts)
        path-starts    (drop-last (reductions #(+ %1 (count %2) 1)
                                    (inc (last domain-endings)) path-parts))
        path-endings   (map #(+ %1 (count %2)) path-starts path-parts)
        starts         (concat domain-starts path-starts)
        endings        (concat domain-endings path-endings)]
    (map vector (concat (-> (count domain-parts) range)
                  (range (count domain-parts)
                    (+ (count domain-parts)
                      (count path-parts))))
      starts endings)))

(defn should-store-url? [url]
  (some? (re-matches #"^https?://.*" url)))
