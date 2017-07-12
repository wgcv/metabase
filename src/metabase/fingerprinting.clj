(ns metabase.fingerprinting
  "Fingerprinting (feature extraction) for various models."
  (:require [bigml.histogram.core :as hist]
            [bigml.sketchy.hyper-loglog :as hyper-loglog]
            [clj-time
             [coerce :as t.coerce]
             [core :as t]
             [format :as t.format]
             [periodic :as t.periodic]]
            [kixi.stats
             [core :as stats]
             [math :as math]]
            [medley.core :as m]
            [metabase.db.metadata-queries :as metadata]
            [metabase.models
             [card :refer [Card]]
             [field :refer [Field]]
             [metric :refer [Metric]]
             [segment :refer [Segment]]
             [table :refer [Table]]]
            [redux.core :as redux]
            [tide.core :as tide])
  (import com.bigml.histogram.Histogram))

(def ^:private ^:const percentiles (range 0 1 0.1))

(defn histogram
  "Transducer that summarizes numerical data with a histogram."
  ([] (hist/create))
  ([acc] acc)
  ([acc x] (hist/insert! acc x)))

(defn histogram-categorical
  "Transducer that summarizes categorical data with a histogram."
  ([] (hist/create))
  ([acc] acc)
  ([acc x] (hist/insert-categorical! acc (when x 1) x)))

(defn rollup
  "Transducer that groups by `groupfn` and reduces each group with `f`.
   Note the contructor airity of `f` needs to be free of side effects."
  [f groupfn]
  (let [init (f)]
    (fn
      ([] (transient {}))
      ([acc]
       (into {}
         (map (fn [[k v]]
                [k (f v)]))
         (persistent! acc)))
      ([acc x]
       (let [k (groupfn x)]
         (assoc! acc k (f (get acc k init) x)))))))

(defn safe-divide
  "Like `clojure.core//`, but returns nil if denominator is 0."
  [numerator & denominators]
  (when (or (and (not-empty denominators) (not-any? zero? denominators))
            (and (not (zero? numerator)) (empty? denominators)))
    (apply / numerator denominators)))

(defn growth
  "Relative difference between `x1` an `x2`."
  [x2 x1]
  (when (every? some? [x2 x1])
    (safe-divide (* (if (neg? x1) -1 1) (- x2 x1)) x1)))

(defn bins
  "Return centers of bins and thier frequencies of a given histogram."
  [histogram]
  (let [bins (hist/bins histogram)]
    (or (some->> bins first :target :counts (into {}))
        (into {}
          (map (juxt :mean :count))
          bins))))

(defn pmf
  "Probability mass function for given histogram."
  [histogram]
  (m/map-vals (partial * (/ (hist/total-count histogram))) (bins histogram)))

