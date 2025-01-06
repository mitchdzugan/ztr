(set! *warn-on-reflection* true)
(ns dz (:require [clojure.core.async :as a :refer [<!! >!]]
                 [ztr.core :as ztr]))

(def *waitrundone (atom (fn [] nil)))
(def *write (atom (fn [_] nil)))

(defn write [str] (@*write str))

(defn cols [& args] (cons 'Cols args))
(defn rows [& args] (cons 'Rows args))
(defn mkup [& args] (cons 'Mkup args))

(defn devrun
  []
  (let [c (a/chan)]
    (let [cdone (a/go (ztr/run c))]
      (reset! *waitrundone (fn [] (<!! cdone))))
    (reset! *write #(do (a/go (>! c %1)) nil))
    nil))

(defn render [el] (write (list 'Render el)))

(defn done []
  (let [waitdone @*waitrundone]
    (write '(Done))
    (reset! *write (fn [_] nil))
    (reset! *waitrundone (fn [] nil))
    (waitdone)))

(defn test-open []
  (devrun)
  (Thread/sleep 3000)
  (done))
