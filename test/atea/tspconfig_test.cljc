(ns atea.tspconfig-test
  (:require
   [clojure.test :as t :refer [deftest is testing]]
   [clojure.java.io :as io]
   [atea.tspconfig :as tspcfg]
   [clojure.set]
   [clojure.string :as str]
   [clojure.edn :as edn]))


(def example-cfg {:customer "Comcare"
                  :cucm-host "pcb"
                  :email     "test@atea.dev"
                  :ip        "10.1.22.21"
                  :hostname  "uaw01"
                  :gateway   "10.1.22.1"
                  :cucm-ip   "10.1.22.10"
                  :dns       [ "10.1.20.50", "10.1.20.51"]
                  :smtp      "10.1.23.59"
                  :subnet    "255.255.255.0"
                  :ntp       "10.1.0.2"
                  :time-zone "Australia/Sydney"
                  })

(deftest parse-customer-cfg
  (is (= (tspcfg/parse-customer-config (slurp "test/cust.cfg"))
         example-cfg )))

(deftest file-exists-test
  (is (=  (tspcfg/test-exists? "test/not-exists") false )))

(deftest current-config-test
  (let [SUT (tspcfg/current-vm-config "test/hostname" "test/ifcfg-eth0" )]
        (is (= "testhost.atea.dev" (:hostname SUT)) "should be existing hostname")
        (is (= "10.66.66.77" (:ip SUT)) "should be existing IP")
        (is (= "255.255.0.0" (:subnet SUT)) "should be exisitng subnet")
        (is (= "none"   (:dhcp SUT))    "Should be none")
        ))
