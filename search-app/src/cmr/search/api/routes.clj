(ns cmr.search.api.routes
  (:require [compojure.route :as route]
            [compojure.core :refer :all]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [ring.util.response :as r]
            [ring.util.request :as request]
            [ring.util.codec :as codec]
            [ring.middleware.json :as ring-json]
            [ring.middleware.params :as params]
            [ring.middleware.nested-params :as nested-params]
            [ring.middleware.keyword-params :as keyword-params]
            [cheshire.core :as json]
            [inflections.core :as inf]

            [cmr.common.concepts :as concepts]
            [cmr.common.log :refer (debug info warn error)]
            [cmr.common.api.errors :as errors]
            [cmr.common.cache :as cache]
            [cmr.common.services.errors :as svc-errors]
            [cmr.common.util :as util]
            [cmr.common.mime-types :as mt]
            [cmr.common.xml :as cx]
            [cmr.search.services.query-service :as query-svc]
            [cmr.system-trace.http :as http-trace]
            [cmr.search.services.parameters.legacy-parameters :as lp]
            [cmr.search.services.messages.common-messages :as msg]
            [cmr.search.services.health-service :as hs]
            [cmr.acl.core :as acl]
            [cmr.common-app.api.routes :as common-routes]
            [cmr.common-app.api-docs :as api-docs]

            ;; Result handlers
            ;; required here to avoid circular dependency in query service
            [cmr.search.results-handlers.csv-results-handler]
            [cmr.search.results-handlers.atom-results-handler]
            [cmr.search.results-handlers.atom-json-results-handler]
            [cmr.search.results-handlers.reference-results-handler]
            [cmr.search.results-handlers.kml-results-handler]
            [cmr.search.results-handlers.metadata-results-handler]
            [cmr.search.results-handlers.query-specified-results-handler]
            [cmr.search.results-handlers.timeline-results-handler]
            [cmr.search.results-handlers.opendata-results-handler]

            ;; ACL support. Required here to avoid circular dependencies
            [cmr.search.services.acls.collection-acls]
            [cmr.search.services.acls.granule-acls]))

(def TOKEN_HEADER "echo-token")
(def CONTENT_TYPE_HEADER "Content-Type")
(def HITS_HEADER "CMR-Hits")
(def TOOK_HEADER "CMR-Took")
(def CMR_GRANULE_COUNT_HEADER "CMR-Granule-Hits")
(def CMR_COLLECTION_COUNT_HEADER "CMR-Collection-Hits")
(def CORS_ORIGIN_HEADER
  "This CORS header is to restrict access to the resource to be only from the defined origins,
  value of \"*\" means all request origins have access to the resource"
  "Access-Control-Allow-Origin")
(def CORS_METHODS_HEADER
  "This CORS header is to define the allowed access methods"
  "Access-Control-Allow-Methods")
(def CORS_CUSTOM_HEADERS_HEADER
  "This CORS header is to define the allowed custom headers"
  "Access-Control-Allow-Headers")
(def CORS_MAX_AGE_HEADER
  "This CORS header is to define how long in seconds the response of the preflight request can be cached"
  "Access-Control-Max-Age")

(def search-result-supported-mime-types
  "The mime types supported by search."
  #{mt/any
    mt/xml
    mt/json
    mt/ummjson
    mt/echo10
    mt/dif
    mt/dif10
    mt/atom
    mt/iso
    mt/opendata
    mt/csv
    mt/kml
    mt/native})

(def supported-provider-holdings-mime-types
  "The mime types supported by search."
  #{mt/any
    mt/xml
    mt/json
    mt/csv})

(def supported-concept-id-retrieval-mime-types
  #{mt/any
    mt/xml ; allows retrieving native format
    mt/native ; retrieve in native format
    mt/atom
    mt/json
    mt/echo10
    mt/iso
    mt/iso-smap
    mt/dif
    mt/dif10})

(defn- search-response-headers
  "Generate headers for search response."
  [content-type results]
  (merge {CONTENT_TYPE_HEADER (mt/with-utf-8 content-type)
          CORS_ORIGIN_HEADER "*"}
         (when (:hits results) {HITS_HEADER (str (:hits results))})
         (when (:took results) {TOOK_HEADER (str (:took results))})))

(defn- concept-type-path-w-extension->concept-type
  "Parses the concept type and extension (\"granules.echo10\") into the concept type"
  [concept-type-w-extension]
  (-> #"^([^s]+)s(?:\..+)?"
      (re-matches concept-type-w-extension)
      second
      keyword))