(def ^{:arglists '([histogram])} nil-count
  "Return number of nil values histogram holds."
  (comp :count hist/missing-bin))

(defn total-count
  "Return total number of values histogram holds."
  [histogram]
  (+ (hist/total-count histogram)
     (nil-count histogram)))

(defn kl-divergence
  "Kullback-Leibler divergence of discrete probability distributions `p` and `q`.
   https://en.wikipedia.org/wiki/Kullback%E2%80%93Leibler_divergence"
  [p q]
  (reduce + (map (fn [pi qi]
                   (* pi (math/log (/ pi qi))))
                 p q)))

(defn jensen-shannon-divergence
  "Jensen-Shannon divergence of discrete probability distributions `p` and `q`.
   Note returned is the square root of JS-divergence, so that it obeys the
   metric laws.
   https://en.wikipedia.org/wiki/Jensen%E2%80%93Shannon_divergence"
  [p q]
  (let [m (map + p q)]
    (math/sqrt (+ (* 0.5 (kl-divergence p m)) (* 0.5 (kl-divergence q m))))))

(def ^:private ^:const ^Double cardinality-error 0.01)

(defn cardinality
  "Transducer that sketches cardinality using Hyper-LogLog."
  ([] (hyper-loglog/create cardinality-error))
  ([acc] (hyper-loglog/distinct-count acc))
  ([acc x] (hyper-loglog/insert acc x)))

(defn binned-entropy
  "Calculate entropy of given histogram."
  [histogram]
  (transduce (map #(* % (math/log %)))
             (redux/post-complete + -)
             (vals (pmf histogram))))

(def ^:private Num      [:type/Number :type/*])
(def ^:private DateTime [:type/DateTime :type/*])
(def ^:private Category [:type/* :type/Category])
(def ^:private Any      [:type/* :type/*])
(def ^:private Text     [:type/Text :type/*])

(defn- field-type
  [field]
  (cond
    (sequential? field)             (mapv field-type field)
    (instance? (type Metric) field) Num
    :else                           [(:base_type field)
                                     (or (:special_type field) :type/*)]))

(def linear-computation? ^:private ^{:arglists '([max-cost])}
  (comp #{:linear} :computation))

(def unbounded-computation? ^:private ^{:arglists '([max-cost])}
  (comp #{:unbounded :yolo} :computation))

(def yolo-computation? ^:private ^{:arglists '([max-cost])}
  (comp #{:yolo} :computation))

(def cache-only? ^:private ^{:arglists '([max-cost])}
  (comp #{:cache} :query))

(def sample-only? ^:private ^{:arglists '([max-cost])}
  (comp #{:sample} :query))

(def full-scan? ^:private ^{:arglists '([max-cost])}
  (comp #{:full-scan :joins} :query))

(def alow-joins? ^:private ^{:arglists '([max-cost])}
  (comp #{:joins} :query))

(defmulti fingerprinter
  "Transducer that summarizes (_fingerprints_) given coll. What features are
   extracted depends on the type of corresponding `Field`(s), amount of data
   points available (some algorithms have a minimum data points requirement)
   and `max-cost.computation` setting.
   Note we are heavily using data sketches so some summary values may be
   approximate."
  #(field-type %2))

(defmethod fingerprinter Num
  [_ _]
  (redux/post-complete
   (redux/fuse {:histogram      histogram
                :cardinality    cardinality
                :kurtosis       stats/kurtosis
                :skewness       stats/skewness
                :sum            (redux/with-xform + (remove nil?))
                :sum-of-squares (redux/with-xform + (comp (remove nil?)
                                                          (map math/sq)))})
   (fn [{:keys [histogram cardinality kurtosis skewness sum sum-of-squares]}]
     (if (pos? (total-count histogram))
       (let [nil-count   (nil-count histogram)
             total-count (total-count histogram)
             unique%     (/ cardinality (max total-count 1))
             var         (or (hist/variance histogram) 0)
             sd          (math/sqrt var)
             min         (hist/minimum histogram)
             max         (hist/maximum histogram)
             mean        (hist/mean histogram)
             median      (hist/median histogram)
             span        (- max min)]
         {:histogram            (pmf histogram)
          :percentiles          (apply hist/percentiles histogram percentiles)
          :sum                  sum
          :sum-of-squares       sum-of-squares
          :positive-definite?   (>= min 0)
          :%>mean               (- 1 ((hist/cdf histogram) mean))
          :cardinality-vs-count unique%
          :var>sd?              (> var sd)
          :nil-conunt           nil-count
          :has-nils?            (pos? nil-count)
          :0<=x<=1?             (<= 0 min max 1)
          :-1<=x<=1?            (<= -1 min max 1)
          :span-vs-sd           (safe-divide span sd)
          :mean-median-spread   (safe-divide span (- mean median))
          :min-vs-max           (safe-divide min max)
          :span                 span
          :cardinality          cardinality
          :min                  min
          :max                  max
          :mean                 mean
          :median               median
          :var                  var
          :sd                   sd
          :count                total-count
          :kurtosis             kurtosis
          :skewness             skewness
          :all-distinct?        (>= unique% (- 1 cardinality-error))
          :entropy              (binned-entropy histogram)
          :type                 Num})
       {:count 0
        :type  Num}))))

(defmethod fingerprinter [Num Num]
  [_ _]
  (redux/fuse {:correlation       (stats/correlation first second)
               :covariance        (stats/covariance first second)
               :linear-regression (stats/simple-linear-regression first second)}))

(def ^:private ^{:arglists '([t])} to-double
  "Coerce `DateTime` to `Double`."
  (comp double t.coerce/to-long))

(def ^:private ^{:arglists '([t])} from-double
  "Coerce `Double` into a `DateTime`."
  (comp t.coerce/from-long long))

(defn- fill-timeseries
  "Given a coll of `[DateTime, Any]` pairs with periodicty `step` fill missing
   periods with 0."
  [step ts]
  (let [ts-index (into {} ts)]
    (into []
      (comp (map to-double)
            (take-while (partial >= (-> ts last first)))
            (map (fn [t]
                   [t (ts-index t 0)])))
      (some-> ts
              ffirst
              from-double
              (t.periodic/periodic-seq step)))))

(defn- decompose-timeseries
  "Decompose given timeseries with expected periodicty `period` into trend,
   seasonal component, and reminder.
   `period` can be one of `:day`, `week`, or `:month`."
  [period ts]
  (let [period (case period
                 :month 12
                 :week  52
                 :day   365)]
    (when (>= (count ts) (* 2 period))
      (select-keys (tide/decompose period ts) [:trend :seasonal :reminder]))))

(defmethod fingerprinter [DateTime Num]
  [{:keys [max-cost scale]} _]
  (let [scale (or scale :raw)]
    (redux/post-complete
     (redux/pre-step
      (redux/fuse {:linear-regression (stats/simple-linear-regression first second)
                   :series            (if (= scale :raw)
                                        conj
                                        (redux/post-complete
                                         conj
                                         (partial fill-timeseries
                                                  (case scale
                                                    :month (t/months 1)
                                                    :week  (t/weeks 1)
                                                    :day   (t/days 1)))))})
      (fn [[x y]]
        [(-> x t.format/parse to-double) y]))
     (fn [{:keys [series linear-regression]}]
       (let [ys-r (->> series (map second) reverse not-empty)]
         (merge {:series                 (for [[x y] series]
                                           [(from-double x) y])
                 :linear-regression      linear-regression
                 :seasonal-decomposition
                 (when (and (not= scale :raw)
                            (unbounded-computation? max-cost))
                   (decompose-timeseries scale series))}
                (case scale
                  :month {:YoY          (growth (first ys-r) (nth ys-r 11))
                          :YoY-previous (growth (second ys-r) (nth ys-r 12))
                          :MoM          (growth (first ys-r) (second ys-r))
                          :MoM-previous (growth (second ys-r) (nth ys-r 2))}
                  :week  {:YoY          (growth (first ys-r) (nth ys-r 51))
                          :YoY-previous (growth (second ys-r) (nth ys-r 52))
                          :WoW          (growth (first ys-r) (second ys-r))
                          :WoW-previous (growth (second ys-r) (nth ys-r 2))}
                  :day   {:DoD          (growth (first ys-r) (second ys-r))
                          :DoD-previous (growth (second ys-r) (nth ys-r 2))}
                  :raw   nil)))))))

(defmethod fingerprinter [Category Any]
  [opts [x y]]
  (rollup (redux/pre-step (fingerprinter opts y) second) first))

(defmethod fingerprinter Text
  [_ _]
  (redux/post-complete
   (redux/fuse {:histogram (redux/pre-step histogram (stats/somef count))})
   (fn [{:keys [histogram]}]
     (let [nil-count (nil-count histogram)]
       {:min        (hist/minimum histogram)
        :max        (hist/maximum histogram)
        :histogram  (pmf histogram)
        :count      (total-count histogram)
        :nil-conunt nil-count
        :has-nils?  (pos? nil-count)
        :type       Text}))))

(defn- quarter
  [dt]
  (-> (t/month dt) (/ 3) Math/ceil long))

(defmethod fingerprinter DateTime
  [_ _]
  (redux/post-complete
   (redux/pre-step
    (redux/fuse {:histogram         (redux/pre-step histogram t.coerce/to-long)
                 :histogram-hour    (redux/pre-step histogram-categorical
                                                    (stats/somef t/hour))
                 :histogram-day     (redux/pre-step histogram-categorical
                                                    (stats/somef t/day-of-week))
                 :histogram-month   (redux/pre-step histogram-categorical
                                                    (stats/somef t/month))
                 :histogram-quarter (redux/pre-step histogram-categorical
                                                    (stats/somef quarter))})
    t.format/parse)
   (fn [{:keys [histogram histogram-hour histogram-day histogram-month
                histogram-quarter]}]
     (let [nil-count (nil-count histogram)]
       {:min               (from-double (hist/minimum histogram))
        :max               (from-double (hist/maximum histogram))
        :histogram         (m/map-keys from-double (pmf histogram))
        :percentiles       (m/map-vals from-double
                                       (apply hist/percentiles histogram
                                              percentiles))
        :histogram-hour    (pmf histogram-hour)
        :histogram-day     (pmf histogram-day)
        :histogram-month   (pmf histogram-month)
        :histogram-quarter (pmf histogram-quarter)
        :count             (total-count histogram)
        :nil-conunt        nil-count
        :has-nils?         (pos? nil-count)
        :entropy           (binned-entropy histogram)
        :type              DateTime}))))

(defmethod fingerprinter Category
  [_ _]
  (redux/post-complete
   (redux/fuse {:histogram   histogram-categorical
                :cardinality cardinality})
   (fn [{:keys [histogram cardinality]}]
     (let [nil-count   (nil-count histogram)
           total-count (total-count histogram)
           unique%     (/ cardinality (max total-count 1))]
       {:histogram            (pmf histogram)
        :cardinality-vs-count unique%
        :nil-conunt           nil-count
        :has-nils?            (pos? nil-count)
        :cardinality          cardinality
        :count                total-count
        :all-distinct?        (>= unique% (- 1 cardinality-error))
        :entropy              (binned-entropy histogram)
        :type                 Category}))))

(defmethod fingerprinter :default
  [_ field]
  (redux/post-complete
   (redux/fuse {:total-count stats/count
                :nil-count   (redux/with-xform stats/count (filter nil?))})
   (fn [{:keys [total-count nil-count]}]
     {:count       total-count
      :nil-count   nil-count
      :has-nils?   (pos? nil-count)
      :type        nil
      :actual-type (field-type field)})))

(prefer-method fingerprinter Category Text)
(prefer-method fingerprinter Num Category)

(defmulti comparison-vector
  "Fingerprint feature vector for comparison/difference purposes."
  :type)

(defmethod comparison-vector Num
  [fingerprint]
  (select-keys fingerprint
               [:histogram :mean :median :min :max :sd :count :kurtosis
                :skewness :entropy :nil-count :cardinality-vs-count :span]))

(defn- fingerprint-field
  "Transduce given column with corresponding fingerprinter."
  [opts field data]
  (-> (transduce identity (fingerprinter opts field) data)
      (assoc :field field)))

(defn- fingerprint-query
  "Transuce each column in given dataset with corresponding fingerprinter."
  [opts {:keys [rows cols]}]
  (transduce identity
             (apply redux/juxt (map-indexed (fn [i field]
                                              (redux/post-complete
                                               (redux/pre-step
                                                (fingerprinter opts field)
                                                #(nth % i))
                                               #(assoc % :field field)))
                                            cols))
             rows))

(def ^:private ^:const ^Long max-sample-size 10000)

(defmulti fingerprint
  "Given a model, fetch corresponding dataset and compute its fingerprint.

   Takes a map of options as first argument. Recognized options:
   * `:max-cost`         a map with keys `:computation` and `:query` which
                         limits maximal resource expenditure when computing the
                         fingerprint. `:computation` can be one of `:linear`
                         (O(n) or better), `:unbounded`, or `:yolo` (full blown
                         machine learning etc.). `query` can be one of
                         `:cache` (use only cached data), `:sample` (sample
                         up to `max-sample-size` rows), `:full-scan` (full table
                         scan), or `:joins` (bring in data from other tables if
                         needed).

   * `:scale`            controls pre-aggregation by time. Can be one of `:day`,
                         `week`, `:month`, or `:raw`"
  #(type %2))

(defn- extract-query-opts
  [{:keys [max-cost]}]
  (cond-> {}
    (sample-only? max-cost) (assoc :limit max-sample-size)))

(defmethod fingerprint (type Field)
  [opts field]
  (->> (metadata/field-values field (extract-query-opts opts))
       (fingerprint-field opts field)))

(defmethod fingerprint (type Table)
  [opts table]
  (fingerprint-query opts (metadata/query-values
                           (:db_id table)
                           (merge (extract-query-opts opts)
                                  {:source-table (:id table)}))))

(defmethod fingerprint (type Card)
  [opts card]
  (let [{:keys [rows cols]} (metadata/query-values
                             (:database_id card)
                             (merge (extract-query-opts opts)
                                    (-> card :dataset_query :query)))
        {:keys [breakout aggregation]} (group-by :source cols)
        fields [(first breakout) (or (first aggregation) (second breakout))]]
    {:fingerprint (fingerprint-field opts fields rows)
     :fields      [(fingerprint-field opts (first fields) (map first rows))
                   (fingerprint-field opts (second fields) (map second rows))]}))

(defmethod fingerprint (type Segment)
  [opts segment]
  (fingerprint-query opts (metadata/query-values
                           (metadata/db-id segment)
                           (merge (extract-query-opts opts)
                                  (:definition segment)))))

(defmethod fingerprint (type Metric)
  [_ metric]
  {:metric metric})

(defn compare-fingerprints
  "Compare fingerprints of two models."
  [opts a b]
  {:models [(fingerprint opts a)
            (fingerprint opts b)]})

(defn- build-query
  [{:keys [scale] :as opts} a b]
  (merge (extract-query-opts opts)
         (cond
           (and (isa? (field-type a) DateTime)
                (not= scale :raw)
                (instance? (type Metric) b))
           (merge (:definition b)
                  {:breakout [[:datetime-field [:field-id (:id a)] scale]]})

           (and (isa? (field-type a) DateTime)
                (not= scale :raw)
                (isa? (field-type b) Num))
           {:source-table (:table_id a)
            :breakout     [[:datetime-field [:field-id (:id a)] scale]]
            :aggregation  [:sum [:field-id (:id b)]]}

           :else
           {:source-table (:table_id a)
            :fields       [[:field-id (:id a)]
                           [:field-id (:id b)]]})))

(defn multifield-fingerprint
  "Holistically fingerprint dataset with multiple columns.
   Takes and additional option `:scale` which controls how timeseries data
   is aggregated. Possible values: `:month`, `week`, `:day`, `:raw`."
  [opts a b]
  (assert (= (:table_id a) (:table_id b)))
  {:fingerprint (->> (metadata/query-values (metadata/db-id a)
                                            (build-query opts a b))
                     :rows
                     (fingerprint-field opts [a b]))
   :fields      (map (partial fingerprint opts) [a b])})

(def magnitude
  "Transducer that claclulates magnitude (Euclidean norm) of given vector."
  (redux/post-complete (redux/pre-step + math/sq) math/sqrt))

(defmulti difference
  "Difference between two features.
   Confined to [0, 1] with 0 being same, and 1 orthogonal."
  #(mapv type %&))

(defmethod difference [Number Number]
  [a b]
  (cond
    (= a b 0)         0
    (zero? (max a b)) 1
    :else             (- 1 (/ (- (max a b) (min a b))
                              (max a b)))))

(defmethod difference [Boolean Boolean]
  [a b]
  (if (= a b) 0 1))

(defmethod difference [Histogram Histogram]
  [a b]
  (- 1 (jensen-shannon-divergence (vals (pmf a)) (vals (pmf b)))))

(defn pairwise-differences
  "Pairwise differences of (feature) vectors `a` and `b`."
  [a b]
  (map difference a b))

(defn distance
  "Distance metric between (feature) vectors."
  [a b]
  (/ (transduce identity magnitude (pairwise-differences a b))
     (math/sqrt (count a))))
