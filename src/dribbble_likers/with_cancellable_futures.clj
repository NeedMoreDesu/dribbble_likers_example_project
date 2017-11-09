(ns dribbble-likers.with-cancellable-futures
  (:require [clojure.tools.macro])
  (:require [clojure.core.async :as async]))

(defmacro with-cancellable-futures [& args]
  `(let [ ~'current-futures (ref [])
          ~'cancel-current-futures (fn []
                                     (dosync
                                       (let [continuations#
                                              (doall
                                                (remove nil?
                                                  (map
                                                    #(do (future-cancel (:future %))
                                                       (if (not (realized? (:promise %)))
                                                         (:continuation %)))
                                                    @~'current-futures)))]
                                         ;; (alter
                                         ;;   ~'current-futures
                                         ;;   #(filter
                                         ;;      (fn [arg#]
                                         ;;        (or
                                         ;;          (not (future-cancelled? (:future arg#)))
                                         ;;          (not (realized? (:promise arg#)))))
                                         ;;      %))
                                         (defn continue-execution# []
                                           (doall (map #(apply % nil) continuations#)))
                                         continue-execution#))) ]
     (clojure.tools.macro/macrolet [(~'cancellable [arg#]
                                      `(let [ ~'result# (promise) ]
                                         (defn ~'continue# []
                                           (if (realized? ~'result#)
                                             ~'result#
                                             (let [ ~'future# ~arg# ]
                                               (dosync
                                                 (alter
                                                   ~'~'current-futures
                                                   conj
                                                   { :continuation ~'continue#
                                                     :future ~'future#
                                                     :promise ~'result# }))
                                               (async/thread
                                                 (try
                                                   (let [ ~'val# @~'future# ]
                                                     (deliver ~'result# ~'val#))
                                                   (dosync
                                                     (alter
                                                       ~'~'current-futures
                                                       (fn [~'futures#]
                                                         (remove
                                                           (fn [~'arg#] (= (:future ~'arg#) ~'future#))
                                                           ~'futures#))))
                                                   (catch Exception ~'e# nil)))
                                               ~'result#)))
                                         (~'continue#)))]
       ~@args)))
