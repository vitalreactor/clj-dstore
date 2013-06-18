# DStore

Using a particular storage service's low level concepts can interfere
with writing code that effectively expresses a program's purpose.
Inspired by Datomic and Bradford Cross' Store, the Distributed Store
(DStore) library abstracts over a variety of modern distributed
key-value and document storage services to provide several immutable
collection types representing the state of those systems at different
points in time.

These abstractions were designed to support massive datasets stored on
remote data clusters and supports:

- Values that interface with familiar clojure idioms and methods 
- Distributed stores that produce sharable, immutable values
- Passing collections by reference among systems
- High volume, write-mostly modalities
- Key-level transactionality using MVCC
- Server-side filtering for improved retrieval performance

Read the Concepts Section for more details.

## Usage

(:dependencies ["com.vitalreactor/istore 0.9.0"])

### Index store type

    (require [dstore.api :as ds])

    ;; Create a peer representing a particular type of data service
	(def mempeer (ds/peer {:type :mem}))

	;; Define a new logical store
	(def store (ds/store mempeer :index "Test"))

	;; Dereference 
    (def index0 @table)
    (index0 :me) ;; => nil
   
	(def index1 (ds/insert store :me {:first "Ian" :last "Eslick"})

	(index1 :me)  ;; => {:first "Ian" :last "Eslick"}
	(:me index1)) ;; => {:first "Ian" :last "Eslick"}

    (def index2 (ds/update! store :me assoc :first "Fred"))
    
	(:me index1) ;; => {:first "Ian" :last "Eslick"}
	(:me index2) ;; => {:first "Fred" :last "Eslick"}

    (def index3 (ds/insert store :you {:first "I" :last "Freely"}))

    (s/scan index3 :a :z) 
    ;; => '([:me {:first "Fred" :last "Eslick"}] [:you {:first "I" :last "Freely"}])

	(-> index3
        (ds/scan :a :z)
        (ds/filter {:first "I"})) ;; => '([:you {:first "I" :last "Freely"}])

