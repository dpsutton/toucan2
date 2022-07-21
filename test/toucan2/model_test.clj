(ns toucan2.model-test
  (:require
   [clojure.test :refer :all]
   [toucan2.model :as model]
   [toucan2.test :as test]))

(deftest reducible-model-query-test
  (is (= [(list 'magic-map :people {:id 1, :name "Cam", :created-at #inst "2020-04-21T23:56:00.000000000-00:00"})]
         (into [] (model/reducible-model-query ::test/db :people "SELECT * FROM people WHERE id = 1;")))))

(deftest ^:parallel default-table-name-test
  (doseq [[model expected] {"ABC"   "ABC"
                            :abc    "abc"
                            :ns/abc "abc"}]
    (testing (pr-str `(model/table-name ~model))
      (is (= expected
             (model/table-name model))))))

(deftest ^:parallel conditions->honeysql-where-clause-test
  (doseq [[conditions expected] {nil                 nil
                                 {:id 1}             [:= :id 1]
                                 {:a 1, :b 2}        [:and [:= :a 1] [:= :b 2]]
                                 {:id [:> 1]}        [:> :id 1]
                                 {:a 1, :b [:> 2]}   [:and [:= :a 1] [:> :b 2]]
                                 {:a [:between 1 2]} [:between :a 1 2]}]
    (testing (pr-str `(model/conditions->honeysql-where-clause ~conditions))
      (is (= expected
             (model/conditions->honeysql-where-clause conditions))))))

(deftest ^:parallel default-build-select-query-test
  (is (= {:select [:*]
          :from   [[:default]]}
         (model/build-select-query :default {} nil nil)))
  (testing "don't override existing"
    (is (= {:select [:a :b]
            :from   [[:my_table]]}
           (model/build-select-query :default {:select [:a :b], :from [[:my_table]]} nil nil))))
  (testing "columns"
    (is (= {:select [:a :b]
            :from   [[:default]]}
           (model/build-select-query :default {} [:a :b] nil)))
    (testing "existing"
      (is (= {:select [:a]
              :from   [[:default]]}
             (model/build-select-query :default {:select [:a]} [:a :b] nil)))))
  (testing "conditions"
    (is (= {:select [:*]
            :from   [[:default]]
            :where  [:= :id 1]}
           (model/build-select-query :default {} nil {:id 1})))
    (testing "merge with existing"
      (is (= {:select [:*]
              :from   [[:default]]
              :where  [:and [:= :a :b] [:= :id 1]]}
             (model/build-select-query :default {:where [:= :a :b]} nil {:id 1}))))))