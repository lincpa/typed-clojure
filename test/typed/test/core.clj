(ns typed.test.core
  (:refer-clojure :exclude [defrecord])
  (:import (clojure.lang Seqable ISeq ASeq IPersistentVector))
  (:require [clojure.test :refer :all]
            [analyze.core :refer [ast]]
            [clojure.repl :refer [pst]]
            [clojure.pprint :refer [pprint]]
            [clojure.data :refer [diff]]
            [typed.core :refer :all]
            [typed.test.rbt]
            [typed.test.deftype]
            [typed.test.core-logic]))

(check-ns 'typed.test.deftype)

(deftest add-scopes-test
  (is (let [body (make-F 'a)]
        (= (add-scopes 0 body)
           body)))
  (is (let [body (make-F 'a)]
        (= (add-scopes 1 body)
           (->Scope body))))
  (is (let [body (make-F 'a)]
        (= (add-scopes 3 body)
           (-> body ->Scope ->Scope ->Scope)))))

(deftest remove-scopes-test
  (is (let [scope (->Scope (make-F 'a))]
        (= (remove-scopes 0 scope)
           scope)))
  (is (let [body (make-F 'a)]
        (= (remove-scopes 1 (->Scope body))
           body))))

(deftest parse-type-test
  (is (= (Poly-body* '(x) (parse-type '(All [x] x)))
         (make-F 'x)))
  (is (= (Poly-body* '(x y) (parse-type '(All [x y] x)))
         (make-F 'x)))
  (is (= (Poly-body* '(x y) (parse-type '(All [x y] y)))
         (make-F 'y)))
  (is (= (Poly-body* '(a b c d e f g h i) (parse-type '(All [a b c d e f g h i] e)))
         (make-F 'e))))

(defmacro sub? [s t]
  `(subtype? (parse-type '~s)
             (parse-type '~t)))

(deftest subtype-test
  (is (subtype? (parse-type 'Integer)
                (parse-type 'Integer)))
  (is (subtype? (parse-type 'Integer)
                (parse-type 'Object)))
  (is (not (sub? Object Integer)))
  (is (sub? Object Object))
  (is (subtype? (parse-type 'Integer)
                (parse-type 'Number)))
  (is (subtype? (parse-type '(clojure.lang.Seqable Integer))
                (parse-type '(clojure.lang.Seqable Integer))))
  (is (subtype? (parse-type '(clojure.lang.Seqable Integer))
                (parse-type '(clojure.lang.Seqable Number))))
  (is (not
        (subtype? (parse-type '(clojure.lang.Seqable Number))
                  (parse-type '(clojure.lang.Seqable Integer)))))
  (is (subtype? (parse-type '(clojure.lang.Cons Integer))
                (parse-type '(clojure.lang.Cons Number))))
  (is (subtype? (parse-type '(clojure.lang.Cons Integer))
                (parse-type '(clojure.lang.Seqable Number)))))

(deftest subtype-java-exceptions-test
  (is (subtype? (RInstance-of IndexOutOfBoundsException)
                (RInstance-of Exception))))

(deftest subtype-intersection
  (is (not (subtype? (RInstance-of Seqable [-any])
                     (In (RInstance-of Seqable [-any])
                         (make-CountRange 1))))))

(deftest subtype-Object
  (is (subtype? (RInstance-of clojure.lang.IPersistentList [-any]) (RInstance-of Object))))

(deftest subtype-hmap
  (is (not (subtype? (constant-type '{:a nil})
                     (constant-type '{:a 1}))))
  (is (subtype? (constant-type '{:a 1 :b 2 :c 3})
                (constant-type '{:a 1 :b 2}))))

;(deftest subtype-top-Function
;  (is (subtype? (parse-type '[Integer -> Number])
;                (In (->TopFunction)))))

(deftest subtype-poly
  (is (subtype? (parse-type '(All [x] (clojure.lang.ASeq x)))
                (parse-type '(All [y] (clojure.lang.Seqable y))))))

(deftest subtype-rec
  (is (subtype? (parse-type 'Integer)
                (parse-type '(Rec [x] (U Integer (clojure.lang.Seqable x))))))
  (is (subtype? (parse-type '(clojure.lang.Seqable (clojure.lang.Seqable Integer)))
                (parse-type '(Rec [x] (U Integer (clojure.lang.Seqable x))))))
  (is (not (subtype? (parse-type 'Number)
                     (parse-type '(Rec [x] (U Integer (clojure.lang.Seqable x)))))))
  (is (sub (HMap {:op (Value :if)
                  :test (HMap {:op (Value :var)
                               :var clojure.lang.Var})
                  :then (HMap {:op (Value :nil)})
                  :else (HMap {:op (Value :false)})})
            (Rec [x] 
                 (U (HMap {:op (Value :if)
                           :test x
                           :then x
                           :else x})
                    (HMap {:op (Value :var)
                           :var clojure.lang.Var})
                    (HMap {:op (Value :nil)})
                    (HMap {:op (Value :false)})))))

 #_(is (sub? (Rec [x] (U Integer (clojure.lang.ILookup x x)))
            (Rec [x] (U Number (clojure.lang.ILookup x x)))))
  )

;FIXME expanding dotted pretypes
#_(deftest trans-dots-test
  (is (= (manual-inst (parse-type '(All [x b ...]
                                        [x ... b -> x]))
                      (map parse-type '(Integer Double Float)))
         (parse-type '[Integer Integer -> Integer])))
  (is (= (manual-inst (parse-type '(All [x b ...]
                                        [b ... b -> x]))
                      (map parse-type '(Integer Double Float)))
         (parse-type '[Double Float -> Integer])))
  ;map type
  (is (= (manual-inst (parse-type '(All [c a b ...]
                                        [[a b ... b -> c] (clojure.lang.Seqable a) (clojure.lang.Seqable b) ... b -> (clojure.lang.Seqable c)]))
                      (map parse-type '(Integer Double Float)))
         (parse-type '[[Double Float -> Integer] (clojure.lang.Seqable Double) (clojure.lang.Seqable Float) -> (clojure.lang.Seqable Integer)]))))

;return type for an expression f
(defmacro ety [f]
  `(-> (ast ~f) check expr-type ret-t))

(deftest tc-invoke-fn-test
  (is (subtype? (ety
                  ((typed.core/fn> [[a :- Number] [b :- Number]] b)
                     1 2))
                (parse-type 'Number)))
  ; manual instantiation "seq"
  (is (subtype? (ety
                  ((typed.core/fn> [[a :- (clojure.lang.Seqable Number)] [b :- Number]] 
                                   ((typed.core/inst seq Number) a))
                     [1 2 1.2] 1))
                (parse-type '(U nil (clojure.lang.ASeq Number)))))
  ; inferred "seq"
  (is (= (ety
           (typed.core/fn> [[a :- (clojure.lang.Seqable Number)] [b :- Number]] 
                           1))
         (Fn-Intersection
           (make-Function
             [(RInstance-of Seqable [(RInstance-of Number)]) (RInstance-of Number)] 
             (-val 1)
             nil nil
             :filter (-FS -top -bot)
             :object -empty))))
  ; poly inferred "seq"
  (is (= (ety
           (typed.core/pfn> (c) [[a :- (clojure.lang.Seqable c)] [b :- Number]] 
                            1))
         (let [x (make-F 'x)]
           (Poly* [(:name x)]
                  (Fn-Intersection
                    (make-Function
                      [(RInstance-of Seqable [x]) (RInstance-of Number)] 
                      (-val 1)
                      nil nil
                      :filter (-FS -top -bot)
                      :object -empty))))))
  ;test invoke fn
  (is (subtype? (ety
                  ((typed.core/fn> [[a :- (clojure.lang.Seqable Number)] [b :- Number]] 
                                   (seq a))
                     [1 2 1.2] 1))
                (parse-type '(U nil (clojure.lang.ASeq Number)))))
  (is (subtype? (ety
                  ((typed.core/fn> [[a :- (clojure.lang.IPersistentMap Any Number)] [b :- Number]] 
                                   ((typed.core/inst get Number) a b))
                     {:a 1} 1))
                (parse-type '(U nil Number)))))

(deftest get-special-test
  (is (= (ety 
           (typed.core/fn> [[a :- (HMap {:a Number})]]
                           (get a :a)))
         (Fn-Intersection
           (make-Function [(->HeterogeneousMap {(-val :a) (RInstance-of Number)})]
                          (RInstance-of Number)
                          nil nil
                          :filter (-FS -top -bot)
                          :object (->Path [(->KeyPE :a)] 0))))))

(deftest truth-false-values-test
  (is (= (tc-t (if nil 1 2))
         (ret (->Value 2) (-FS -top -bot) (->EmptyObject))))
  (is (= (tc-t (if false 1 2))
         (ret (->Value 2) (-FS -top -bot) (->EmptyObject))))
  (is (= (tc-t (if 1 1 2))
         (ret (->Value 1) (-FS -top -bot) (->EmptyObject)))))

(deftest empty-fn-test
  (is (= (tc-t (fn []))
         (ret (In (->Function [] (make-Result -nil
                                              (-FS -bot -top)
                                              (->EmptyObject))
                              nil nil nil))
              (-FS -top -bot)
              (->EmptyObject))))
  (is (= (tc-t (fn [] 1))
         (ret (In (->Function [] (make-Result (->Value 1)
                                              (-FS -top -bot)
                                              (->EmptyObject))
                              nil nil nil))
              (-FS -top -bot)
              (->EmptyObject))))
  (is (= (tc-t (let []))
         (ret -nil (-FS -bot -top) (->EmptyObject)))))

(deftest path-test
  (is (= (tc-t (fn [a] (let [a 1] a)))
         (ret (In (->Function [-any]
                              (make-Result (-val 1)
                                           (-FS -top -top)
                                           -empty)
                              nil nil nil))
              (-FS -top -bot) -empty)))
  (is (= (tc-t (let [a nil] a))
         (ret -nil (-FS -bot -top) -empty))))

(deftest equiv-test
  (is (= (tc-t (= 1))
         (tc-t (= 1 1))
         (tc-t (= 1 1 1 1 1 1 1 1 1 1))
         (ret (Un -true -false) (-FS -top -top) (->EmptyObject))))
  (is (= (tc-t (= 'a 'b))
         (tc-t (= 1 2))
         (tc-t (= :a :b))
         (tc-t (= :a 1 'a))
         (ret (Un -true -false) (-FS -top -top) -empty)))
  (is (= (tc-t (= :Val (-> {:a :Val} :a)))
         (ret (Un -true -false) (-FS -top -top) -empty))))

(deftest name-to-param-index-test
  ;a => 0
  (is (= (tc-t 
           (typed.core/fn> [[a :- (U (HMap {:op (Value :if)})
                                     (HMap {:op (Value :var)}))]] 
                           (:op a)))
         (ret (In (->Function
                    [(Un (->HeterogeneousMap {(->Value :op) (->Value :if)})
                         (->HeterogeneousMap {(->Value :op) (->Value :var)}))]
                    (let [t (Un (->Value :if) (->Value :var))
                          i 0
                          p [(->KeyPE :op)]]
                      (make-Result t
                                   (-FS -top -bot)
                                   (->Path p 0)))
                    nil nil nil))
                  (-FS -top -bot)
                  -empty))))

;TODO
(deftest refine-test
  (is (= (tc-t 
           (typed.core/fn> [[a :- (U (HMap {:op (Value :if)})
                                     (HMap {:op (Value :var)}))]] 
                           (when (= (:op a) :if) 
                             a)))
         (ret (In (->Function
                    [(Un (->HeterogeneousMap {(->Value :op) (->Value :if)})
                         (->HeterogeneousMap {(->Value :op) (->Value :var)}))]
                    (make-Result (Un -nil (->HeterogeneousMap {(->Value :op) (->Value :if)}))
                                 (-FS (-and (-filter (->Value :if) 0 [(->KeyPE :op)])
                                            (-not-filter (Un -false -nil) 0))
                                           ; what are these filters doing here?
                                      (-or (-and (-filter (->Value :if) 0 [(->KeyPE :op)])
                                                 (-filter (Un -false -nil) 0))
                                           (-not-filter (->Value :if) 0 [(->KeyPE :op)])))
                                 -empty)
                    nil nil nil))
              (-FS -top -bot)
              -empty))))


#_(deftest dotted-infer-test
  (is (cf (map number? [1]))))

(deftest check-invoke
  (is (thrown? Exception (ety (symbol "a" 'b))))
  (is (= (ety (symbol "a" "a"))
         (RInstance-of clojure.lang.Symbol))))

(deftest check-do-test
  (is (= (ety (do 1 2))
         (->Value 2))))

(deftest tc-var-test
  (is (= (tc-t seq?)
         (ret (In (->Function [(->Top)]
                              (make-Result (Un -true -false)
                                           (-FS (-filter (RInstance-of ISeq [(->Top)]) 0)
                                                (-not-filter (RInstance-of ISeq [(->Top)]) 0))
                                           -empty)
                              nil nil nil))
              (-FS -top -top) -empty))))

(deftest heterogeneous-ds-test
  (is (not (subtype? (parse-type '(HMap {:a (Value 1)}))
                     (RInstance-of ISeq [(->Top)]))))
  (is (not (subtype? (parse-type '(Vector* (Value 1) (Value 2)))
                     (RInstance-of ISeq [(->Top)]))))
  (is (subtype? (parse-type '(Seq* (Value 1) (Value 2)))
                (RInstance-of ISeq [(->Top)])))
  (is (subtype? (parse-type '(List* (Value 1) (Value 2)))
                (RInstance-of ISeq [(->Top)])))
  (is (= (tc-t [1 2])
         (ret (->HeterogeneousVector [(->Value 1) (->Value 2)]) -true-filter -empty)))
  (is (= (tc-t '(1 2))
         (ret (->HeterogeneousList [(->Value 1) (->Value 2)]) -true-filter -empty)))
  (is (= (tc-t {:a 1})
         (ret (->HeterogeneousMap {(->Value :a) (->Value 1)}) -true-filter -empty)))
  (is (= (tc-t {})
         (ret (->HeterogeneousMap {}) -true-filter -empty)))
  (is (= (tc-t [])
         (ret (->HeterogeneousVector []) -true-filter -empty)))
  (is (= (tc-t '())
         (ret (->HeterogeneousList []) -true-filter -empty))))

(deftest implied-atomic?-test
  (is (implied-atomic? (-not-filter -false 'a)(-not-filter (Un -nil -false) 'a))))

(deftest combine-props-test
  (is (= (map set (combine-props [(->ImpFilter (-not-filter -false 'a)
                                               (-filter -true 'b))]
                                 [(-not-filter (Un -nil -false) 'a)]
                                 (atom true)))
         [#{} #{(-not-filter (Un -nil -false) 'a)
                (-filter -true 'b)}])))

(deftest env+-test
  ;test basic TypeFilter
  ;update a from Any to (Value :a)
  (is (let [props [(-filter (->Value :a) 'a)]
            flag (atom true)]
        (and (= (let [env {'a -any}
                      lenv (->PropEnv env props)]
                  (env+ lenv [] flag))
                (->PropEnv {'a (->Value :a)} props))
             @flag)))
  ;test positive KeyPE
  ;update a from (U (HMap {:op :if}) (HMap {:op :var})) => (HMap {:op :if})
  (is (let [props [(-filter (->Value :if) 'a [(->KeyPE :op)])]
            flag (atom true)]
        (and (= (let [env {'a (Un (->HeterogeneousMap {(->Value :op) (->Value :if)})
                                  (->HeterogeneousMap {(->Value :op) (->Value :var)}))}
                      lenv (->PropEnv env props)]
                  (env+ lenv [] flag))
                (->PropEnv {'a (->HeterogeneousMap {(->Value :op) (->Value :if)})} props))
             @flag)))
  ;test negative KeyPE
  (is (let [props [(-not-filter (->Value :if) 'a [(->KeyPE :op)])]
            flag (atom true)]
        (and (= (let [env {'a (Un (->HeterogeneousMap {(->Value :op) (->Value :if)})
                                  (->HeterogeneousMap {(->Value :op) (->Value :var)}))}
                      lenv (->PropEnv env props)]
                  (env+ lenv [] flag))
                (->PropEnv {'a (->HeterogeneousMap {(->Value :op) (->Value :var)})} props))
             @flag)))
  ;test impfilter
  (is (let [{:keys [l props]}
            (env+ (->PropEnv {'a (Un -false -true) 'b (Un -nil -true)}
                             [(->ImpFilter (-not-filter -false 'a)
                                           (-filter -true 'b))])
                  [(-not-filter (Un -nil -false) 'a)]
                  (atom true))]
        (and (= l {'a -true, 'b -true})
             (= (set props)
                #{(-not-filter (Un -nil -false) 'a)
                  (-filter -true 'b)}))))
  ; more complex impfilter
  (is (= (env+ (->PropEnv {'and1 (Un -false -true)
                           'tmap (->Name 'typed.test.core/UnionName)}
                          [(->ImpFilter (-filter (Un -nil -false) 'and1)
                                        (-not-filter (-val :MapStruct1)
                                                     'tmap
                                                     [(->KeyPE :type)]))
                           (->ImpFilter (-not-filter (Un -nil -false) 'and1)
                                        (-filter (-val :MapStruct1)
                                                 'tmap
                                                 [(->KeyPE :type)]))])
               [(-filter (Un -nil -false) 'and1)]
               (atom true))))
  ; refine a subtype
  (is (= (:l (env+ (->PropEnv {'and1 (RInstance-of Seqable [-any])} [])
                   [(-filter (RInstance-of IPersistentVector [-any]) 'and1)]
                   (atom true)))
         {'and1 (RInstance-of IPersistentVector [-any])})))

(deftest destructuring-special-ops
  (is (= (tc-t (seq? [1 2]))
         (ret -false -false-filter -empty)))
  (is (= (tc-t (let [a {:a 1}]
                 (seq? a)))
         (ret -false -false-filter -empty)))
  ;FIXME for destructuring rest args
;  (is (= (tc-t (let [a '(a b)]
;                 (seq? a)))
;         (ret -true -true-filter -empty)))
  (is (= (tc-t (let [a {:a 1}]
                 (if (seq? a)
                   (apply hash-map a)
                   a)))
         (ret (->HeterogeneousMap {(->Value :a) (->Value 1)})
              ;FIXME should true-filter ?
              (-FS -top -top) -empty)))
  (is (= (tc-t (typed.core/fn> [[{a :a} :- (HMap {:a (Value 1)})]]
                               a))
         (ret (In (->Function [(->HeterogeneousMap {(->Value :a) (->Value 1)})]
                              (make-Result (->Value 1) 
                                           (-FS -top -top)  ; have to throw out filters whos id's go out of scope
                                           ;(->Path [(->KeyPE :a)] 0) ; TR not TC supports this inference. The destructuring
                                                                      ; adds an extra binding, which is erased as it goes out of scope.
                                                                      ; Can we recover this path?
                                           -empty)
                              nil nil nil))
              (-FS -top -bot)
              -empty)))
  (is (= (-> (tc-t (typed.core/fn> [[a :- typed.test.core/UnionName]]
                                   (seq? a)))
           ret-t)
         (In (->Function [(->Name 'typed.test.core/UnionName)]
                         (make-Result -false 
                                      ;FIXME why isn't this (-FS -bot (-not-filter (RInstance-of ISeq [-any]) 0)) ?
                                      (-FS -bot -top)
                                      -empty)
                         nil nil nil))))
  (is (= (tc-t (let [{a :a} {:a 1}]
                 a))
         (ret (->Value 1) 
              (-FS -top -top) ; a goes out of scope, throw out filters
              -empty)))
  (is (= (tc-t (typed.core/fn> [[a :- (HMap {:a (Value 1)})]]
                               (seq? a)))
         (ret (In (->Function [(->HeterogeneousMap {(->Value :a) (->Value 1)})]
                              (make-Result -false -false-filter -empty)
                              nil nil nil))
              (-FS -top -bot)
              -empty)))
  ;roughly the macroexpansion of map destructuring
  (is (= (tc-t (typed.core/fn> 
                 [[map-param :- typed.test.rbt/badRight]]
                 (when (and (= :Black (-> map-param :tree))
                            (= :Red (-> map-param :left :tree))
                            (= :Red (-> map-param :left :right :tree)))
                   (let [map1 map-param
                         map1
                         (if (clojure.core/seq? map1)
                           (clojure.core/apply clojure.core/hash-map map1)
                           map1)

                         mapr (clojure.core/get map1 :right)
                         mapr
                         (if (clojure.core/seq? mapr)
                           (clojure.core/apply clojure.core/hash-map mapr)
                           mapr)

                         maprl (clojure.core/get mapr :left)
                         ;_ (tc-pr-env "maprl")
                         maprl
                         (if (clojure.core/seq? maprl)
                           (clojure.core/apply clojure.core/hash-map maprl)
                           maprl)]
                     maprl))))))
  ;destructuring a variable of union type
  ; NOTE: commented out because, for now, it's an error to get a non-existant key
;  (is (= (tc-t (typed.core/fn> [[{a :a} :- (U (HMap {:a (Value 1)})
;                                              (HMap {:b (Value 2)}))]]
;                               a))
;         (ret (In (->Function [(Un (->HeterogeneousMap {(->Value :a) (->Value 1)})
;                                   (->HeterogeneousMap {(->Value :b) (->Value 2)}))]
;                              (make-Result (Un (->Value 1) -nil) (-FS -top -top) -empty)
;                              nil nil nil))
;              (-FS -top -bot)
;              -empty)))
              )

(def-alias MyName (HMap {:a (Value 1)}))
(def-alias MapName (HMap {:a typed.test.core/MyName}))

(def-alias MapStruct1 (HMap {:type (Value :MapStruct1)
                             :a typed.test.core/MyName}))
(def-alias MapStruct2 (HMap {:type (Value :MapStruct2)
                             :b typed.test.core/MyName}))
(def-alias UnionName (U MapStruct1 MapStruct2))

(deftest Name-resolve-test
  (is (= (tc-t (typed.core/fn> [[tmap :- typed.test.core/MyName]]
                               ;call to (apply hash-map tmap) should be eliminated
                               (let [{e :a} tmap]
                                 e)))
         (ret (In (->Function [(->Name 'typed.test.core/MyName)]
                              (make-Result (->Value 1) (-FS -top -top) -empty)
                              nil nil nil))
              (-FS -top -bot) -empty)))
  (is (= (tc-t (typed.core/fn> [[tmap :- typed.test.core/MapName]]
                               (let [{e :a} tmap]
                                 (assoc e :c :b))))
         (ret (In (->Function [(->Name 'typed.test.core/MapName)]
                              (make-Result (->HeterogeneousMap {(->Value :a) (->Value 1)
                                                                (->Value :c) (->Value :b)})
                                           (-FS -top -bot) -empty)
                              nil nil nil))
              (-FS -top -bot) -empty)))
  ; Name representing union of two maps, both with :type key
  (is (= (tc-t (typed.core/fn> [[tmap :- typed.test.core/UnionName]]
                               (:type tmap)))
         (ret (In (->Function [(->Name 'typed.test.core/UnionName)]
                              (make-Result (Un (->Value :MapStruct2)
                                               (->Value :MapStruct1))
                                           (-FS -top -bot) 
                                           (->Path [(->KeyPE :type)] 0))
                              nil nil nil))
              (-FS -top -bot) -empty)))
  ; using = to derive paths
  (is (= (tc-t (typed.core/fn> [[tmap :- typed.test.core/UnionName]]
                               (= :MapStruct1 (:type tmap))))
         (ret (In (->Function [(->Name 'typed.test.core/UnionName)]
                              (let [t (->Value :MapStruct1)
                                    path [(->KeyPE :type)]]
                                (make-Result (Un -false -true)
                                             (-FS (-filter t 0 path)
                                                  (-not-filter t 0 path))
                                             -empty))
                              nil nil nil))
              (-FS -top -bot) -empty)))
  ; using filters derived by =
  (is (= (tc-t (typed.core/fn> [[tmap :- typed.test.core/UnionName]]
                               (if (typed.core/tc-pr-filters "the test"
                                     (= :MapStruct1 (:type tmap)))
                                 (do (typed.core/tc-pr-env "follow then")
                                   (:a tmap))
                                 (do (typed.core/tc-pr-env "follow else")
                                   (:b tmap)))))
         (ret (In (->Function [(->Name 'typed.test.core/UnionName)]
                              (let [t (->Name 'typed.test.core/MyName)
                                    path [(->KeyPE :a)]]
                                ;object is empty because then and else branches objects differ
                                (make-Result t (-FS -top -bot) -empty))
                              nil nil nil))
              (-FS -top -bot) -empty)))
  ; following paths with test of conjuncts
  (is (= (tc-t (typed.core/fn> [[tmap :- typed.test.core/UnionName]]
                               ; (and (= :MapStruct1 (-> tmap :type))
                               ;      (= 1 1))
                               (if (typed.core/tc-pr-filters "final filters"
                                    (let [and1 (typed.core/tc-pr-filters "first and1"
                                                 (= :MapStruct1 (-> tmap :type)))]
                                      (typed.core/tc-pr-env "first conjunct")
                                      (typed.core/tc-pr-filters "second and1"
                                        (if (typed.core/tc-pr-filters "second test"
                                              and1)
                                          (do (typed.core/tc-pr-env "second conjunct")
                                            (typed.core/tc-pr-filters "third and1"
                                              (= 1 1)))
                                          (do (typed.core/tc-pr-env "fail conjunct")
                                            (typed.core/tc-pr-filters "fail and1"
                                              and1))))))
                                 (do (typed.core/tc-pr-env "follow then")
                                   (assoc tmap :c :d))
                                 1)))
         (ret (In (->Function [(->Name 'typed.test.core/UnionName)]
                              (let [t (Un (-val 1)
                                          (->HeterogeneousMap {(-val :type) (-val :MapStruct1)
                                                               (-val :c) (-val :d)
                                                               (-val :a) (->Name 'typed.test.core/MyName)}))]
                                (make-Result t (-FS -top -bot) -empty))
                              nil nil nil))
              (-FS -top -bot) -empty))))

;(tc-t (typed.core/fn> [[a :- Number]
;                       [b :- Number]]
;                      (if (= a 1)
;                        a
;                        )))
;
;(-> (tc-t (typed.core/fn> [[tmap :- typed.test.core/UnionName]]
;                          (= :MapStruct1 (-> tmap :type))
;                          (= 1 (-> tmap :a :a))))
;  :t :types first :rng :fl unparse-filter-set pprint)

;(->
;  (tc-t (typed.core/fn> [[a :- Number]
;                         [b :- Number]]
;;           (-FS (-and (-filter (-val 1) 'a)
;;                      (-filter (-val 2) 'b))
;;                (-or (-not-filter (-val 1) 'a)
;;                     (-not-filter (-val 2) 'b)
;;                     (-and (-not-filter (-val 1) 'a)
;;                           (-not-filter (-val 2) 'b))))
;          (typed.core/tc-pr-filters "final filters"
;            (let [and1 (typed.core/tc-pr-filters "first and1"
;                         (= a 1))]
;              (typed.core/tc-pr-env "first conjunct")
;;              (-FS (-and (-not-filter (Un -false -nil) and1)
;;                         (-filter (-val 2) 'b))
;;                   (-or (-filter (Un -false -nil) and1)
;;                        (-not-filter (-val 2) 'b)))
;              (typed.core/tc-pr-filters "second and1"
;                (if (typed.core/tc-pr-filters "second test"
;                      and1)
;                  (do (typed.core/tc-pr-env "second conjunct")
;                    (typed.core/tc-pr-filters "third and1"
;                      (= b 2)))
;                  (do (typed.core/tc-pr-env "fail conjunct")
;                    (typed.core/tc-pr-filters "fail and1"
;                      and1))))))))
;  :t :types first :rng :fl unparse-filter-set pprint)

(deftest update-test
  (is (= (update (Un (->HeterogeneousMap {(-val :type) (-val :Map1)})
                     (->HeterogeneousMap {(-val :type) (-val :Map2)}))
                 (-filter (->Value :Map1) 'tmap [(->KeyPE :type)]))
         (->HeterogeneousMap {(-val :type) (-val :Map1)})))
  ;test that update resolves Names properly
  (is (= (update (->Name 'typed.test.core/MapStruct2)
                 (-filter (-val :MapStruct1) 'tmap [(->KeyPE :type)]))
         (Un)))
  ;test that update resolves Names properly
  ; here we refine the type of tmap with the equivalent of following the then branch 
  ; with test (= :MapStruct1 (:type tmap))
  (is (= (update (->Name 'typed.test.core/UnionName)
                 (-filter (->Value :MapStruct1) 'tmap [(->KeyPE :type)]))
         (->HeterogeneousMap {(-val :type) (-val :MapStruct1) 
                              (-val :a) (->Name 'typed.test.core/MyName)})))
  (is (= (update (->Name 'typed.test.core/UnionName)
                 (-not-filter (->Value :MapStruct1) 'tmap [(->KeyPE :type)]))
         (->HeterogeneousMap {(-val :type) (-val :MapStruct2) 
                              (-val :b) (->Name 'typed.test.core/MyName)})))
  (is (= (update (Un -true -false) (-filter (Un -false -nil) 'a nil)) 
         -false)))

(deftest overlap-test
  (is (not (overlap -false -true)))
  (is (not (overlap (-val :a) (-val :b)))))

(def-alias SomeMap (U (HMap {:a (Value :b)})
                      (HMap {:b (Value :c)})))

(deftest assoc-test
  (is (= (tc-t (assoc {} :a :b))
         (ret (->HeterogeneousMap {(->Value :a) (->Value :b)})
              (-FS -top -bot)
              -empty)))
  (is (= (-> (tc-t (typed.core/fn> [[m :- typed.test.core/SomeMap]]
                                   (assoc m :c 1)))
           ret-t :types first :rng)
         (make-Result (Un (->HeterogeneousMap {(-val :a) (-val :b)
                                               (-val :c) (-val 1)})
                          (->HeterogeneousMap {(-val :b) (-val :c)
                                               (-val :c) (-val 1)}))
                      (-FS -top -bot)
                      -empty))))
         
(-> (tc-t (typed.core/fn> [[tmap :- typed.test.rbt/badRight]]
                          (and (= :Black (-> tmap :tree))
                               (= :Red (-> tmap :left :tree))
                               (= :Red (-> tmap :right :tree))
                               (= :Red (-> tmap :right :left :tree)))))
                          ;(and (tc-pr-filters "first filter"
                          ;       (= :Black (-> tmap :tree)))
                          ;     (tc-pr-filters "second filter"
                          ;       (= :Red (-> tmap :left :tree)))
                          ;     (tc-pr-filters "third filter"
                          ;       (= :Red (-> tmap :right :tree)))
                          ;     (tc-pr-filters "fourth filter"
                          ;       (= :Red (-> tmap :right :left :tree))))
  ret-t :types first :rng :fl :else unparse-filter pprint)

;(deftest filter-simplification
;  (is (= (read-string "#typed.core.OrFilter{:fs #{#typed.core.NotTypeFilter{:type #typed.core.Value{:val :Black}, :path (#typed.core.KeyPE{:val :tree}), :id 0} #typed.core.AndFilter{:fs #{#typed.core.TypeFilter{:type #typed.core.Value{:val :Black}, :path (#typed.core.KeyPE{:val :tree}), :id 0} #typed.core.OrFilter{:fs #{#typed.core.NotTypeFilter{:type #typed.core.Value{:val :Red}, :path (#typed.core.KeyPE{:val :left} #typed.core.KeyPE{:val :tree}), :id 0} #typed.core.AndFilter{:fs #{#typed.core.TypeFilter{:type #typed.core.Value{:val :Red}, :path (#typed.core.KeyPE{:val :left} #typed.core.KeyPE{:val :tree}), :id 0} #typed.core.OrFilter{:fs #{#typed.core.AndFilter{:fs #{#typed.core.TypeFilter{:type #typed.core.Value{:val :Red}, :path (#typed.core.KeyPE{:val :right} #typed.core.KeyPE{:val :tree}), :id 0} #typed.core.NotTypeFilter{:type #typed.core.Value{:val :Red}, :path (#typed.core.KeyPE{:val :right} #typed.core.KeyPE{:val :left} #typed.core.KeyPE{:val :tree}), :id 0}}} #typed.core.NotTypeFilter{:type #typed.core.Value{:val :Red}, :path (#typed.core.KeyPE{:val :right} #typed.core.KeyPE{:val :tree}), :id 0}}}}}}}}}}}"

(deftest update-nested-hmap-test
  (is (= (update (->HeterogeneousMap {(-val :left) (->Name 'typed.test.rbt/rbt)})
                 (-filter (-val :Red) 'id [(->KeyPE :left) (->KeyPE :tree)]))
         (->HeterogeneousMap {(-val :left) 
                              (->HeterogeneousMap {(-val :tree) (-val :Red) 
                                                   (-val :entry) (->Name 'typed.test.rbt/EntryT) 
                                                   (-val :left) (->Name 'typed.test.rbt/bt) 
                                                   (-val :right) (->Name 'typed.test.rbt/bt)})}))))
         
(deftest rbt-test

  (is (= (tc-t (typed.core/fn> [[tmap :- typed.test.rbt/badRight]]
                               (let [and1 (= :Black (-> tmap :tree))]
                                 #_(tc-pr-env "first clause")
                                 (if and1
                                   (let [and1 (= :Red (-> tmap :left :tree))]
                                     #_(tc-pr-env "second then clause")
                                     (if and1
                                       (let [and1 (= :Red (-> tmap :right :tree))]
                                         #_(tc-pr-env "third then clause")
                                         (if and1
                                           (= :Red (-> tmap :right :left :tree))
                                           (do #_(tc-pr-env "last clause")
                                             and1)))
                                       (do #_(tc-pr-env "third else clause")
                                         and1)))
                                   (do #_(tc-pr-env "second else clause")
                                     and1))))))))

(deftest check-get-keyword-invoke-test
  ;truth valued key
  (is (= (tc-t (let [a {:a 1}]
                 (:a a)))
         (ret (->Value 1) (-FS -top -bot) -empty)))
  ;false valued key, a bit conservative in filters for now
  (is (= (tc-t (let [a {:a nil}]
                 (:a a)))
         (ret -nil (-FS -top -top) -empty)))
  ;multiple levels
  (is (= (tc-t (let [a {:c {:a :b}}]
                 (-> a :c :a)))
         (ret (->Value :b) (-FS -top -bot) -empty)))
  (is (= (tc-t (clojure.core/get {:a 1} :a))
         (tc-t (clojure.lang.RT/get {:a 1} :a))
         #_(tc-t ({:a 1} :a))
         (tc-t (:a {:a 1}))
         (ret (->Value 1)
              (-FS -top -bot)
              -empty))))

(defn print-cset [cs]
  (into {} (doall
             (for [ms (:maps cs)
                   [k v] (:fixed ms)]
               [k
                [(str (unparse-type (:S v))
                      " << "
                      (:X v)
                      " << "
                      (unparse-type (:T v)))]]))))

(deftest promote-demote-test
  (is (= (promote-var (make-F 'x) '#{x})
         (->Top)))
  (is (= (demote-var (make-F 'x) '#{x})
         (Bottom)))
  (is (= (promote-var (RInstance-of clojure.lang.ISeq [(make-F 'x)]) '#{x})
         (RInstance-of clojure.lang.ISeq [(->Top)])))
  (is (= (demote-var (RInstance-of clojure.lang.ISeq [(make-F 'x)]) '#{x})
         (RInstance-of clojure.lang.ISeq [(Bottom)]))))

(deftest variances-test
  (is (= (fv-variances (make-F 'x))
         '{x :covariant}))
  (is (= (fv-variances (->Top))
         '{})))

(deftest fv-test
  (is (= (fv (make-F 'x))
         '#{x})))

(deftest fi-test
  (is (empty? (fi (make-F 'x)))))

(deftest bounds-constraints
  (is (cs-gen #{} '#{x} #{} (->Value 1) (make-F 'x (RInstance-of Number)))))

(deftest cs-gen-test
  (is (= (cs-gen #{} ;V
                 '#{x y} ;X
                 #{} ;Y
                 (->Value 1) ;S
                 (make-F 'x)) ;T
         (->cset [(->cset-entry {'x (->c (->Value 1) 'x (->Top))
                                 'y (->c (Un) 'y (->Top))}
                                (->dmap {}))]))))

(deftest subst-gen-test
  (let [cs (cs-gen #{} ;V
                   '#{x y} ;X
                   #{} ;Y
                   (->Value 1) ;S
                   (make-F 'x))]
    (is (= (subst-gen cs #{} (make-F 'x))
           {'x (->t-subst (->Value 1))
            'y (->t-subst (Un))}))))

(deftest infer-test
  (is (= (infer '#{x y} ;tv env
                #{}
                [(->Value 1) (->Value 2)] ;actual
                [(make-F 'x) (make-F 'y)] ;expected
                (make-F 'x)))) ;result
  (is (= (infer '#{x} ;tv env
                '#{}
                [(RInstance-of IPersistentVector [(Un (-val 1) (-val 2) (-val 3))])] ;actual
                [(RInstance-of Seqable [(make-F 'x)])] ;expected
                (RInstance-of ASeq [(make-F 'x)])))) ;result
  (is (= (infer '#{x} ;tv env
                '#{}
                [(->HeterogeneousVector [(-val 1) (-val 2) (-val 3)])] ;actual
                [(RInstance-of Seqable [(make-F 'x)])] ;expected
                (RInstance-of ASeq [(make-F 'x)]))))) ;result

(deftest arith-test
  (is (= (:t (tc-t (+)))
         (RInstance-of Number)))
  (is (= (:t (tc-t (+ 1 2)))
         (RInstance-of Number)))
  (is (thrown? Exception (tc-t (+ 1 2 "a"))))
  (is (thrown? Exception (tc-t (-))))
  (is (thrown? Exception (tc-t (/)))))

(deftest tc-constructor-test
  (is (= (tc-t (Exception. "a"))
         (ret (RInstance-of Exception)
              (-FS -top -bot)
              (->NoObject)))))

(deftest tc-throw-test
  (is (= (:t (tc-t (throw (Exception. "a"))))
         (Un))))

(deftest first-seq-test
  (is (subtype? (:t (tc-t (first [1 1 1])))
                (Un -nil (RInstance-of Number))))
  (is (subtype (In (RInstance-of clojure.lang.PersistentList [-any])
                   (make-CountRange 1))
               (In (RInstance-of Seqable [-any])
                   (make-CountRange 1))))
  (is (subtype? (:t (tc-t (let [l [1 2 3]]
                            (if (seq l)
                              (first l)
                              (throw (Exception. "Error"))))))
                (RInstance-of Number))))

(deftest intersection-maker-test
  (is (= (In -nil (-val 1))
         (Un)))
  (is (= (In (RInstance-of Seqable [-any])
             -nil)
         (Un))))

(deftest count-subtype-test
  (is (subtype? (make-CountRange 1)
                (make-CountRange 1)))
  (is (not (subtype? (make-CountRange 1)
                     (make-ExactCountRange 1))))
  (is (subtype? (make-ExactCountRange 1)
                (make-CountRange 1))))

(deftest core-logic-subtype-test
  (is (subtype? (->Name 'typed.test.core-logic/Term) 
                (Un -nil (RInstance-of Object)))))

(deftest ccfind-test
  (is (= (-> (tc-t (typed.core/fn> [[a :- (clojure.lang.IPersistentMap Long String)]]
                                   (find a 1)))
           :t :types first :rng :t)
         (Un (->HeterogeneousVector (list (RInstance-of Long) (RInstance-of String)))
             -nil))))

(deftest map-infer-test
  (is (subtype? (ret-t (tc-t (map + [1 2])))
                (RInstance-of Seqable [(RInstance-of Number)]))))
