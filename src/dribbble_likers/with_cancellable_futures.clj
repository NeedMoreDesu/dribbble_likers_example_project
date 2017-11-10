(ns dribbble-likers.with-cancellable-futures
  (:require [clojure.tools.logging :as log]))

(defn keep-alive-rule [arg]
  (let [future (:future arg)]
    (or
      (= future :cancelled)
      (and
        (future? future)
        (not (future-done? future))))))

(defn values-to-dictionary [values]
  (into {}
    (map
      (fn [arg] [(hash (:restart-fn arg)) arg])
      values)))

(defmacro cancellable [id arg]
  `(letfn [(just-do-it# []
             (let [ future# ~arg ]
               (when @~'is-execution-stopped?
                 (do
                   (future-cancel future#)
                   (log/info "Future stopped from launch: " ~id)))
               (swap!
                 ~'current-futures
                 #(values-to-dictionary (filter keep-alive-rule (vals %))))
               (swap!
                 ~'current-futures
                 assoc
                 (hash just-do-it#)
                 { :restart-fn just-do-it#
                   :future (if (future-cancelled? future#) :cancelled future#)
                   :id ~id })
               future#))
            (~'cleanup-cancellable []
              (let [ key# (hash just-do-it#)
                     future# (get @~'current-futures key#) ]
                (when-not (keep-alive-rule future#)
                  (swap!
                    ~'current-futures
                    dissoc
                    key#))))]
     (just-do-it#)))

(defmacro with-cancellable [& args]
  `(let [ ~'current-futures
          (atom {})
          ~'is-execution-stopped?
          (atom false)
          ~'cleanup-all-cancellable
          (fn []
            (swap!
              ~'current-futures
              #(values-to-dictionary (filter keep-alive-rule (vals %)))))
          ~'stop-execution
          (fn []
            (reset! ~'is-execution-stopped? true)
            (log/info "Execution stopped")
            (swap!
              ~'current-futures
              (fn [futures#]
                (let [ futures#
                       (map
                         (fn [arg#]
                           (if (and
                                 (future? (:future arg#))
                                 (not (future-done? (:future arg#))))
                             (do
                               (future-cancel (:future arg#))
                               (log/info "Future stopped from stop signal: " (:id arg#))
                               (assoc arg# :future :cancelled))
                             arg#))
                         (vals futures#))
                       filtered-futures#
                       (filter keep-alive-rule futures#)
                       result#
                       (values-to-dictionary filtered-futures#) ]
                  result#)))
            nil)
          ~'resume-execution
          (fn []
            (reset! ~'is-execution-stopped? false)
            (log/info "Resuming execution")
            (doall
              (map
                (fn [future#]
                  (when (= (:future future#) :cancelled) ; cancelled by our stop-execution call
                    (apply (:restart-fn future#) nil)
                    (log/info "Future restored: " (:id future#))))
                (vals @~'current-futures)))
            nil) ]
     ~@args ))
