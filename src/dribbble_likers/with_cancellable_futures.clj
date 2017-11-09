(ns dribbble-likers.with-cancellable-futures
  (:require [clojure.tools.macro])
  (:require [clojure.core.async :as async]))

(defmacro cancellable [arg]
  `(do
     (defn just-do-it# []
       (let [ ~'future# ~arg# ]
         (swap!
           ~'current-futures
           conj
           { :restart-fn just-do-it#
             :future future# })
         result#))
     (just-do-it#)))

(defmacro
  "Create `current-futures` atom"
  with-cancellable [& args]
  `(let [ ~'current-futures
          (atom [])
          ~'cancel-current-futures
          (fn []
            (let [continuations#
                   (doall
                     (remove nil?
                       (map
                         #(do (future-cancel (:future %)) (:continuation %))
                         @~'current-futures)))]
              (reset! ~'current-futures [])
              (defn continue-execution# []
                (doall (map #(apply % nil) continuations#)))
              continue-execution#)) ]
     ~@args ))

(def a (promise))

(add-watch a :watcher (fn[key ref old new] (print key ref old new)))
