(ns dstore.peer.mem
  (:require [dstore.api :as ds]
            [org.fressian.clojure :as fr]
            [rotary.client :as db] ;; [rotary "0.4.0"]
  (:import [clojure.lang Seqable IFn IKeywordLookup
            ILookup IDeref IHashEq IObj]
           [dstore.api StorePeer
            KeyValueStore AsyncKeyValueStore LogStore MutableStore
            Indexed Filterable Excisable]))
  
;; Book-keeping in table?
;; 
  
(defrecord DynamoPeer [cred]
  StorePeer
  (stores [this])
  
  (store [this name]
    (throw (Throwable. (format "Store not found for: '%s'." name))))
  
  (store [this type name]
    (ds/store this type name {}))
    
  (store [this type name schema]
    (let [store (with-meta
                  (->DynamoIndexStore name (atom {}))
                  {:name name :schema schema})]
        (swap! roots assoc name store)
        (@roots name)))

  (rem-store [this ref]
    (let [name (if (= (type ref) DynamoIndexStore)
                 (:name ref) ref)]
      this)))

(defmethod print-method DynamoPeer [peer w]
  (.write w (format "#DynamoPeer[]")))

(defmethod ds/create-peer* :mem [config]
  (->DynamoPeer (atom {})))