(defn- path-w-extension->mime-type
  "Parses the search path with extension and returns the requested mime-type or nil if no extension
  was passed."
  [search-path-w-extension valid-mime-types]
  (when-let [extension (second (re-matches #"[^.]+(?:\.(.+))$" search-path-w-extension))]
    (or (valid-mime-types (mt/format->mime-type (keyword extension)))
        (svc-errors/throw-service-error
          :bad-request (format "The URL extension [%s] is not supported." extension)))))

(defn- path-w-extension->concept-id
  "Parses the path-w-extension to remove the concept id from the beginning"
  [path-w-extension]
  (second (re-matches #"([^\.]+?)(?:/[0-9]+)?(?:\..+)?" path-w-extension)))

(defn- path-w-extension->revision-id
  "Parses the path-w-extension to extract the revision id"
  [path-w-extension]
  (when-let [revision-id (nth (re-matches #"([^\.]+)/([^\.]+)(?:\..+)?" path-w-extension) 2)]
    (try
      (when revision-id
        (Integer/parseInt revision-id))
      (catch NumberFormatException e
        (svc-errors/throw-service-error
          :invalid-data
          (format "Revision id [%s] must be an integer greater than 0." revision-id))))))

(defn- get-search-results-format
  "Returns the requested search results format parsed from headers or from the URL extension"
  ([path-w-extension headers default-mime-type]
   (get-search-results-format
     path-w-extension headers search-result-supported-mime-types default-mime-type))
  ([path-w-extension headers valid-mime-types default-mime-type]
   (mt/mime-type->format
     (or (path-w-extension->mime-type path-w-extension valid-mime-types)
         (mt/extract-header-mime-type valid-mime-types headers "accept" true)
         (mt/extract-header-mime-type valid-mime-types headers "content-type" false))
     default-mime-type)))

(defn process-params
  "Processes the parameters by removing unecessary keys and adding other keys like result format."
  [params path-w-extension headers default-mime-type]
  (-> params
      (dissoc :path-w-extension)
      (dissoc :token)
      (assoc :result-format (get-search-results-format path-w-extension headers default-mime-type))))

(defn- search-response
  "Generate the response map for finding concepts by params or AQL."
  [{:keys [results result-format] :as response}]
  {:status 200
   :headers (search-response-headers (mt/format->mime-type result-format) response)
   :body results})

(def options-response
  "Generate the response map for finding concepts by params or AQL."
  {:status 200
   :headers {CONTENT_TYPE_HEADER "text/plain; charset=utf-8"
             CORS_ORIGIN_HEADER "*"
             CORS_METHODS_HEADER "POST, GET, OPTIONS"
             CORS_CUSTOM_HEADERS_HEADER "Echo-Token"
             ;; the value in seconds for how long the response to the preflight request can be cached
             ;; set to 30 days
             CORS_MAX_AGE_HEADER "2592000"}})

(defn- find-concepts-by-json-query
  "Invokes query service to parse the JSON query, find results and return the response."
  [context path-w-extension params headers json-query]
  (let [concept-type (concept-type-path-w-extension->concept-type path-w-extension)
        params (process-params params path-w-extension headers mt/xml)
        _ (info (format "Searching for concepts from client %s in format %s with JSON %s and query parameters %s."
                        (:client-id context) (:result-format params) json-query params))
        results (query-svc/find-concepts-by-json-query context concept-type params json-query)]
    (search-response results)))

(defn- find-concepts-by-parameters
  "Invokes query service to parse the parameters query, find results, and return the response"
  [context path-w-extension params headers body]
  (let [concept-type (concept-type-path-w-extension->concept-type path-w-extension)
        context (assoc context :query-string body)
        params (process-params params path-w-extension headers mt/xml)
        result-format (:result-format params)
        _ (info (format "Searching for %ss from client %s in format %s with params %s."
                        (name concept-type) (:client-id context) result-format
                        (pr-str params)))
        search-params (lp/process-legacy-psa params body)
        results (query-svc/find-concepts-by-parameters context concept-type search-params)]
    (search-response results)))

(defn- find-concepts
  "Invokes query service to find results and returns the response"
  [context path-w-extension params headers body]
  (let [content-type-header (get headers (str/lower-case CONTENT_TYPE_HEADER))]
    (cond
      (= mt/json content-type-header)
      (find-concepts-by-json-query context path-w-extension params headers body)

      (or (nil? content-type-header) (= mt/form-url-encoded content-type-header))
      (find-concepts-by-parameters context path-w-extension params headers body)

      :else
      {:status 415
       :headers {CORS_ORIGIN_HEADER "*"}
       :body (str "Unsupported content type ["
                  (get headers (str/lower-case CONTENT_TYPE_HEADER)) "]")})))


(defn- get-granules-timeline
  "Retrieves a timeline of granules within each collection found."
  [context path-w-extension params headers query-string]
  (let [params (process-params params path-w-extension headers mt/json)
        _ (info (format "Getting granule timeline from client %s with params %s."
                        (:client-id context) (pr-str params)))
        search-params (lp/process-legacy-psa params query-string)
        results (query-svc/get-granule-timeline context search-params)]
    {:status 200
     :headers {CORS_ORIGIN_HEADER "*"}
     :body results}))

(defn- find-concepts-by-aql
  "Invokes query service to parse the AQL query, find results and returns the response"
  [context path-w-extension params headers aql]
  (let [params (process-params params path-w-extension headers mt/xml)
        _ (info (format "Searching for concepts from client %s in format %s with AQL: %s and query parameters %s."
                        (:client-id context) (:result-format params) aql params))
        results (query-svc/find-concepts-by-aql context params aql)]
    (search-response results)))

(defn- find-concept-by-cmr-concept-id
  "Invokes query service to find concept metadata by cmr concept id (and possibly revision id)
  and returns the response"
  [context path-w-extension params headers]
  (let [concept-id (path-w-extension->concept-id path-w-extension)
        revision-id (path-w-extension->revision-id path-w-extension)]
    (if revision-id
      (let [supported-mime-types (disj supported-concept-id-retrieval-mime-types mt/atom mt/json)
            result-format (get-search-results-format path-w-extension headers
                                                     supported-mime-types
                                                     mt/xml)
            params (assoc params :result-format result-format)]
        (info (format "Search for concept with cmr-concept-id [%s] and revision-id [%s]"
                      concept-id
                      revision-id))
        (search-response (query-svc/find-concept-by-id-and-revision context
                                                                    result-format
                                                                    concept-id
                                                                    revision-id)))
      (let [result-format (get-search-results-format path-w-extension headers
                                                     supported-concept-id-retrieval-mime-types
                                                     mt/xml)
            params (assoc params :result-format result-format)]
        (info (format "Search for concept with cmr-concept-id [%s]" concept-id))
        (search-response (query-svc/find-concept-by-id context result-format concept-id))))))

(defn- get-provider-holdings
  "Invokes query service to retrieve provider holdings and returns the response"
  [context path-w-extension params headers]
  (let [params (process-params params path-w-extension headers mt/json)
        _ (info (format "Searching for provider holdings from client %s in format %s with params %s."
                        (:client-id context) (:result-format params) (pr-str params)))
        [provider-holdings provider-holdings-formatted]
        (query-svc/get-provider-holdings context params)
        collection-count (count provider-holdings)
        granule-count (reduce + (map :granule-count provider-holdings))]
    {:status 200
     :headers {CONTENT_TYPE_HEADER (str (mt/format->mime-type (:result-format params)) "; charset=utf-8")
               CMR_COLLECTION_COUNT_HEADER (str collection-count)
               CMR_GRANULE_COUNT_HEADER (str granule-count)
               CORS_ORIGIN_HEADER "*"}
     :body provider-holdings-formatted}))

(defn- find-tiles
  "Retrieves all the tiles which intersect the input geometry"
  [context params]
  (let [results (query-svc/find-tiles-by-geometry context params)]
    {:status 200
     :headers {CONTENT_TYPE_HEADER (mt/with-utf-8 mt/json)}
     :body results}))

(defn- build-routes [system]
  (routes
    (context (get-in system [:search-public-conf :relative-root-url]) []
      ;; Add routes for API documentation
      (api-docs/docs-routes
        (get-in system [:search-public-conf :protocol])
        (get-in system [:search-public-conf :relative-root-url])
        "public/index.html")

      ;; Retrieve by cmr concept id or concept id and revision id
      (context ["/concepts/:path-w-extension" :path-w-extension #"[A-Z][0-9]+-[0-9A-Z_]+.*"] [path-w-extension]
        ;; OPTIONS method is needed to support CORS when custom headers are used in requests to the endpoint.
        ;; In this case, the Echo-Token header is used in the GET request.
        (OPTIONS "/" req options-response)
        (GET "/" {params :params headers :headers context :request-context}
          (find-concept-by-cmr-concept-id context path-w-extension params headers)))

      ;; Find concepts
      (context ["/:path-w-extension" :path-w-extension #"(?:(?:granules)|(?:collections))(?:\..+)?"] [path-w-extension]
        (OPTIONS "/" req options-response)
        (GET "/" {params :params headers :headers context :request-context query-string :query-string}
          (find-concepts context path-w-extension params headers query-string))
        ;; Find concepts - form encoded or JSON
        (POST "/" {params :params headers :headers context :request-context body :body-copy}
          (find-concepts context path-w-extension params headers body)))

      ;; Granule timeline
      (context ["/granules/:path-w-extension" :path-w-extension #"(?:timeline)(?:\..+)?"] [path-w-extension]
        (OPTIONS "/" req options-response)
        (GET "/" {params :params headers :headers context :request-context query-string :query-string}
          (get-granules-timeline context path-w-extension params headers query-string))
        (POST "/" {params :params headers :headers context :request-context body :body-copy}
          (get-granules-timeline context path-w-extension params headers body)))

      ;; AQL search - xml
      (context ["/concepts/:path-w-extension" :path-w-extension #"(?:search)(?:\..+)?"] [path-w-extension]
        (OPTIONS "/" req options-response)
        (POST "/" {params :params headers :headers context :request-context body :body-copy}
          (find-concepts-by-aql context path-w-extension params headers body)))

      ;; Provider holdings
      (context ["/:path-w-extension" :path-w-extension #"(?:provider_holdings)(?:\..+)?"] [path-w-extension]
        (OPTIONS "/" req options-response)
        (GET "/" {params :params headers :headers context :request-context}
          (get-provider-holdings context path-w-extension params headers)))

      ;; Resets the application back to it's initial state.
      (POST "/reset" {:keys [request-context params headers]}
        (acl/verify-ingest-management-permission request-context)
        (cache/reset-caches request-context)
        {:status 204})

      ;; add routes for accessing caches
      common-routes/cache-api-routes

      ;; add routes for checking health of the application
      (common-routes/health-api-routes hs/health)

      (GET "/tiles" {params :params context :request-context}
        (find-tiles context params)))

    (route/not-found "Not Found")))

(defn copy-of-body-handler
  "Copies the body into a new attribute called :body-copy so that after a post of form content type
  the original body can still be read. The default ring params reads the body and parses it and we
  don't have access to it."
  [f]
  (fn [request]
    (let [^String body (slurp (:body request))]
      (f (assoc request
                :body-copy body
                :body (java.io.ByteArrayInputStream. (.getBytes body)))))))

(defn find-query-str-mixed-arity-param
  "Return the first parameter that has mixed arity, i.e., appears with both single and multivalued in
  the query string. e.g. foo=1&foo[bar]=2 is mixed arity, so is foo[]=1&foo[bar]=2. foo=1&foo[]=2 is
  not. Parameter with mixed arity will be flagged as invalid later."
  [query-str]
  (when query-str
    (let [query-str (-> query-str
                        (str/replace #"%5B" "[")
                        (str/replace #"%5D" "]")
                        (str/replace #"\[\]" ""))]
      (last (some #(re-find % query-str)
                  [#"(^|&)(.*?)=.*?\2\["
                   #"(^|&)(.*?)\[.*?\2="])))))

(defn mixed-arity-param-handler
  "Detect query string with mixed arity and throws a 400 error. Mixed arity param is when a single
  value param is mixed with multivalue. One specific case of this is for improperly expressed options
  in the query string, e.g., granule_ur=*&granule_ur[pattern]=true. Ring parameter handling throws
  500 error when it happens. This middleware handler returns a 400 error early to avoid the 500 error
  from Ring."
  [f]
  (fn [request]
    (when-let [mixed-param (find-query-str-mixed-arity-param (:query-string request))]
      (svc-errors/throw-service-errors
        :bad-request
        [(msg/mixed-arity-parameter-msg mixed-param)]))
    (f request)))

(defn default-error-format-fn
  "Determine the format that errors should be returned in based on the request URI."
  [{:keys [uri]} _e]
  (if (re-find #"caches" uri)
    mt/json
    mt/xml))

(defn make-api [system]
  (-> (build-routes system)
      acl/add-authentication-handler
      (http-trace/build-request-context-handler system)
      keyword-params/wrap-keyword-params
      nested-params/wrap-nested-params
      errors/invalid-url-encoding-handler
      mixed-arity-param-handler
      (errors/exception-handler default-error-format-fn)
      common-routes/pretty-print-response-handler
      params/wrap-params
      copy-of-body-handler))
