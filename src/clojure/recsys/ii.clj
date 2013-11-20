(ns recsys.ii
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [parenskit (vector :as lkv) (core :as lkc)]
            [esfj.provider :refer [defprovider]])
  (:import [java.util Map Set]
           [com.google.common.collect Maps Sets]
           [org.grouplens.lenskit GlobalItemScorer ItemScorer]
           [org.grouplens.lenskit.data.dao EventDAO ItemDAO UserDAO]
           [org.grouplens.lenskit.knn NeighborhoodSize]
           [org.grouplens.lenskit.scored ScoredId]
           [recsys.dao ItemTitleDAO MOOCRatingDAO MOOCItemDAO MOOCUserDAO]
           [recsys.dao RatingFile TitleFile UserFile]
           [recsys.ii SimpleItemItemScorer SimpleGlobalItemScorer]))

(set! *warn-on-reflection* true)

(defn configure-recommender
  "Create the LensKit recommender configuration."
  []
  (doto (lkc/config)
    (-> (.bind EventDAO) (.to MOOCRatingDAO))
    (-> (.set RatingFile) (.to (io/file "data/ratings.csv")))
    (-> (.bind ItemDAO) (.to MOOCItemDAO))
    (-> (.set TitleFile) (.to (io/file "data/movie-titles.csv")))
    (-> (.bind UserDAO) (.to MOOCUserDAO))
    (-> (.set UserFile) (.to (io/file "data/users.csv")))
    (-> (.bind ItemScorer) (.to SimpleItemItemScorer))
    (-> (.bind GlobalItemScorer) (.to SimpleGlobalItemScorer))
    (-> (.set NeighborhoodSize) (.to (Long/valueOf 20)))))

(defn parse-args
  "Parse the command-line arguments in sequence `args`.  Use Java collections
to keep output order consistent with Java implementation."
  [args]
  (log/info "parsing" (count args) "command line arguments")
  (reduce (fn [^Map to-score arg]
            (let [[uid iid] (map #(Long/parseLong %) (str/split arg #":"))]
              (when-not (.containsKey to-score uid)
                (.put to-score uid (Sets/newHashSet)))
              (-> to-score ^Set (.get uid) (.add iid))
              to-score))
          (Maps/newHashMap) args))

(defn -main
  "Main entry point to the program."
  [& args]
  (let [[to-score basket]
        , (cond
           (and (= 1 (count args) (= "--all" (first args))))
           , (log/info "scoring for all users")
           (and (pos? (count args)) (= "--basket" (first args)))
           , [nil (reduce (fn [^Set s arg] (.add s (Long/parseLong arg)))
                          (Sets/newHashSet) (rest args))]
           :else
           , [(parse-args args)])
        config (configure-recommender)
        rec (lkc/rec-build config)
        tdao (lkc/rec-get rec ItemTitleDAO)]
    (if basket
      (let [grec (.getGlobalItemRecommender rec)
            _ (log/info "printing items similar to" basket)
            items (.globalRecommend grec ^Set basket 5)]
        (doseq [^ScoredId item items, :let [id (.getId item)]]
          (printf "%d,%.4f,%s\n" id (.getScore item) (.getItemTitle tdao id))))
      (let [scorer (.getItemScorer rec)
            to-score (or to-score
                         (let [_ (log/debug "loading user/item sets")
                               udao (lkc/rec-get rec UserDAO)
                               _ (when-not udao
                                   (log/error "no user DAO")
                                   (System/exit 2))]
                           (reduce (fn [^Map m user]
                                     (.put m user (.getItemIds tdao)))
                                   (Maps/newHashMap) (.getUserIds udao))))]
        (log/info "scoring for" (count to-score) "users")
        (doseq [[user items] to-score
                :let [_ (log/info "scoring" (count items) "items for user" user)
                      scores (.score scorer ^Long user ^Set items)]
                ^long item items
                :let [score (if-not (.containsKey scores item)
                              "NA"
                              (format "%.4f" (get scores item)))
                      title (.getItemTitle tdao item)]]
          (printf "%d,%d,%s,%s\n" user item score title))))))
