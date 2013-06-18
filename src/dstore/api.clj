(ns dstore.api
  (:refer-clojure :exclude [filter]))

;; Factory capturing backend connections
;; -----------------------------------------
;; - Generates Stores tied to specific tables, etc of a store.
;; - Can have multiple Stores per peer.

(def ^:private peer-dispatcher :type)

(defmulti create-peer* 
  "
  Internal backend interface called by the peer function
  after ensuring that the backend libraries are loaded off
  the classpath.
  "
  #'peer-dispatcher)
  
(defn peer
  "User interface to generate a peer for a particular storage backend

  Required configuration parameters
  :type = :mem | :dynamo"
  [config]
  (case (:type config)
    :mem (require '[dstore.peer.mem]))
  (create-peer* config))

(defprotocol StorePeer
  (store [this name] [this type name] [this type name schema]
    "Retrieve a store of the requested type associated with the
     provided names.  If schema is provided, apply the schema
     to the store to setup efficient indexing.  Indexing is
     currently a static decision because it is often implemented
     by constructing ordered row and/or column keys.  If the store
     already exists, it is just returned.  If a schema
     doesn't match the stored schema, an error is produced.  If none
     exists, store returns an error")
  (stores [this] [this type]
    "Return all the names of stores managed by this peer")
  (rem-store [this name]
    "Reclaim all backend storage associated with the named store"))


;; Store
;; ----------------------------------------
;; - IDeref to get current values
;; - IObj for metadata which includes schema

(defprotocol KeyValueStore
  (insert [this key value]
    "Returns an Index values representing the new state of the store.
     Error if the value exists and is not identical or the value does
     not populate the index appropriately.")
  (insert-multi [this kv-map]
    "Batched insert")
  (update! [this key fn] [this key fn args] ;; [this key value fn & args]
    "Applies the fn to the old value to produce the new value.  Stores all have different
     models of applying changes, so this provides the most flexibility plus hides versioning
     control under the hood.  Version conflicts lead to exceptions."))
   
(defprotocol AsyncKeyValueStore
  (insert-async [this key value]
    "Asynchronous insert, returns a future that will contain the new index")
  (insert-multi-async [this kv-map]
    "Asynchronous insert, returns a future that will contain the new index")
  (update-async! [this key fn] [this key fn args]
    "Asynchronous update, returns a future that contains the new index or an
     error if one occcurs"))

(defprotocol LogStore
  (append [this value]
    "Append the complex value to the end of the log.  The Log store has
     an internal policy for how data is organized. 
     return an index object allowing for scans")
  (append-multi [this value-seq]
    "Batch append, order of seq is order of underlying append"))

(defprotocol MutableStore
  (drop! [this index]
    "Drop all the values captured in the provided index value, assumes
     scans or filters have been applied or all data will be dropped.  To
     be used rarely because it violates the immutability of the provided
     underlying values.  If you excise, immutable values will throw exceptions
     if the values are no longer present."))


;; Immutable Index Values
;; ----------------------------------------
;; - Seqable - seq returns pairs of key/value
;;   Performance semantics?  Streaming, one at a time, chunked?
;; - ILookup, IKeywordLookup - Simple get semantics
;; - 
;; - IHashEq, IObj?
;; - vals return values

(defprotocol Indexed
  (scan [this start-spec] [this start-spec end-spec]
    "Returns a new Index reduced to the range of values dilineated by
     the provided start and end specifications.  Specifications are
     currently

     Unrecognized specifications result in an exception."))

(defprotocol Filterable
  (filter [this specification]
    "Apply the predicate fn (local) or structure (possibly remote) to
     a collection and return a filtered version of that collection")
  (is-filtered [this]
    "Returns a boolean if a filter has been applied"))

(defprotocol Excisable
  (valid-value? [this]
    "Values that have excisable stores should implement this to provide
     a test for validity if excision isn't carefully controlled in the system."))

;;(defprotocol Queryable
;;  (query [this query & args]
;;    "Many stores provide query support.  We could provide a standard
;;     query interface that supports a common query, like Mongo's structure
;;     matching or noql"))

;;
;; Utility functions for scanning logs
;;

;; Standard time helpers





