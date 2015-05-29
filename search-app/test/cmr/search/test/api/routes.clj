(ns cmr.search.test.api.routes
  (:require [clojure.test :refer :all]
            [cmr.search.api.routes :as r])
  (:use ring.mock.request))

(def ^:private api (#'cmr.search.api.routes/build-routes
                     {:search-public-conf {:protocol "https" :relative-root-url "/search"}}))

(defn- substring?
  [test-value string]
  (.contains string test-value))

(deftest cmr-welcome-page
  (testing "visited on a path without a trailing slash"
    (let [response (api (request :get "https://cmr.example.com/search"))]
      (testing "redirects permanently to the version with a trailing slash"
        (is (= (:status response) 301))
        (is (= (:headers response) {"Location" "https://cmr.example.com/search/"})))))

  (testing "visited on a path with a trailing slash"
    (let [response (api (request :get "https://cmr.example.com/search/"))]
      (testing "produces a HTTP 200 success response"
        (is (= (:status response) 200)))
      (testing "returns the welcome page HTML"
        (is (substring? "The CMR Search API" (:body response)))))))

(deftest test-404
  (testing "a 404 is returned for a missing document"
    (is (= 404 (:status (api (request :get "https://cmr.example.com/search/site/NOT-A-PAGE.html")))))))

(deftest cmr-api-documentation-page
  (let [response (api (request :get "https://cmr.example.com/search/site/search_api_docs.html"))]
    (testing "uses the incoming host and scheme for its documentation endpoints"
      (is (substring? "https://cmr.example.com/search/collections" (:body response))))))

(deftest find-query-str-mixed-arity-param
  (testing "find-query-str-mixed-arity-param finds parameter with mixed arity correctly"
    (are [query-str found]
         (= found
            (r/find-query-str-mixed-arity-param query-str))

         "foo=1&foo[bar]=2" "foo"
         "foo[]=1&foo[bar]=2" "foo"
         "foo=0&foo[]=1&foo[]=2" nil
         "foo[bar]=1&foo[x]=2" nil
         "foo=1&options[foo][pattern]=true" nil
         "foo[]=1&options[foo][pattern]=true" nil)))

(deftest get-search-results-format-test
  (testing "format from headers"
    (are [path headers default-mime-type expected-format]
         (=  expected-format (#'r/get-search-results-format path headers default-mime-type))
         ;; Accept header
         "search/collections" {"accept" "application/echo10+xml"} "application/xml" :echo10
         "search/collections" {"accept" "application/dif+xml"} "application/xml" :dif
         "search/collections" {"accept" "application/dif10+xml"} "application/xml" :dif10
         "search/collections" {"accept" "application/iso19115+xml"} "application/xml" :iso19115
         "search/collections" {"accept" "application/xml"} "application/json" :xml
         "search/collections" {"accept" "application/json"} "application/xml" :json
         "search/collections" {"accept" "application/atom+xml"} "application/xml" :atom
         "search/collections" {"accept" "application/opendata+json"} "application/xml" :opendata
         "search/collections" {"accept" "application/vnd.google-earth.kml+xml"} "application/xml" :kml
         "search/collections" {"accept" "text/csv"} "application/xml" :csv
         "search/collections" {"accept" "application/metadata+xml"} "application/xml" :native

         ;; Content-Type header
         "search/collections" {"content-type" "application/echo10+xml"} "application/xml" :echo10
         "search/collections" {"content-type" "application/dif+xml"} "application/xml" :dif
         "search/collections" {"content-type" "application/dif10+xml"} "application/xml" :dif10
         "search/collections" {"content-type" "application/iso19115+xml"} "application/xml" :iso19115
         "search/collections" {"content-type" "application/xml"} "application/json" :xml
         "search/collections" {"content-type" "application/json"} "application/xml" :json
         "search/collections" {"content-type" "application/atom+xml"} "application/xml" :atom
         "search/collections" {"content-type" "application/opendata+json"} "application/xml" :opendata
         "search/collections" {"content-type" "application/vnd.google-earth.kml+xml"} "application/xml" :kml
         "search/collections" {"content-type" "text/csv"} "application/xml" :csv
         "search/collections" {"content-type" "application/metadata+xml"} "application/xml" :native

         ;; Both headers - Accept header wins out
         "search/collections" {"accept" "application/echo10+xml" "content-type" "application/json"} "application/xml" :echo10
         "search/collections" {"accept" "application/dif+xml" "content-type" "application/json"} "application/xml" :dif
         "search/collections" {"accept" "application/dif10+xml" "content-type" "application/json"} "application/xml" :dif10
         "search/collections" {"accept" "application/iso19115+xml" "content-type" "application/json"} "application/xml" :iso19115
         "search/collections" {"accept" "application/xml" "content-type" "application/json"} "application/json" :xml
         "search/collections" {"accept" "application/json" "content-type" "application/xml"} "application/xml" :json
         "search/collections" {"accept" "application/atom+xml" "content-type" "application/json"} "application/xml" :atom
         "search/collections" {"accept" "application/opendata+json" "content-type" "application/json"} "application/xml" :opendata
         "search/collections" {"accept" "application/vnd.google-earth.kml+xml" "content-type" "application/json"} "application/xml" :kml
         "search/collections" {"accept" "text/csv" "content-type" "application/json"} "application/xml" :csv
         "search/collections" {"accept" "application/metadata+xml" "content-type" "application/json"} "application/xml" :native))

  (testing "format from extension"
    (are [path headers default-mime-type expected-format]
         (=  expected-format (#'r/get-search-results-format path headers default-mime-type))
         "search/collections.echo10" {"accept" "application/dif+xml"} "application/xml" :echo10
         "search/collections.dif" {"accept" "application/json"} "application/xml" :dif
         "search/collections.dif10" {"accept" "application/json"} "application/xml" :dif10
         "search/collections.iso19115" {"accept" "application/json"} "application/xml" :iso19115
         "search/collections.xml" {"accept" "application/dif+xml"} "application/json" :xml
         "search/collections.json" {"accept" "application/dif+xml"} "application/xml" :json
         "search/collections.atom" {"accept" "application/json"} "application/xml" :atom
         "search/collections.opendata" {"accept" "application/json"} "application/xml" :opendata
         "search/collections.kml" {"accept" "application/json"} "application/xml" :kml
         "search/collections.csv" {"accept" "application/json"} "application/xml" :csv
         "search/collections.native" {"accept" "application/json"} "application/xml" :native))

  (testing "using default format"
    (are [path headers default-mime-type expected-format]
         (=  expected-format (#'r/get-search-results-format path headers default-mime-type))
         "search/collections" {} "application/echo10+xml" :echo10
         "search/collections" {} "application/dif+xml" :dif
         "search/collections" {} "application/dif10+xml" :dif10
         "search/collections" {} "application/iso19115+xml" :iso19115
         "search/collections" {} "application/xml" :xml
         "search/collections" {} "application/json" :json
         "search/collections" {} "application/atom+xml" :atom
         "search/collections" {} "application/opendata+json" :opendata
         "search/collections" {} "application/vnd.google-earth.kml+xml" :kml
         "search/collections" {} "text/csv" :csv
         "search/collections" {} "application/metadata+xml" :native)))