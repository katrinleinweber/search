(ns cmr.search.results-handlers.opendata-results-handler
  "Handles the opendata results format and related functions"
  (:require [cmr.search.data.elastic-results-to-query-results :as elastic-results]
            [cmr.search.data.elastic-search-index :as elastic-search-index]
            [cmr.search.services.query-service :as qs]
            [clojure.walk :as walk]
            [clojure.string :as str]
            [clojure.set :as set]
            [clj-time.core :as time]
            [cheshire.core :as json]
            [cmr.common.util :as util]
            [cmr.search.models.results :as r]
            [cmr.spatial.serialize :as srl]
            [cmr.search.services.url-helper :as url]))

(def BUREAU_CODE
  "opendata bureauCode for NASA"
  "026:00")

(def PROGRAM_CODE
  "opendata programCode for NASA : Earth Science Research"
  "026:001")

(def PUBLISHER
  "opendata publisher string for NASA"
  "National Aeronautics and Space Administration")

(def LANGUAGE_CODE
  "opendata language code for NASA data"
  "en-US")

(defmethod elastic-search-index/concept-type+result-format->fields [:collection :opendata]
  [concept-type query]
  ;; TODO add spatial, etc.
  ["entry-title"
   "summary"
   "science-keywords-flat"
   "update-time"
   "insert-time"
   "concept-id"
   "short-name"
   "project-sn"
   "opendata-format"
   "access-url"
   "access-value" ;; needed for acl enforcment
   ])

(defmethod elastic-results/elastic-result->query-result-item :opendata
  [_ _ elastic-result]
  (let [{concept-id :_id
         score :_score
         {[short-name] :short-name
          [summary] :summary
          [update-time] :update-time
          [insert-time] :insert-time
          [provider-id] :provider-id
          [project-sn] :project-sn
          [access-value] :access-value
          [science-keywords-flat] :science-keywords-flat
          [opendata-format] :opendata-format
          [access-url] :access-url
          [entry-title] :entry-title} :fields} elastic-result]
    {:id concept-id
     :score (r/normalize-score score)
     :title entry-title
     :short-name short-name
     :summary summary
     :update-time update-time
     :insert-time insert-time
     :concept-type :collection
     :opendata-format opendata-format
     :access-url access-url
     :project-sn project-sn
     :provider-id provider-id
     :access-value access-value ;; needed for acl enforcment
     :keywords science-keywords-flat
     :entry-title entry-title}))

(defn short-name->access-level
  "Return the access-level value based on the given short-name."
  [short-name]
  (if (and short-name (re-find #"AST" short-name))
    "restricted public"
    "public"))


(defn result->opendata
  "Converts a search result item to opendata."
  [context concept-type item]
  (let [{:keys [id summary short-name project-sn update-time insert-time provider-id access-value
                keywords entry-title opendata-format access-url]} item]
    (println (str "FORMAT....." opendata-format))
    (util/remove-nil-keys {:title entry-title
                           :description summary
                           :keyword keywords
                           :modified update-time
                           :publisher PUBLISHER
                           ;; TODO :conctactPoint
                           ;; TODO :mbox
                           :identifier id
                           :accessLevel (short-name->access-level short-name)
                           :bureauCode BUREAU_CODE
                           :programCode PROGRAM_CODE
                           ;; TODO :accessLevelComment :access-constraints
                           :accessURL access-url
                           :format opendata-format
                           ;; TODO :spatial
                           ;; TODO :temporal
                           :theme (not-empty (str/join "," project-sn))
                           ;; TODO :distribution
                           ;; TODO :accrualPeriodicity
                           ;; TODO :landingPage
                           :language  LANGUAGE_CODE
                           ;; TODO :references related-urls
                           :issued insert-time})))

(defn results->opendata
  "Convert search results to opendata."
  [context concept-type results]
  (let [{:keys [items]} results]
    (map (partial result->opendata context concept-type) items)))

(defmethod qs/search-results->response :opendata
  [context query results]
  (let [{:keys [concept-type pretty? echo-compatible? result-features]} query
        response-results (results->opendata
                           context concept-type results)]
    (json/generate-string response-results {:pretty pretty?})))
