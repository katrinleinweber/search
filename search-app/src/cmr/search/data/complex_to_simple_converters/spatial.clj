(ns cmr.search.data.complex-to-simple-converters.spatial
  "Contains converters for spatial condition into the simpler executable conditions"
  (:require [cmr.search.data.complex-to-simple :as c2s]
            [cmr.search.data.elastic-search-index :as idx]
            [cmr.search.services.query-helper-service :as query-helper]
            [cmr.search.models.query :as qm]
            [cmr.search.models.group-query-conditions :as gc]
            [cmr.spatial.mbr :as mbr]
            [cmr.spatial.serialize :as srl]
            [cmr.spatial.derived :as d]
            [cmr.spatial.relations :as sr]
            [clojure.string :as str])
  (:import gov.nasa.echo_orbits.EchoOrbitsRubyBootstrap))

(def orbits
  "Java wrapper for echo-orbits ruby library."
  (EchoOrbitsRubyBootstrap/bootstrapEchoOrbits))

(defn- shape->script-cond
  [shape]
  (let [ords-info-map (-> (srl/shapes->ords-info-map [shape])
                          (update-in [:ords-info] #(str/join "," %))
                          (update-in [:ords] #(str/join "," %)))]
    (qm/map->ScriptCondition {:name "spatial"
                              :params ords-info-map})))

(defn- br->cond
  [prefix {:keys [west north east south] :as br}]
  (letfn [(add-prefix [field]
                      (->> field name (str prefix "-") keyword))
          (range-cond [field from to]
                      (qm/numeric-range-condition (add-prefix field) from to))
          (bool-cond [field value]
                     (qm/->BooleanCondition (add-prefix field) value))]
    (if (mbr/crosses-antimeridian? br)
      (let [c (range-cond :west -180 west)
            d (range-cond :east -180 east)
            e (range-cond :east east 180)
            f (range-cond :west west 180)
            am-conds (gc/and-conds [(bool-cond :crosses-antimeridian true)
                                    (gc/or-conds [c f])
                                    (gc/or-conds [d e])])
            lon-cond (gc/or-conds [(range-cond :west -180 east)
                                   (range-cond :east west 180)
                                   am-conds])]
        (gc/and-conds [lon-cond
                       (range-cond :north south 90)
                       (range-cond :south -90 north)]))

      (let [north-cond (range-cond :north south 90.0)
            south-cond (range-cond :south -90.0 north)
            west-cond (range-cond :west -180 east)
            east-cond (range-cond :east west 180)
            am-conds (gc/and-conds [(bool-cond :crosses-antimeridian true)
                                    (gc/or-conds [west-cond east-cond])])
            non-am-conds (gc/and-conds [west-cond east-cond])]
        (gc/and-conds [north-cond
                       south-cond
                       (gc/or-conds [am-conds non-am-conds])])))))

(defn- resolve-shape-type
  "Convert the 'type' string from a serialized shape to one of 'point', 'line', 'br', or 'poly'.
  These are used by the echo-orbits-java wrapper library."
  [type]
  (cond
    (re-matches #".*line.*" type) "line"
    (re-matches #".*poly.*" type) "poly"
    :else type))

(defn- orbit-crossings
  "Compute the orbit crossing ranges (max and min longitude) for a single collection
  used to create the crossing conditions for orbital crossing searches.
  The stored-ords parameter is a vector of coordinates (longitude/latitude) of the points for
  the search area (as returned by the shape->stored-ords method of the spatial library.
  The orbit-params paraemter is the set of orbit parameters for a single collection.
  Returns a vector of vectors of doubles representing the ascending and descending crossing ranges."
  [stored-ords orbit-params]
  ;; Use the orbit parameters to perform orbital back tracking to longitude ranges to be used
  ;; in the search.
  (let [type (resolve-shape-type (name (:type (first stored-ords))))
        coords (double-array (map srl/stored->ordinate
                                  (:ords (first stored-ords))))]
    (let [{:keys [swath-width
                  period
                  inclination-angle
                  number-of-orbits
                  start-circular-latitude]} orbit-params
          asc-crossing (.areaCrossingRange
                         orbits
                         type
                         coords
                         true
                         inclination-angle
                         period
                         swath-width
                         start-circular-latitude
                         number-of-orbits)
          desc-crossing (.areaCrossingRange
                          orbits
                          type
                          coords
                          false
                          inclination-angle
                          period
                          swath-width
                          start-circular-latitude
                          number-of-orbits)]
      (when (or (seq asc-crossing)
                (seq desc-crossing))
        [asc-crossing desc-crossing]))))

(defn- range->numeric-range-intersection-condition
  "Create a condtion to test for a numberic range intersection with multiple ranges."
  [ranges]
  (gc/or-conds
    (map (fn [[start-lat end-lat]]
           (qm/numeric-range-intersection-condition
             :orbit-start-clat
             :orbit-end-clat
             start-lat
             end-lat))
         ranges)))

(defn- crossing-ranges->condition
  "Create a search condition for a given vector of crossing ranges."
  [crossing-ranges]
  (gc/or-conds
    (map (fn [[range-start range-end]]
           (qm/numeric-range-condition
             :orbit-asc-crossing-lon
             range-start
             range-end))
         crossing-ranges)))

(defn- orbital-condition
  "Create a condition that will use orbit parameters and orbital back tracking to find matches
  to a spatial search."
  [context shape]
  (let [mbr (sr/mbr shape)
        [asc-lat-ranges desc-lat-ranges] (.denormalizeLatitudeRange orbits (:south mbr) (:north mbr))
        asc-lat-conds (range->numeric-range-intersection-condition asc-lat-ranges)
        desc-lat-conds (range->numeric-range-intersection-condition desc-lat-ranges)
        {:keys [query-collection-ids]} context
        orbit-params (query-helper/collection-orbit-parameters context query-collection-ids true)
        stored-ords (srl/shape->stored-ords shape)
        crossings-map (reduce (fn [memo params]
                                (let [lon-crossings (orbit-crossings stored-ords params)]
                                  (if (seq lon-crossings)
                                    (assoc
                                      memo
                                      (:concept-id params)
                                      lon-crossings)
                                    memo)))
                              {}
                              orbit-params)]
    (when (seq crossings-map)
      (gc/or-conds
        (map (fn [collection-id]
               (let [[asc-crossings desc-crossings] (get crossings-map collection-id)]
                 (gc/and-conds
                   [(qm/string-condition :collection-concept-id collection-id, true, false)
                    (gc/or-conds
                      [;; ascending
                       (gc/and-conds
                         [asc-lat-conds
                          (crossing-ranges->condition asc-crossings)])
                       ;; descending
                       (gc/and-conds
                         [desc-lat-conds
                          (crossing-ranges->condition desc-crossings)])])])))
             (keys crossings-map))))))

(extend-protocol c2s/ComplexQueryToSimple
  cmr.search.models.query.SpatialCondition
  (c2s/reduce-query-condition
    [{:keys [shape]} context]
    (let [shape (d/calculate-derived shape)
          orbital-cond (when (= :granule (:query-concept-type context))
                         (orbital-condition context shape))
          mbr-cond (br->cond "mbr" (srl/shape->mbr shape))
          lr-cond (br->cond "lr" (srl/shape->lr shape))
          spatial-script (shape->script-cond shape)
          spatial-cond (gc/and-conds [mbr-cond (gc/or-conds [lr-cond spatial-script])])]
      (if orbital-cond
        (gc/or-conds [spatial-cond orbital-cond])
        spatial-cond))))
