(ns ppdsp.classifier.moa-classifier
  (:require [ppdsp.classifier.base
             :refer [process-record describe-model]]
            [ppdsp.utils :refer [max-fn]]
            [ppdsp.utils.random :refer [seeded-shuffle]]
            [ppdsp.utils.timing :refer [get-current-thread-time!]]
          ;  [ppdsp.classifier.inspectablearf :as insparf] 
            [ppdsp.classifier.base        ;;waruni
             :refer [process-record describe-model ]]
            [ppdsp.trees.parse-model
             :refer [parse-moa-tree-model-string]]
           )
  (:import [com.yahoo.labs.samoa.instances Attribute InstanceImpl Instances InstancesHeader]
          [ppdsp.classifier.base Classifier]
          [ppdsp.classifier inspectablearf]
          [java.util ArrayList]
          [moa.classifiers.trees HoeffdingTree]
          [moa.classifiers.trees HoeffdingAdaptiveTree]
          [moa.classifiers.meta AdaptiveRandomForest]
          [moa.classifiers.bayes NaiveBayes]
          [moa.classifiers.functions Perceptron]
          [moa.classifiers.lazy kNN]
          [moa.classifiers AbstractClassifier]
          [moa.classifiers.drift DriftDetectionMethodClassifier]
          [moa.core GaussianEstimator]
         ; [clojure.core.rrb-vector.debug-platform-dependent]
           
     )
  )

