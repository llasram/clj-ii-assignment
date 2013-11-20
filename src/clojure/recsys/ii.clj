(ns recsys.ii
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.core.reducers :as r]
            [clojure.tools.logging :as log]
            [parenskit (vector :as lkv) (core :as lkc)]
            [esfj.provider :refer [defprovider]])
  (:import [java.util Map Set]
           [com.google.common.collect Maps Sets]
           [org.grouplens.lenskit GlobalItemScorer ItemScorer]
           [org.grouplens.lenskit.data.dao
            , EventDAO ItemDAO UserDAO UserEventDAO]
           [org.grouplens.lenskit.data.event Rating]
           [org.grouplens.lenskit.data.history
            , History RatingVectorUserHistorySummarizer UserHistory]
           [org.grouplens.lenskit.knn NeighborhoodSize]
           [org.grouplens.lenskit.scored ScoredId]
           [org.grouplens.lenskit.vectors SparseVector]
           [recsys.dao ItemTitleDAO MOOCRatingDAO MOOCItemDAO MOOCUserDAO]
           [recsys.dao RatingFile TitleFile UserFile]
           [recsys.ii SimpleItemItemModel SimpleItemItemScorer]))

(set! *warn-on-reflection* true)

(defn rvuhs-mrv
  "LOL Java."
  {:tag `SparseVector}
  [event] (RatingVectorUserHistorySummarizer/makeRatingVector event))

(defn item-vectors
  "Generate a map of item to vector of user ratings of that item."
  [^ItemDAO idao ^UserEventDAO uedao]
  (let [items (.getItemIds idao)]
    (with-open [stream (.streamEventsByUser uedao)]
      (->> (reduce (fn [item-data ^UserHistory event]
                     (let [vector (-> event rvuhs-mrv lkv/mvec)]
                       ;; vector is now the user's rating vector
                       ;; TODO Normalize this vector and store the ratings in
                       ;;      the item data as a map of item IDs to a map of
                       ;;      user IDs to normalized ratings
                       ))
                   {} stream)
           (r/map (fn [item item-ratings]
                    [item (lkv/ivec item-ratings)]))
           (into {})))))

(defprovider simple-item-item-model
  "Generate map of item IDs to `ScoreId`s for each other item, holding the other
item ID and similarity."
  ^SimpleItemItemModel [^ItemDAO idao ^UserEventDAO uedao]
  (let [vectors (item-vectors idao uedao)
        items (keys vectors)]
    ;; TODO Compute the similarities between each pair of items
    ;; It will need to be in a map of longs to lists of Scored IDs to store in
    ;; the model
    (SimpleItemItemModel. {})))

;; Needs access to parameter set via NeighborhoodSize annotation, which the esfj
;; library does not yet support specifying.  Instead of this being a Provider,
;; we leave a stub Java class to invoke this as a plain function.
(defn simple-item-item-scorer
  ^ItemScorer [^SimpleItemItemModel model ^UserEventDAO uedao nnbrs]
  (lkc/item-scorer
   (fn [user scores]
     (let [ratings (rvuhs-mrv
                    (or (.getEventsForUser uedao user Rating)
                        (History/forUser user)))]
       (-> (fn [item]
             (let [neighbors (.getNeighbors model item)]
               ;; TODO Score this item and save the score into scores
               0.0))
           (lkv/map-keys! :either scores))))))

(defprovider simple-global-item-scorer
  ^GlobalItemScorer [^SimpleItemItemModel model]
  (lkc/global-item-scorer
   (fn [items scores]
     ;; TODO score items in the domain of scores
     ;; each item's score is the sum of its similarity to each item in items, if
     ;; they are neighbors in the model.
     (lkv/map! (constantly 0.0) scores))))

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
    (-> (.bind SimpleItemItemModel) (.toProvider simple-item-item-model))
    (-> (.bind ItemScorer) (.to SimpleItemItemScorer))
    (-> (.bind GlobalItemScorer) (.toProvider simple-global-item-scorer))
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
           , [nil (reduce (fn [^Set s arg]
                            (doto s (.add (Long/parseLong arg))))
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
        (doseq [[^Long user ^Set items] to-score
                :let [_ (log/info "scoring" (count items) "items for user" user)
                      scores (.score scorer user items)]
                ^long item items
                :let [score (if-not (.containsKey scores item)
                              "NA"
                              (format "%.4f" (.get scores item)))
                      title (.getItemTitle tdao item)]]
          (printf "%d,%d,%s,%s\n" user item score title))))))
