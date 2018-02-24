(ns cmr.search.services.aql.converters.attribute-name
  "Contains functions for parsing and converting additionalAttributeNames aql element to query conditions"
  (:require [cmr.common.xml :as cx]
            [cmr.search.services.aql.conversion :as a]
            [cmr.search.models.query :as qm]
            [cmr.common-app.services.search.group-query-conditions :as gc]))

(defn- attrib-name-condition
  "Returns the AttributeNameAndGroupCondition for the given attrib-name"
  ([attrib-name]
   (attrib-name-condition attrib-name nil))
  ([attrib-name pattern?]
   (qm/map->AttributeNameAndGroupCondition {:name attrib-name
                                            :pattern? pattern?})))

(defmulti attrib-name-element->condition
  "Returns the query condition of the given additional attribute name element"
  (fn [elem]
    (:tag elem)))

(defmethod attrib-name-element->condition :value
  [elem]
  (attrib-name-condition (a/element->string-content elem)))

(defmethod attrib-name-element->condition :textPattern
  [elem]
  (let [value (-> elem a/element->string-content a/aql-pattern->cmr-pattern)]
    (attrib-name-condition value true)))

(defmethod attrib-name-element->condition :list
  [elem]
  (let [values (cx/strings-at-path elem [:value])
        conditions (map (comp attrib-name-condition a/remove-outer-single-quotes) values)]
    (gc/or-conds conditions)))

;; Converts additionalAttributeNames element into query condition, returns the converted condition
(defmethod a/element->condition :attribute-name
  [concept-type element]
  (attrib-name-element->condition (first (:content element))))
