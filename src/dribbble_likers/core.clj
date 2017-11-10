(ns dribbble-likers.core
  (:gen-class)
  (:require [clj-http.client :as client])
  (:require [dribbble-likers.with-cancellable-futures :refer :all])
  (:require [clojure.core.async :as async :refer [go <! >!]])
  (:require [clojure.tools.logging :as log]))

(def auth-token "db68dbc3fb1a6a902cce0cda829e35116c887d6a33a387682c93ae74c6f85275")
(def default-options { :oauth-token auth-token
                       :as :json })
(defn url-followers [user]
  (str "https://api.dribbble.com/v1/users/" user "/followers"))
(defn url-shots [user]
  (str "https://api.dribbble.com/v1/users/" user "/shots"))

(with-cancellable
  (defn request [f path options]
    (let [ch (async/chan)]
      (log/info "Start request" path)
      (cancellable
        path
        (f path (assoc options :async? true)
          (fn [success]
            (log/info "Finished request" path)
            (go
              (>! ch success)
              (async/close! ch)
              ;; this isn't mandatory, but it un-reference right away
              (cleanup-cancellable)))
          (fn [error]
            (log/error "Failed request" path)
            (go
              (>! ch error)
              (async/close! ch)
              (cleanup-cancellable)))))
      ch))

  (def stop-all-requests stop-execution)
  (def resume-all-requests resume-execution)
  (def current-futures current-futures))

(swap!
  current-futures
  (fn [futures]
    (let [ futures
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
             futures)
           filtered-futures#
           (filter keep-alive-rule futures) ]
      (set filtered-futures#))))

(letfn [(parse [response]
          (if (= (-> response :status) 200)
            (->> response
              :body
              (map :follower)
              (map :username))))]

  (defn get-followers [user]
    (go (parse (<! (request client/get (url-followers user) default-options))))))

(letfn [(parse [response]
          (if (= (-> response :status) 200)
            (->> response
              :body
              (map :likes_count)
              (apply +))))]

  (defn get-likes [user]
    (go (parse (<! (request client/get (url-shots user) default-options))))))

(defn get-followers-likes [user]
  (go
    (let [ likes-chs (map
                       (fn [user]
                         (go { :user user :likes (<! (get-likes user)) }))
                       (<! (get-followers user)))
           ch-with-likes (async/merge likes-chs)
           result-ch (async/into [] ch-with-likes) ]
      (<! result-ch))))

(defn get-top10 [user]
  (go (let [ likes (<! (get-followers-likes user)) ]
        (take 10
          (sort
            (fn [left right]
              (compare
                [(:likes right) (:user right)]
                [(:likes left) (:user left)]))
            likes)))))

(defn simple-example [username]
  (log/info "Starting requests...")
  (let [ch (go (println (<! (get-top10 username))))]
    (Thread/sleep 800)
    (log/info "... and suddenly stop it for 5 seconds.")
    (stop-all-requests)
    (Thread/sleep 5000)
    (log/info "Resume requests.")
    (resume-all-requests)
    (async/<!! ch)))

(defn -main [& [username]]
  (simple-example (or username "simplebits")))
