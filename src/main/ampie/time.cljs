(ns ampie.time
  (:require [goog.string :as gstring]
            [goog.string.format]))

(defn timestamp->date [timestamp]
  (let [date  (js/Date. timestamp)
        day   (.getDate date)
        month (.getMonth date)
        month-name
        (get ["January" "February" "March" "April" "May" "June" "July" "August"
              "September" "October" "November" "December"]
          month)
        year  (.getFullYear date)]
    (gstring/format "%s %d, %d" month-name day year)))

(defn timestamp->time [timestamp]
  (let [date    (js/Date. timestamp)
        hours   (.getHours date)
        minutes (.getMinutes date)]
    (gstring/format "%02d:%02d" hours minutes)))
