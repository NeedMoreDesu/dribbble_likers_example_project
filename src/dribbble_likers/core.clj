(ns dribbble-likers.core
  (:require [clj-http.client :as client])
  (:require [dribbble-likers.with-cancellable-futures :refer :all]))

(def auth-token "db68dbc3fb1a6a902cce0cda829e35116c887d6a33a387682c93ae74c6f85275")
(def default-options { :oauth-token auth-token
                       :as :json })
(defn url-followers [user]
  (str "https://api.dribbble.com/v1/users/" user "/followers"))
(defn url-shots [user]
  (str "https://api.dribbble.com/v1/users/" user "/shots"))

(with-cancellable-futures
  (letfn [(parse [response]
            (if (= (-> response :status) 200)
              (->> response
                :body
                (map :follower)
                (map :username))))]

    (defn get-followers [user]
      (cancellable
        (future
          (parse (client/get (url-followers user) default-options))))))

  (letfn [(parse [response]
            (if (= (-> response :status) 200)
              (->> response
                :body
                (map :likes_count)
                (apply +))))]

    (defn get-likes [user]
      (cancellable
        (future
          (parse (client/get (url-shots user) default-options))))))

  (defn get-followers-likes [user]
    (let [followers (get-followers user)] ; it's promise at this point
      (cancellable
        (future
          (map (fn [user]
                 [user (get-likes user)])
            @followers)))))

  (defn get-top10 [user]
    (let [likes (get-followers-likes user)]
      (cancellable
        (future
          (let [likes (doall (map
                               (fn [[user likes]] { :user user :likes @likes })
                               @likes))]
            (take 10
              (sort
                (fn [left right]
                  (compare
                    [(:likes right) (:user right)]
                    [(:likes left) (:user left)]))
                likes)))))))

  (def stop-execution cancel-current-futures))

;; @(get-top10 "simplebits") ; => ({:user "johannalundgren", :likes 482} {:user "Peffy", :likes 465} {:user "mhmichelsen", :likes 249} {:user "zhangwanjun", :likes 0} {:user "szcdesign", :likes 0} {:user "suzhiwei", :likes 0} {:user "leo4design", :likes 0} {:user "dubstepmusicmaker", :likes 0} {:user "PKNarwade", :likes 0} {:user "LarryLugsten", :likes 0})

(defn print-timestamp [message]
  (println message " : "(str (java.time.LocalDateTime/now))))

(do
  (future @(get-top10 "simplebits") (println "I'm done"))
  ((stop-execution))
  ((stop-execution))
  )
