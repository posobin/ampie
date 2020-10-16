(ns ampie.interop
  (:require [clojure.string :as string]
            [clojure.walk :refer [postwalk]])
  (:refer-clojure :rename {clj->js core-clj->js
                           js->clj core-js->clj}
                  :exclude [clj->js js->clj]))

(defn kebab->camel-case [s]
  (let [words (string/split s "-")]
    (string/join (into [(first words)]
                   (map string/capitalize (rest words))))))

(def A-code (.charCodeAt "A" 0))
(def Z-code (.charCodeAt "Z" 0))
(def camel-case->kebab
  (memoize
    (fn camel-case->kebab[s]
      (let [upper-case?
            (map #(<= A-code (.charCodeAt s %) Z-code)
              (range (count s)))
            word-starts
            (concat
              [0]
              (filter identity
                (map #(and (not %1) %2
                        %3)
                  (cons true upper-case?)
                  upper-case?
                  (range)))
              [(count s)])
            words
            (map #(string/lower-case (subs s %1 %2))
              word-starts (rest word-starts))]
        (string/join "-" words)))))

(defn transform-keys [map]
  (letfn [(keyword->kebab [k]
            (if (keyword? k)
              (keyword (camel-case->kebab (name k)))
              k))]
    (postwalk keyword->kebab map)))

(defn js->clj [obj]
  (transform-keys (core-js->clj obj :keywordize-keys true)))

(defn clj->js [obj]
  (core-clj->js obj :keyword-fn (comp kebab->camel-case name)))

(defn name-with-ns [keyword]
  (if (namespace keyword)
    (str (namespace keyword) "/" (name keyword))
    (name keyword)))
