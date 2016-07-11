(ns cmr.search.data.metadata-retrieval.revision-format-map
  "TODO"
  (require [cmr.common.util :as u]
           [clojure.string :as str]
           [cmr.common.log :as log :refer (debug info warn error)]
           [cmr.search.services.result-format-helper :as rfh]
           [cmr.search.data.metadata-retrieval.metadata-transformer :as metadata-transformer]))

;; TODO unit test this whole namespace

(def non-metadata-fields
  #{:concept-id :revision-id :native-format :compressed? :size})

(def key-sorted-revision-format-map
  (u/key-sorted-map [:concept-id :revision-id :native-format :echo10 :dif :dif10 :iso19115]))

(defn map-metadata-values
  "Applies a function over the metadata fields of the revision format map"
  [f rfm]
  (into {} (mapv (fn [entry]
                   (let [k (key entry)]
                     (if (contains? non-metadata-fields k)
                       entry
                       [k (f (val entry))])))
                 rfm)))

(defn with-size
  "Adds a size field to the revision format map by calculating the sizes of the individual cached
   metadata."
  [rfm]
  (let [metadata-fields (filterv (complement non-metadata-fields) (keys rfm))
        size (reduce + (map (comp count rfm) metadata-fields))]
    (assoc rfm :size size)))

(defn gzip-revision-format-map
  "TODO"
  [revision-format-map]
  (if (:compressed? revision-format-map)
    revision-format-map
    (-> (map-metadata-values u/string->gzip-bytes revision-format-map)
        (assoc :compressed? true)
        with-size)))

(defn ungzip-revision-format-map
  "TODO"
  [revision-format-map]
  (if (:compressed? revision-format-map)
    (-> (map-metadata-values u/gzip-bytes->string revision-format-map)
        (assoc :compressed? false)
        (dissoc :size))
    revision-format-map))

(defn prettify
  [revision-format-map]
  (let [uncompressed-map (dissoc (ungzip-revision-format-map revision-format-map) :compressed?)
        trim-xml (fn [xml]
                   (-> xml
                       (str/replace "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" "")
                       (subs 0 30)
                       (str "...")))
        trimmed (map-metadata-values trim-xml uncompressed-map)
        keys-as-keywords (into {} (mapv (fn [[k v]]
                                          (if (map? k)
                                            [(keyword (str (name (:format k)) "_" (name (:version k)))) v]
                                            [k v]))
                                        trimmed))]
    (into key-sorted-revision-format-map keys-as-keywords)))


(defn get-metadata-in-format
  "Gets cached metadata in the specified format from the revision format map. Assumes the format is
   present." ;; TODO consider making it work even if not present
  [target-format revision-format-map]
  (let [target-format (if (= target-format :native)
                        (:native-format revision-format-map)
                        target-format)
        metadata (get revision-format-map target-format)]
    (if (:compressed? revision-format-map)
      (u/gzip-bytes->string metadata)
      metadata)))


;; TODO make this work with a revision format map that's zipped or not zipped.
;; Add compressed flag in the two gzip functions below.
(defn revision-format-map->concept
  "Converts a revision format map into a concept map using the target format. Assumes target format
   is present in revision format map."
  [target-format revision-format-map]
  {:pre [(or (get revision-format-map target-format)
             (= :native target-format))]}
  (let [{:keys [concept-id revision-id]} revision-format-map]
    {:concept-id concept-id
     :revision-id revision-id
     :concept-type :collection
     :metadata (get-metadata-in-format target-format revision-format-map)
     :format (if (= :native target-format)
               (rfh/search-result-format->mime-type (:native-format revision-format-map))
               (rfh/search-result-format->mime-type target-format))}))

;; TODO make sure to test passing in :native here
(defn concept->revision-format-map
  "Converts a concept into a revision format map. See namespace documentation for details."
  [context concept target-format-set]
  (let [{:keys [concept-id revision-id metadata] concept-mime-type :format} concept
        native-format (rfh/mime-type->search-result-format concept-mime-type)
        base-map {:concept-id concept-id
                  :revision-id revision-id
                  :native-format native-format
                  native-format metadata}
        ;; Translate to all the cached formats except the native format.
        target-formats (disj target-format-set native-format :native)
        formats-map (metadata-transformer/transform-to-multiple-formats
                     context concept target-formats)]
    (merge base-map formats-map)))

(defn add-additional-format
  "Adds an additional stored format to the revision format map."
  [context target-format revision-format-map]
  (let [concept (revision-format-map->concept :native revision-format-map)
        transformed (metadata-transformer/transform context concept target-format)]
    (if (:compressed? revision-format-map)
     (assoc revision-format-map target-format (u/string->gzip-bytes transformed))
     (assoc revision-format-map target-format transformed))))