#?(:clj (do
          (defn- build-instance [record header]
            (let [instance (->> record
                                (:values)
                                ;; Moa represents missing values with NaN:
                                ;; https://github.com/Waikato/moa/blob/master/moa/src/main/java/com/yahoo/labs/samoa/instances/InstanceImpl.java#L464
                                (map #(if (nil? %) Double/NaN %))
                                (double-array)
                                (InstanceImpl. 1.0))]
              (doto instance
                (.setDataset header))))

          (defn- build-attribute [{:keys [name options]}]
            (if (= options :numeric)
              (Attribute. name)
              (Attribute. name (ArrayList. options))))

          (defn- build-header [record-schema]
            (let [attributes (map build-attribute record-schema)
                  instances (Instances. "input-header" (into-array Attribute attributes) 0)]
              (doto (InstancesHeader. instances)
                (.setClassIndex (dec (count record-schema))))))

          (defn- classify-from-votes [schema id votes]
            (if (empty? votes)
		              (let [class-options (->> schema
		                                       ;; Assumes last attribute is class
		                                       last
		                                       :options
		                                       count
		                                       range)
		                    ]
			                ;; If there are no votes, pick a random class with no confidence (seeded by id). WARNING: There may be correlation between rngs with sequential seeds, 
			               ;; but probably not of huge importance in this instance.
			                {:class (first (seeded-shuffle class-options id))
			                 :confidence 0.0
			                 :raw-confidence 0.0
			                 :raw-votes votes
		                   }
		                )
		              (let [[class confidence] (->> (map-indexed vector votes)
		                                            (max-fn second))
				                    normal-confidence (/ (double confidence) (reduce + votes))
		                    ]
				                {:class class
				                 :confidence (if (Float/isNaN normal-confidence)
				                               0.0
				                               normal-confidence)
				                 :raw-confidence (if (or (Double/isInfinite confidence)
				                                         (Double/isNaN confidence))
				                                   nil
				                                   confidence)
				                 :raw-votes (map #(if (or (Double/isInfinite %)
				                                          (Double/isNaN %))
				                                    nil
				                                    %)
				                                 votes)
		                     }
		                )
              )
            )

   
          
          (defn wrap-moa-classifier [schema moa-classifier]
            (let [header (build-header schema)]
		              (doto moa-classifier
		                (.setModelContext header)
		                (.prepareForUse)
                  )
			              (reify Classifier
						                (process-record [this record]
						                  (let [instance (build-instance record header)
						                        start-time (get-current-thread-time!)
						                        votes (.getVotesForInstance moa-classifier instance) ;;Returns an array containing the estimated membership probabilities of the test instance in each class
						                        _ (.trainOnInstance moa-classifier instance) ;;incrementally training the classifier on the instances
						                        stop-time (get-current-thread-time!)
						                        result (classify-from-votes schema (:id record)
						                                                    (vec votes))
                                 
                                   ;; correct (.correctlyClassifies moa-classifier instance) ;; Waruni ;;returns true or false
                                  ;; weight (.trainingWeightSeenByModel moa-classifier) ;;number of instances
                                   ;; w (.weight instance ) ;;weight of a each single instance is equal to 1
                                   ;; copy (.copy instance) ;prints a copy of the instance
                                 ;;  measure (.getModelMeasurements moa-classifier)
                           
   ;;...........accessing protected elements                              
                                ;; f (.getDeclaredField HoeffdingTree "treeRoot")
                             ;;    _ (.setAccessible f true)
                               ;;  root (.get f moa-classifier)
   ;;..........................                            
                                 
                                       ;;  filter (.filterInstanceToLeaf instance nil 1) not working
                                       ;;  seen (.getWeightSeen moa-classifier)       ;;not working
                                       ;;   dist (.observedClassDistribution moa-classifier instance )    ;;not working
                                       ;; promise (.calculatePromise moa-classifier) ;;not working
                                       ;; error (.getErrorEstimation instance)  ;;not working
                                        ;;v (.getClassVotes  moa-classifier instance)
                                       ;;dis (.getObservedClassDistribution instance)
			                              ]
                  
			                     ;;  (println "Instance" instance) ;;record
			                    ;; (println "Result" result) ;; :class :confidence :raw-confidence :raw-votes
                      
                        
							                    (assoc result
							                           :id (:id record)
							                           :process-time (merge-with - stop-time start-time)
                                  )
			                     #_   (println "Result" (assoc result
							                           :id (:id record)
							                           :process-time (merge-with - stop-time start-time)))
                      
			                        )
			                      )
                      
						                (describe-model [this]
                                 (let [sb (StringBuilder.) ]
						                           (try
						                              (do (.getModelDescription moa-classifier sb 2);;Returns a string representation of the model (stringbuilder indent)
		                                          (.toString sb)
		                                      )
						                              (catch NullPointerException e nil)
			                                   )
			                                )
                                  )
                    )
                 )
            )
   
	    		
          (defn hoeffding-tree [schema]
            (let [tree (HoeffdingTree.)
                  ;;tree (HoeffdingAdaptiveTree.)
                 ]
  ;; (println schema) --[{:name 0-dim0, :options :numeric} {:name 0-dim1, :options :numeric} {:name 0-dim2, :options :numeric} {:name 0-dim3, :options :numeric} {:name 0-dim4, :options :numeric} {:name 0-dim5, :options :numeric} {:name activity, :options [cycling lying sitting standing walking]}]
  ;;[{:name avg_rss12, :options :numeric} {:name var_rss12, :options :numeric} {:name avg_rss13, :options :numeric} {:name var_rss13, :options :numeric} {:name avg_rss23, :options :numeric} {:name var_rss23, :options :numeric} {:name activity, :options [cycling lying sitting standing walking]}]
		         
               ;; Configure the tree to classify according to...... always use a naive bayes classifier at the leaf.
		             (.setChosenLabel (.-leafpredictionOption tree) "NB") ;;NBAdaptive NB MC
            
		             (wrap-moa-classifier schema tree)
             )
           )
          
         
          
         #_ (defn adaptive-random-forest
             [schema &
              {:keys [disable-weighting disable-pre-pruning]}]
             (let [tree (AdaptiveRandomForest.)
                   ;; Set the Tree learner to the default CLI string, but with NaiveBayes leaf prediction ("-l NB")
                   hoeffding-tree-string "ARFHoeffdingTree -e 2000000 -g 50 -c 0.01 -l NB"
                   ;; Optionally disable pre-pruning
                   hoeffding-tree-string (if disable-pre-pruning
                                           (str hoeffding-tree-string " -p")
                                           hoeffding-tree-string)]
               (-> tree .-treeLearnerOption (.setValueViaCLIString hoeffding-tree-string))
               ;; Disable multi-threading (we do our own at the experiment level)
               (-> tree .-numberOfJobsOption (.setValue 0))
               (when disable-weighting
                 (-> tree .-disableWeightedVote .set))
               (wrap-moa-classifier schema tree)))

         #_ (defn inspectable-adaptive-random-forest
             [schema &
              {:keys [disable-weighting disable-pre-pruning]}]
             (let [tree (inspectablearf.)
                   ;; Set the Tree learner to the default CLI string, but with NaiveBayes leaf prediction ("-l NB")
                   hoeffding-tree-string "ARFHoeffdingTree -e 2000000 -g 50 -c 0.01 -l NB"
                   ;; Optionally disable pre-pruning
                   hoeffding-tree-string (if disable-pre-pruning
                                           (str hoeffding-tree-string " -p")
                                           hoeffding-tree-string)]
               (-> tree .-treeLearnerOption (.setValueViaCLIString hoeffding-tree-string))
               ;; Disable multi-threading (we do our own at the experiment level)
               (-> tree .-numberOfJobsOption (.setValue 0))
               (when disable-weighting
                 (-> tree .-disableWeightedVote .set))
	               (let [classifier (wrap-moa-classifier schema tree)]
	                 (reify Classifier
	                   (process-record [this record]
	                     (assoc (process-record classifier record)
	                            :tree-depths (->> (.getEnsemble tree)
	                                              (map #(-> % .classifier .measureTreeDepth))
	                                              (vec))))
	                   (describe-model [this]
	                     (describe-model classifier)
                      )
                    )
	                 )
               )
             )

          (defn naive-bayes [schema]
            (let [classifier (NaiveBayes.)]
              (wrap-moa-classifier schema classifier)))

          (defn perceptron [schema]
            (let [classifier (Perceptron.)]
              (wrap-moa-classifier schema classifier)))

          (defn knn [schema]
            (let [classifier (kNN.)]
              (wrap-moa-classifier schema classifier)))

          (defn drift-detection-classifier [schema classifier-string dd-string]
            (let [classifier (DriftDetectionMethodClassifier.)]
              (-> classifier .-baseLearnerOption (.setValueViaCLIString classifier-string))
              (-> classifier .-driftDetectionMethodOption (.setValueViaCLIString dd-string))
              (wrap-moa-classifier schema classifier)))

          (defn dd-naive-bayes [schema]
            (drift-detection-classifier schema "bayes.NaiveBayes" "ADWINChangeDetector"))
          
         #_ (defn abstract-classifier [schema]
            (let [classifier (AbstractClassifier.)]
               (wrap-moa-classifier schema classifier)
               )
             )
          
       )
    )
