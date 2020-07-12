(ns ampie.time
  (:require [goog.string :as gstring]
            [goog.string.format]))

(defn get-start-of-week [^js date]
  (let [day (.getDay date)]
    (js/Date. (.setDate date (- (.getDate date) (mod (+ day 6) 7))))))

(defn js-date->yyyy-MM-dd [^js date]
  (let [year  (.getFullYear date)
        month (inc (.getMonth date))
        day   (.getDate date)]
    (gstring/format "%d-%02d-%02d" year month day)))

(defn timestamp->date [timestamp]
  (let [current       (js/Date.)
        current-day   (.getDate current)
        current-month (.getMonth current)
        current-year  (.getFullYear current)
        date          (js/Date. timestamp)
        day           (.getDate date)
        month         (.getMonth date)
        month-name
        (get ["January" "February" "March" "April" "May" "June" "July" "August"
              "September" "October" "November" "December"]
          month)
        year          (.getFullYear date)]
    (if (not= current-year year)
      (gstring/format "%s %d, %d" month-name day year)
      (gstring/format "%s %d" month-name day))))

(defn timestamp->time [timestamp]
  (let [date    (js/Date. timestamp)
        hours   (.getHours date)
        minutes (.getMinutes date)]
    (gstring/format "%02d:%02d" hours minutes)))

(defn seconds->str [seconds]
  (let [hours   (quot seconds (* 60 60))
        minutes (quot (mod seconds (* 60 60)) 60)
        seconds (mod seconds 60)]
    (cond (and (pos? hours) (zero? minutes))
          (gstring/format "%dh" hours)
          (pos? hours)
          (gstring/format "%dh %dm" hours minutes)
          (or (> minutes 5) (and (pos? minutes) (zero? seconds)))
          (gstring/format "%dm" minutes)
          (pos? minutes)
          (gstring/format "%dm %ds" minutes seconds)
          :else
          (gstring/format "%ds" seconds))))
