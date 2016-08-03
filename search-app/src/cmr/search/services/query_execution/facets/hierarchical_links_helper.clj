(ns cmr.search.services.query-execution.facets.hierarchical-links-helper
  "Functions to create links for hierarchical fields within v2 facets. Facets (v2) includes links
  within each value to conduct the same search with either a value added to the query or with the
  value removed. This namespace contains functions to create the links that include or exclude a
  particular parameter. See https://wiki.earthdata.nasa.gov/display/CMR/Updated+facet+response for
  details.

  Commonly used parameters in the functions include:

  base-url - root URL for the link being created.
  query-params - the query parameters from the current search as a map with a key for each
                 parameter name and the value as either a single value or a collection of values.
  base-field - the base field for the field as a snake case string. For example for a param
               \"science_keywords_h[0][topic]\" the base-field is \"science_keywords_h\".
  field-name - the query-field that needs to be added to (or removed from) the current search.
  value - the value to apply (or remove) for the given field-name."
  (:require [camel-snake-kebab.core :as csk]
            [clojure.string :as str]
            [cmr.common.util :as util]
            [cmr.search.services.query-execution.facets.links-helper :as lh]))

(defn- get-indexes-for-field-name
  "Returns a set of all of the indexes for the given field name within the query parameters."
  [query-params base-field]
  ;; Regex to find any query params for the base-field, and extract the index for each matching
  ;; param. e.g. with a base field of "foo" and param name of "foo[11][bar]", the regex will
  ;; extract 11 as the index. It would not match "notfoo[11][bar]" because of the different base
  ;; field.
  (let [field-regex (re-pattern (format "%s\\[(\\d+)\\]\\[.*\\]" base-field))]
    (set (keep #(some-> (re-matches field-regex %) second Integer/parseInt)
               (keys query-params)))))

(defn- get-max-index-for-field-name
  "Returns the max index for the provided field name within the query parameters.

  For example if the query parameters included fields foo[0][alpha]=bar and foo[6][beta]=zeta the
  max index of field foo would be 6. If the field is not found then -1 is returned."
  [query-params base-field]
  (apply max -1 (get-indexes-for-field-name query-params base-field)))