### Log store type
	
    (require [dstore.api :as ds]

	(def mempeer (ds/peer {:type :mem}))
 
    (def debug-log (ds/store mempeer :log "Test" {:major [:a :b :_time]}))

	(def index 
       (-> debug-log 
   	     (ds/append {:op "test" :payload {:a 1 :b 1}})
	     (ds/append {:op "test" :payload {:a 1 :b 2}})
	     (ds/append {:op "test" :payload {:a 2 :b 1}})))

	(ds/scan index [1])         ;; => [{:a 1 :b 1} {:a 1 :b 2}]
	(ds/scan index [1 2])       ;; => [{:a 1 :b 2}]
	(ds/scan index [1 0] [1 1]) ;; => [{:a 1 :b 1}]
	(ds/scan index [1] [2])     ;; => [{:a 1 :b 1} {:a 1 :b 2} {:a 2 :b 1}]

    (-> index
      (ds/scan [1])
      (ds/filter {:b 2})) ;; => [{:a 1 :b 2}]

## Concepts

Like Datomic, DStore is designed to work with scalable remote
key-value or BigTable style stores.  Unlike Datomic, it's focused on
write-centric applications with limited updating, non-coordinated
transactions (e.g. per-key atomic updates), with complex but
consistently structured values.  It supports an optimistic MVCC to
detect read-write-modify conflicts across multiple threads or machines.

Current data structure types include the Index, a map of maps that can
be efficiently retrieved using server-side ranges and/or filters.  It
also implements the Log, an append-only sequence of maps that can also
be retrieved by range and filtered.

The update! operation is just like swap! and results in a remote
read/write operation and a retry in the event of a server error.  This
is fine so long as updates are rare relative to the total write/read
volume.

I'm considering an alternative merge! operation which pushes a value
to be merged on the server (if the version doesn't conflict) but need
to see more backends first.

This library definitely does not cover all use cases for the backends
it is based on, but it will interoperate with lower-level uses of the
client libraries if you have needs that extend what is offered here.

### Primary Types

There are three major conceptual types involved in Store.

- Peer - Wraps a client driver for a storage service.  Configuration
  data is backend specific.  It can generate Stores of different types
  with optional schemas that indicate how the data is to be laid out
  for efficient queries.

- Store - Represents a logical storage endpoint.  Dereferencing a
  store produces a Value representing the current state of the Store.
  All state changes go through the Store via store-specific interfaces
  such as IKeyValueStore (insert/update), ILogStore (append), etc.

- Collection - Represents a single, immutable state of the Store.
  Collections usually implement various map-like interfaces, are
  seqable and implement some additional interfaces capturing
  server-side filtering operations depending on their type such as
  Indexed (scan) and Filtered (filter, is-filtered).  Those methods
  return new Values representing the appropriate subset of values.  A
  collection can be queries for a specific key, or as a sequence via
  seq.


### Values

Concrete collection types like MemoryIndex and HBaseIndex implement
Collection interfaces like Indexed, Filterable, and various Clojure
internal interfaces.  You can grab values from them like a map, map
over (seq index) etc.  The values returned from these collections are
native clojure maps, potentially with some extra fields or metadata
attached.

### Other Concepts

- Schema - Hints to the backing store to support efficient,
  server-side filtering of store data.

- Primitive values - All values added to Stores are maps.  The schema
  dictates the semantics of keys, which can and ought to be compound.
  The schema can populate index based on values of the maps, elements
  of the key, or both together.  Filtering is based on 

- Embedded sequence data - these structures are serialized as values,
  no filtering or searching on values in embedded sequences!

- Immutability - Some stores do not provide built in versioning the
  way HBase does.  This means the store needs to handle versioning in
  other ways; usually by having incrementing or time-based versions so
  a reference version earlier than the returned value results in an
  addition fetch to grab earlier verisons from a history table.  If
  updates are rare, this should be a modest penalty.  If a Store
  supports chunking, then this can be done on chunk fetch via
  multi-gets to batch up history lookups.

This library can be extended pretty easily with:
- New Peers for other storage services (S3, Voldemort, etc).  Anything
  supporting a CAS operation or version check will be efficient.
- New Store / Collection pairs.
- Client-side Store cache - another optimization is caching immutable
  values on the client-side for repeated lookups. This can be a bit tricky
  to do efficiently so was punted.  Caching would significantly improve
  history lookups.

## Schema Format

Schemas currently consist only of a single :major constraint on the
primary sorted dimension of the table, namely how the 'row key' in a
typical BigTable layout is to be constructed from the provided
structure, if not provided in the key itself.

For example, the following are legal ways of specifying a key for a schema:

    {:major [key1 key2 key3]}
 
    (s/insert key1val {:key2 <val1> :key3 <val2>}) ;; [key1val <val1> <val2>]
    (s/insert [key2val key2val] {:key2 <val1> :key3 <val2>}) ;; => [key1val key2val <val2>]

Keys can have enforced clojure types.  This is important for most
stores because different kinds of numbers don't sort correctly.  The
schema can enable enforcement of key constraints to ensure correct
sorting semantics.  Submission only fails if the provided type can't
be coerced to the schema-specified index type.

    {:major [[:key1 :string] [:key2 :float] [key3 :keyword]]}

## Filter Format



## Other Stores

    (def couchpeer (s/peer {:type :couchbase
                            :bucket "default"
                            :password ""
                            :uris ["http://127.0.01:8091/pools"]}))

## Notes

Interface for storage services. Assumed to operate on valid
clojure types + byte[].

Low-level abstraction over physical stores
- HBase, DynamoDB, Couchbase, Infinispan, Native
Language abstraction for capturing side effects
- like datomic's transact
Language abstractions for manipulating values
- Stores as immutable index
- Reuse internal interfaces as much as possible
  maps to known clojure functions

Keys may be vectors for stores that operate on compound types.
Stores should validate keys and provide exceptions if a key
does not validate.

Datomic and FleetDB lessons:
- Separate 'store' from 'value'
- Side effects registered with store, implication as new value
- CAS is used to catch collisions when updating existing records where necessary
- Validate that key and object meet schema requirements for underlying indexing

Peer (factory for stores, encapsulates backends)
- (name, Schema) -> Store

Store protocols (like Datomic's connections, encapsulates side-effects/coordination)
- IKeyValueStore: insert(key, value), update(key, value, [ver]) => Index
- IAsyncKeyValueStore: store-async(key, value), update-asynch(key, value, [ver]) => Index
- ILogStore: append(complex-value) => Index
- IMarkableStore: claim(key, ver), complete(key, ver) => boolean

Index protocols (like Datomic's Db)
- ILookup (key => value)          ;; Key to value
- Map (keys, vals)                ;; Error if query is unfiltered
- Selectable: select(map)         ;; Queries are remote, so filter rows & columns
- Filterable: filter(fn) => Index ;; 
- Scannable: scan(start_key), scan(start_key, end_key) => ISeq
  Structure of key can be exact, prefix value, regex,e tc.
  Error if do not recognize type of key

## License

Copyright © 2013 Vital Reactor, LLC

Distributed under the Eclipse Public License, the same as Clojure.
