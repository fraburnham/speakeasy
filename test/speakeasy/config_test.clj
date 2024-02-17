(ns speakeasy.config-test
  (:require [clojure.test :refer [deftest testing is are]]
            [clojure.string :as string]
            [clojure.java.shell :as shell]
            [speakeasy.config :as sut]))

(deftest deep-contains?
  (are [ks expected] (= (sut/deep-contains? {:k {:k2 {:k3 nil} :k4 false} :k5 "asdf"} ks) expected)
    [:k] true
    [:k :k3] false
    [:k :k2] true
    [:k5] true
    [:asdf] false
    [:k :k2 :k3] true
    [:k :k4] true))

(deftest resolve-value
  (testing "allows nil and false values to be returned"
    (is (= (sut/resolve-value {} {:k nil} {::sut/config-location [:k]}) nil))
    (is (= (sut/resolve-value {} {:k false} {::sut/config-location [:k]}) false)))

  (testing "parses env variables"
    (are [env-map var parser expected]
        (= (sut/resolve-value env-map {} {::sut/env-var var ::sut/env-parser parser})
           expected)
        {"NUM" "6"} "NUM" #(Integer/parseInt %) 6
        {"BOOL" "false"} "BOOL" (fn [b] (= "true" b)) false
        {"BOOL" "true"} "BOOL" (fn [b] (= "true" b)) true))

  (testing "uses value from env if found"
    (are [env-map var expected]
        (= expected
           (sut/resolve-value env-map {} {::sut/env-var var ::sut/default :mock-default}))

        {"TEST" "value"} "TEST" "value"
        {"NONSENSE" "value"} "NONSENSE" "value"
        {} "SOMETHING" :mock-default))

  (testing "uses value from config file if found"
    (are [config-file-map loc expected]
        (= expected
           (sut/resolve-value {} config-file-map {::sut/config-location loc ::sut/default :mock-default}))

        {:test "value"} [:test] "value"
        {:test {:some {:depth "value"}}} [:test :some :depth] "value"
        {} [:missing] :mock-default))

  (testing "throws if there will be no value to return"
    (is (thrown? clojure.lang.ExceptionInfo
                 (sut/resolve-value {} {} {})))))

(deftest get-config-file
  (testing "returns empty map when no file exsts"
    (is (= (sut/get-config-file {"FILE" "/likely/invalid/path"} {::sut/file {::sut/env-var "FILE"}})
           {})))

  (testing "parses some edn when a file exists"
    (let [temp-file-path (string/trim (:out (shell/sh "mktemp")))
          expected {:test "value"}]
      (spit temp-file-path (prn-str expected))
      (is (= (sut/get-config-file {"FILE" temp-file-path} {::sut/file {::sut/env-var "FILE"}})
             expected)))))