(defn- split-into-base-field-and-subfield
  "Takes a query parameter name and returns the base field and subfield for that query parameter.
  For example \"science_keywords_h[0][topic]\" returns [\"science_keywords_h\" \"topic\"]"
  [param-name]
  (let [[_ base-field subfield] (re-find #"(.*)\[\d+\]\[(.*)\]" param-name)]
    [base-field subfield]))

(defn- get-keys-to-update
  "Returns a sequence of keys that have multiple values where at least one of values matches the
  passed in value. Looks for matches case insensitively."
  [query-params value]
  (let [value (str/lower-case value)]
    (for [[k value-or-values] query-params
          :when (and (coll? value-or-values)
                     (some #{value} (map str/lower-case value-or-values)))]
      k)))

(defn- get-keys-to-remove
  "Returns a sequence of keys that have a single value which matches the passed in value. Looks for
  matches case insensitively."
  [query-params value]
  (let [value (str/lower-case value)]
    (for [[k value-or-values] query-params
          :when (and (not (coll? value-or-values))
                     (= (str/lower-case value-or-values) value))]
      k)))

(defn- get-potential-matching-query-params
  "Returns a subset of query parameters whose keys match the provided field-name and ignoring the
  index.

  As an example, consider passing in a field-name of foo[0][alpha] and query parameters
  of foo[0][alpha]=fish, foo[0][beta]=cat, and foo[1][alpha]=dog. The returned query params would be
  foo[0][alpha]=fish and foo[1][alpha]=dog, but not foo[0][beta]=cat. Note that the same query
  params would also be returned for any field-name of foo[<n>][alpha] where n is an integer.
  Field-name must be of the form <string>[<int>][<string>]."
  [query-params field-name]
  (let [[base-field subfield] (split-into-base-field-and-subfield field-name)
        field-regex (re-pattern (format "%s.*%s]" base-field subfield))
        matching-keys (keep #(re-matches field-regex %) (keys query-params))]
    (when (seq matching-keys)
      (select-keys query-params matching-keys))))

(defn- remove-value-from-query-params-for-hierachical-field
  "Removes the value from anywhere it matches in the provided potential-query-param-matches.
  Each key in the potential-query-param-matches may contain a single value or a collection of
  values. If the key contains a single value which matches the passed in value the query parameter
  is removed completely. Otherwise if the value matches one of the values in a collection of values
  only the matching value is removed for that query parameter. All value comparisons are performed
  case insensitively."
  [query-params potential-query-param-matches value]
  (let [updated-query-params (apply dissoc query-params
                                    (get-keys-to-remove potential-query-param-matches value))]
    (reduce (fn [updated-params k]
                (update updated-params k
                 (fn [existing-values]
                   (remove (fn [existing-value]
                             (= value (str/lower-case existing-value)))
                           existing-values))))
            updated-query-params
            (get-keys-to-update potential-query-param-matches value))))

(defn- process-removal-for-field-value-tuple
  "Helper to process a subfield and value tuple to remove the appropriate term from the query
  parameters."
  [base-field potential-qp-matches query-params field-value-tuple]
  (let [[field value] field-value-tuple
        value (str/lower-case value)
        field-name (format "%s[0][%s]" base-field (csk/->snake_case_string field))
        potential-qp-matches (or potential-qp-matches
                                (get-potential-matching-query-params query-params field-name))]
    (remove-value-from-query-params-for-hierachical-field query-params potential-qp-matches value)))

(defn- remove-index-from-params
  "Returns the query parameters for the provided base-field with the index removed from the
  parameter name."
  [query-params]
  (util/map-keys #(str/replace % #"\[\d+\]" "") query-params))

(defn- find-duplicate-indexes
  "Returns a set of indexes that have query parameters that are completely duplicated by another
  index.
  params-by-index - A map with the key being a numerical index and values of all the query
                    parameters for that index."
  [params-by-index]
  (set
   (for [[idx qps] params-by-index
         [matching-index matching-qps] params-by-index
         ;; Ignore the index when comparing the query parameters
         :let [qps (remove-index-from-params qps)
               matching-qps (remove-index-from-params matching-qps)]
         :when (and (not= idx matching-index)
                    (and (= qps (select-keys matching-qps (keys qps)))
                         ;; another group of params fully contains this group of params
                         (or (not= qps matching-qps)
                             ;; If multiple sets of parameters exactly match, get rid of all but one
                             (> idx matching-index))))]
     idx)))

(defn- remove-duplicate-params
  "Removes any parameters for the provided field which are exact subsets of another index for the
  same field."
  [query-params base-field]
  (let [query-param-regex (re-pattern (str "^" base-field "\\[\\d+\\]\\["))
        base-field-params-by-index (group-by (fn [[k _]]
                                               (when (re-find query-param-regex k)
                                                 (->> (re-find #"\[(\d+)\]" k)
                                                      second
                                                      Integer/parseInt)))
                                             query-params)
        indexes-to-remove (find-duplicate-indexes base-field-params-by-index)
        query-keys-to-remove (mapcat #(keys (get base-field-params-by-index %)) indexes-to-remove)]
    (if (seq query-keys-to-remove)
      (apply dissoc query-params query-keys-to-remove)
      query-params)))

(defn- create-remove-link-for-hierarchical-field
  "Create a link that will modify the current search to no longer filter on the given hierarchical
  field-name and value. Looks for matches case insensitively.
  Field-name must be of the form <string>[<int>][<string>].

  applied-children-tuples - Tuples of [subfield term] for any applied children terms that should
                            also be removed in the remove link being generated.
  potential-qp-matches - List of query parameters which may need to be removed when generating the
                         remove link."
  [base-url query-params field-name value applied-children-tuples potential-qp-matches]
  (let [[base-field subfield] (split-into-base-field-and-subfield field-name)
        updated-params (reduce (partial process-removal-for-field-value-tuple base-field
                                        potential-qp-matches)
                              query-params
                              (conj applied-children-tuples [subfield value]))
        updated-params (remove-duplicate-params updated-params base-field)]
    {:remove (lh/generate-query-string base-url updated-params)}))

(defn- get-matching-ancestors
  "Returns a sequence of query parameters for each unique index that completely matches the
  hierarchical ancestors of the provided field."
  [base-field query-params parent-indexes ancestors-map]
  (let [;; In order for a field to be considered for removal, it and all of its ancestors must
        ;; be found in the query parameters. This builds a sequence of query parameters to check
        ;; against (one set of query parameters for each potential parent index).
        ancestors-to-match (for [idx parent-indexes]
                             (util/map-keys #(format "%s[%d][%s]" base-field idx %) ancestors-map))
        num-ancestors (count (first ancestors-to-match))
        ;; Sequences of actual matches against the query parameters for each potential index
        ancestor-matches (map (fn [ancestor]
                                (into {} (for [[k v] ancestor
                                               :when (= (str/lower-case v)
                                                        (some->
                                                         (get query-params k) str/lower-case))]
                                           [k v])))
                              ancestors-to-match)]
    ;; Filter the results to only those where every ancestor was found in the query parameters for
    ;; that index
    (into {} (mapcat #(when (= num-ancestors (count %)) %) ancestor-matches))))

(defn- generate-query-params
  "Returns a map of query params for the given base-field, indexes, and subfield-value-tuples"
  [base-field indexes subfield-value-tuples]
  (into {}
        (for [idx indexes
              [k v] subfield-value-tuples
              :let [param-name (format "%s[%d][%s]" base-field idx (csk/->snake_case_string k))]]
          [param-name v])))

;;;;;;;;;;;;;;;;;;;;
;; Public functions
(defn create-apply-link-for-hierarchical-field
  "Create a link that will modify the current search to also filter by the given hierarchical
  field-name and value.
  Field-name must be of the form <string>[<int>][<string>] such as science_keywords[0][topic]."
  [base-url query-params field-name ancestors-map parent-indexes value has-siblings? & _]
  (let [[base-field subfield] (split-into-base-field-and-subfield field-name)
        index-to-use (if (or has-siblings? (empty? parent-indexes))
                       (inc (get-max-index-for-field-name query-params base-field))
                       (apply min parent-indexes))
        updated-field-name (format "%s[%d][%s]" base-field index-to-use subfield)
        updated-query-params (assoc query-params updated-field-name value)
        updated-query-params (if (or has-siblings? (empty? parent-indexes))
                               ;; Add all of the ancestors for this index
                               (reduce
                                (fn [query-params [k v]]
                                  (let [param-name (format "%s[%d][%s]" base-field index-to-use k)]
                                    (assoc query-params param-name v)))
                                updated-query-params
                                ancestors-map)
                               updated-query-params)]
    {:apply (lh/generate-query-string base-url updated-query-params)}))

(defn create-link-for-hierarchical-field
  "Creates either a remove or an apply link based on whether this particular value is already
  selected within a query. Returns a map with the key being the type of link created and value is
  the link itself. The Field-name must be a hierarchical field which has the form
  <string>[<int>][<string>].

  applied-children-tuples - Tuples of [subfield term] for any applied children terms that should
                            also be removed if a remove link is being generated."
  ([base-url query-params field-name value]
   (create-link-for-hierarchical-field base-url query-params field-name nil nil value false nil))
  ([base-url query-params field-name ancestors-map parent-indexes value has-siblings?
    applied-children-tuples]
   (if (keys (dissoc ancestors-map "category"))
     (let [[base-field subfield] (split-into-base-field-and-subfield field-name)
           ancestor-matches (get-matching-ancestors base-field query-params parent-indexes
                                                    (assoc ancestors-map subfield value))]
       (if (seq ancestor-matches)
         (let [indexes (get-indexes-for-field-name ancestor-matches base-field)
               ;; Children params can exist for any of the indexes that matched
               potential-children-params (generate-query-params base-field indexes
                                                                applied-children-tuples)
               all-potential-params (merge potential-children-params ancestor-matches)]
           (create-remove-link-for-hierarchical-field base-url query-params field-name value
                                                      applied-children-tuples all-potential-params))
         (create-apply-link-for-hierarchical-field base-url query-params field-name ancestors-map
                                                   parent-indexes value has-siblings?)))

     (let [potential-query-params (get-potential-matching-query-params query-params field-name)
           value-exists? (or (seq (get-keys-to-remove potential-query-params value))
                             (seq (get-keys-to-update potential-query-params value)))]
       (if value-exists?
         (create-remove-link-for-hierarchical-field base-url query-params field-name value
                                                    applied-children-tuples nil)
         (create-apply-link-for-hierarchical-field base-url query-params field-name ancestors-map
                                                   parent-indexes value has-siblings?))))))
