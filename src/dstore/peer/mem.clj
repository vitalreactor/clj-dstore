(ns dstore.peer.mem
  (:require [dstore.api :as ds])
  (:import [clojure.lang Seqable IFn IKeywordLookup
            ILookup IDeref IHashEq IObj]
           [dstore.api StorePeer
            KeyValueStore AsyncKeyValueStore LogStore MutableStore
            Indexed Filterable Excisable]))

;;
;; MemStore: A trivial, non-distributed in-memory 'index' store
;; ------------------------------------------------------------
;;
;; - Not optimized for larger in-memory datasets
;; - Good for short-term unit testing


;; Immutable Memory Index Value
;; ------------------------------------------------------------

(defn- key-filter
  ([start]
     (fn [[key value]]
       (>= (compare key start) 0)))
  ([start end]
     (fn [[key value]]
       (and (>= (compare key start) 0)
            (< (compare key end) 0)))))

(defn- value-filter
  ([spec]
     (fn [[key value]]
       (every? (fn [[mk mv]]
                 (and (map? value)
                      (= mv (value mk))))
               spec))))

(deftype MemoryIndex [root filters]
  ;; Filtering
  Indexed
  (scan [this start]
    (MemoryIndex. root (cons (key-filter start) filters)))
  (scan [this start end]
    (MemoryIndex. root (cons (key-filter start end) filters)))
  
  Filterable
  (filter [this spec]
    (MemoryIndex. root (cons (value-filter spec) filters)))

  ;; Value access
  IFn
  (invoke [this key]
    (get root key))
  (applyTo [this args]
    (clojure.lang.AFn/applyToHelper this args))
                   
  ILookup
  (valAt [this key]    (@root key))
  (valAt [this key nf] (if-let [val (@root key)] val nf))

;;  IKeywordLookup
;;  (getLookupThunk [this k]
;;    (let [root root]
;;      (reify ILookupThunk
;;        (get [thunk target]
;;          (if 
  
  Seqable
  (seq [this]
    (let [idxfilt (apply comp filters)]
      (->> (seq root)
           (sort-by first)
           (filter idxfilt)))))
        
(defmethod print-method MemoryIndex [idx w]
  (.write w (format "#MemoryIndex{}")))


;;
;; A memory store
;; ------------------------------------------------------------

(defrecord MemoryIndexStore [name root]
  IDeref
  (deref [this]
    (with-meta
      (->MemoryIndex @root nil))
      (meta this))
  
  KeyValueStore
  (insert [this key value]
    (assert value)
    (->MemoryIndex (swap! root assoc key value) nil))
  (insert-multi [this kv-map]
    (assert (every? #(nil? (this %)) (keys kv-map)))
    (->MemoryIndex (swap! root merge kv-map) nil))
  (update! [this key f]
    (ds/update! this key f []))
  (update! [this key f args]
    (let [old (@root key)]
      (->MemoryIndex
       (apply swap! root (fn [old] (apply f old args)))
       nil)))
                                 
  AsyncKeyValueStore
  (insert-async [this key value]
    (future (ds/insert this key value)))
  (insert-multi-async [this kv-map]
    (future (ds/insert-multi this kv-map)))
  (update-async! [this key fn]
    (future (ds/update! this key fn [])))
  (update-async! [this key fn args]
    (future (ds/update! this key fn args))))

(defmethod print-method MemoryIndexStore [store w]
  (.write w (format "#MemoryIndexStore{:name %s :major %s}"
                    (:name store)
                    (get-in (meta store) [:schema :major]))))

;;
;; The root peer for simple in-memory implementations
;; --------------------------------------------------

(defrecord MemoryPeer [roots]
  StorePeer
  (stores [this]
    (map :name @roots))
  
  (store [this name]
    (if-let [store (@roots name)]
      store
      (throw (Throwable. (format "Store not found for: '%s'." name)))))
  
  (store [this type name]
    (ds/store this type name {}))
    
  (store [this type name schema]
    (if-let [store (@roots name)]
      store
      (let [store (with-meta
                    (->MemoryIndexStore name (atom {}))
                    {:name name :schema schema})]
        (swap! roots assoc name store)
        (@roots name))))

  (rem-store [this ref]
    (let [name (if (= (type ref) MemoryIndexStore)
                 (:name ref) ref)]
      (swap! roots dissoc name)
      this)))

(defmethod print-method MemoryPeer [peer w]
  (.write w (format "#MemoryPeer[tables:%s]" (count @(:roots peer)))))

(defmethod ds/create-peer* :mem [config]
  (->MemoryPeer (atom {})))
  
