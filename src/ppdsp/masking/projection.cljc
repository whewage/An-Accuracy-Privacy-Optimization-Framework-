(ns ppdsp.masking.projection
  (:require [ppdsp.masking.base :refer [masked-schema-features
                                       mask-record-features
                                       decorate-mask-factory]]
            [ppdsp.utils.random :refer [seeded-rng next-gauss!
                                       next-derived-seed!]]
			      [ppdsp.dataset.base :refer [dataset-record-count]];;waruni						   
            [clojure.core.matrix :as m]
            [clojure.java.io :as io]
            [clojure.core.matrix.linear :as ml]
            [clojure.math.numeric-tower ::as Math])
  (:import [ppdsp.masking.base DatasetFeatureMask]))

;;;;;;;;;;;;
(defn save-data-append-newline [filename data]
  (io/make-parents filename)
  (with-open [w (io/writer filename :append true)]
    (binding [*out* w]
      (println data )))
  data)
;;;;;;;;;;;;;;
(defn make-drifting-projection-mask-factory
  "Creates a dataset-feature-mask that right-multiplies dataset features
  by the given init-matrix, and incrementally updates init-matrix with
  drift-fn! after masking each record."
  [init-matrix drift-fn!]
  (fn [schema-features]
    (let [[matrix-rows matrix-cols] (m/shape init-matrix)
          matrix-atom (atom init-matrix)]
      (when (not-every? #(= :numeric (:options %)) schema-features)
        (throw (IllegalArgumentException. "Some dataset features are not numeric")))
      (when (not= matrix-rows (count schema-features))
        (throw (IllegalArgumentException. "Number of matrix rows and dataset features differ")))
      (reify DatasetFeatureMask  ;;imported 
        (masked-schema-features [this] ;;implement the functions in imported 
          (map #(hash-map :name (str "dim" %)
                          :options :numeric)
               (range matrix-cols)))
        (mask-record-features [this features] ;;implement the functions in imported
          (let [current-matrix @matrix-atom]
            (swap! matrix-atom drift-fn!)
            (when (not= (m/shape current-matrix) (m/shape @matrix-atom))
              (throw (IllegalStateException. "Drifted matrix does not have the same dimensionality as the previous matrix")))
            (m/mmul features current-matrix)))))))

(defn make-static-projection-mask-factory
  "Creates a dataset-feature-mask that right-multiplies dataset features
  by the given matrix."
  [matrix]
  (make-drifting-projection-mask-factory matrix identity))

(defn random-gauss-matrix
  "Generates a vector of vectors where each value is drawn from a
  Gaussian distribution with mean: mu and standard deviation: sigma."
  [[rows cols] mu sigma
   & {:keys [seed]}]
  (let [seed (or seed 1)
        rng (seeded-rng seed)]
    (vec
     (for [_ (range rows)]
       (vec
        (for [_ (range cols)]
          (next-gauss! rng mu sigma)))))))

