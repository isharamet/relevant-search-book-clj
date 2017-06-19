(ns relevant-search-book-clj.ch01
  (:require [cheshire.core :as cheshire]
            [qbits.spandex :as spandex]
            [qbits.spandex.utils :as spandex-utils]
            [clojure.core.async :as async]))

(def tmdb
  (with-open [rdr (clojure.java.io/reader "resources/tmdb.json")]
    (cheshire/parse-stream rdr true)))

(def es-client
  (spandex/client {:hosts ["http://localhost:9200"]}))

(def index-data
  (reduce
    (fn [acc movie]
      (let [[id {:keys [title overview tagline]}] movie]
        (conj
          acc
          {:index {:_index "tmdb", :_type "movie", :_id id}}
          {:title title, :overview overview, :tagline tagline})))
    []
    tmdb))

(defn reindex
  ([] (reindex nil))
  ([mappings]
   (let [settings {:settings {:number_of_shards 1}}
         body (if (nil? mappings)
                settings
                (assoc settings :mappings mappings))]
     (do
       (clojure.pprint/pprint body)
       (try
         (spandex/request
           es-client
           {:url "/tmdb"
            :method :delete})
         (catch Exception e
           (println "Unable to delete 'tmdb' index")))
       (spandex/request
         es-client
         {:url "/tmdb"
          :method :put
          :body body})
       (let [{:keys [input-ch output-ch]}
             (spandex/bulk-chan
               es-client
               {:flush-threshold 100
                :flush-interval 5000
                :max-concurrent-requests 3})]
         (async/put! input-ch index-data))
       (future (loop [] (async/<!! (:output-ch es-client))))))))

(defn print-search-results
  [rs]
  (doseq [hit (get-in rs [:body :hits :hits])]
    (println (hit :_score) (get-in hit [:_source :title]))))

(defn search
  [q]
  (spandex/request-async
    es-client
    {:url "/tmdb/movie/_search?explain"
     :method :get
     :body {:query
            {:multi_match
             {:query q
              :fields ["title^10" "overview"]}}}
     :success print-search-results
     :error   (fn [ex] (println ex))}))

(defn explain
  [q]
  (spandex/request-async
    es-client
    {:url "/tmdb/movie/_validate/query?explain"
     :method :get
     :body {:query
            {:multi_match
             {:query q
              :fields ["title^10" "overview"]}}}
     :success (fn [rs] (clojure.pprint/pprint (:body rs)))
     :error   (fn [ex] (println ex))}))

(defn analyze
  ([q] (analyze q nil))
  ([q field]
    (let [body {:analyzer :standard
                :text q}
          body (if (nil? field)
                 body
                 (assoc body :field field))]
      (spandex/request-async
        es-client
        {:url "/tmdb/_analyze"
         :method :get
         :body body
         :success (fn [rs] (clojure.pprint/pprint (:body rs)))
         :error   (fn [ex] (println ex))}))))

(defn get-mappings
  []
  (spandex/request-async
    es-client
    {:url "tmdb/_mappings"
     :method :get
     :success (fn [rs] (clojure.pprint/pprint (:body rs)))
     :error   (fn [ex] (println ex))}))

(reindex)

(search "basketball with cartoon aliens")

(explain "basketball with cartoon aliens")

(analyze "Fire with Fire")

;; ---

(reindex
  {:movie
   {:properties
    {:title
     {:type :text
      :analyzer :english}
     :overview
     {:type :text
      :analyzer :english}}}})

(get-mappings)

(analyze "Fire with Fire" :title)