(defn make-random-projection-mask-factory
  "Creates a dataset-feature-mask that performs a projection with a
  random matrix with the given number of output columns, and values
  drawn from a Gaussian with mean 0 and standard deviation: sigma."
  [output-cols sigma & {:keys [seed drift-fn!]
                        :or {drift-fn! identity}}]
  (fn [schema-features]
    (let [input-cols (count schema-features)
          matrix (random-gauss-matrix [input-cols output-cols] 0 sigma :seed seed)
          drifting-mask-factory (make-drifting-projection-mask-factory matrix drift-fn!)
          normalisation-factor (/ 1 (* (Math/sqrt output-cols) sigma))
          normalised-mask-factory (decorate-mask-factory drifting-mask-factory
                                                         identity
                                                         #(m/mul normalisation-factor %))]
      (normalised-mask-factory schema-features))))

(defn random-orthogonal-matrix
  "Produces a random orthogonal matrix by generating a
  random-gauss-matrix with mean zero and unit variance, and then
  producing an orthogonal 'Q' matrix through QR decomposition.

  This approach was described in: Meng, D., Sivakumar, K., & Kargupta,
  H. (2004, November). Privacy-sensitive Bayesian network parameter
  learning. In Data Mining, 2004. ICDM'04. Fourth IEEE International
  Conference on (pp. 487-490). IEEE.

  NOTE: Because the random matrix generally has full column rank (all
  columns are linearly independent), the matrix is also generally
  orthonormal. See:
  https://en.wikipedia.org/wiki/QR_decomposition#Square_matrix"
  [size & {:keys [seed]}]
  (:Q (ml/qr (random-gauss-matrix [size size] 0 1 :seed seed))))

(defn make-random-orthogonal-mask-factory
  "Creates a dataset-feature-mask that performs a transformation with a
  random orthogonal matrix."
  [& {:keys [seed drift-fn!]
      :or {drift-fn! identity}}]
  (fn [schema-features]
    (let [matrix (random-orthogonal-matrix (count schema-features) :seed seed)
          drifting-mask-factory (make-drifting-projection-mask-factory matrix drift-fn!)]
      (drifting-mask-factory schema-features))))

(defn make-random-projection-with-noise-and-translation-mask-factory-cycles 
   "A mask factory that combines a random projection (to projection-features number of features and with projection-sigma
  used when generating gaussian projection matrix) with  Logistic cumulative Gaussian noise (with cumulative-sigmas*return-value-of-logistic-function as the sigma for each attribute) added to each
  record. 
  If a noise-log-atom is provided, it will be populated with a list of the cumulative-noise values that are added for each record."
  [projection-features projection-sigma  cumulative-noise-sigma translation cycle-size growth-rate-k maximum-fn-value upper-bound lower-bound
   & {:keys [seed noise-log-atom]}]
  (when noise-log-atom
    (reset! noise-log-atom []))
 
  (def counter (atom 0))
  ;;(def logistic-counter (atom (- (* -1 cycle-size) 1 )) ) ;;start logistic counter with - values
  (def logistic-counter (atom (- lower-bound 1 )))
  (fn [schema-features]
    (let [seed (or seed 1)
          rng (seeded-rng seed)
          translation (or translation 0)
          translation-vector (repeat (count schema-features) translation)
          cumulative-noise-vector-atom (atom (m/zero-vector (count schema-features)))
          projection-mask-factory (make-random-projection-mask-factory
                                   projection-features projection-sigma
                                   :seed (next-derived-seed! rng))
          mask-factory (decorate-mask-factory
                        projection-mask-factory
                        identity
		                        (fn [features]
		                           (when features (swap! logistic-counter inc)   
                                     (if (> @logistic-counter upper-bound)
                                       (reset! logistic-counter   (- lower-bound 1 ) )   ) ;;resetting the cycles from lower bound to upper bound
                               )
		                            (let [
		                                  logistic-sigma (*   (/ maximum-fn-value  (+ 1    (m/exp (* (* -1 growth-rate-k)  @logistic-counter ) )  )  ) cumulative-noise-sigma )
		                                  cumulative-noise   (if (= maximum-fn-value 0)
																																			(repeatedly projection-features #(next-gauss! rng 0 cumulative-noise-sigma))
																																			(repeatedly projection-features #(next-gauss! rng 0 logistic-sigma))
																					                )	
											         	     	]
		                                  (swap! cumulative-noise-vector-atom #(map + % cumulative-noise))
                                   ;;  (save-data-append-newline (str "noise.csv")  @cumulative-noise-vector-atom)
                                   ;;(save-data-append-newline (str "noise2.csv")  cumulative-noise)
		                                     ;;  (println "cumulative-noise" cumulative-noise)
		                                      ;; (println "cummulative"  @cumulative-noise-vector-atom)
		                              (when noise-log-atom
		                              (swap! noise-log-atom conj cumulative-noise))
		                            ;; Add the  logistic cumulative noise to the features.
		                                (vec (map +
		                                      features
		                                      translation-vector
		                                      @cumulative-noise-vector-atom))
		                            )
		                        )
                          )
          ]
     
         (mask-factory schema-features)
      )
    )
)
