;; Just auth - a simple two factor authenticatiokn library

;; part of Decentralized Citizen Engagement Technologies (D-CENT)
;; R&D funded by the European Commission (FP7/CAPS 610349)

;; Copyright (C) 2017 Dyne.org foundation

;; Sourcecode designed, written and maintained by
;; Aspasia Beneti <aspra@dyne.org>

;; This program is free software: you can redistribute it and/or modify
;; it under the terms of the GNU Affero General Public License as published by
;; the Free Software Foundation, either version 3 of the License, or
;; (at your option) any later version.

;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;; GNU Affero General Public License for more details.

;; You should have received a copy of the GNU Affero General Public License
;; along with this program.  If not, see <http://www.gnu.org/licenses/>.

(ns just-auth.test.db.failed-login
  (:require [midje.sweet :refer :all]
            [just-auth.db 
             [failed-login :as fl]
             [just-auth :as auth-db]]
            [clj-storage.db.mongo :as mongo]
            [clj-storage.test.db.mongo.test-db :as test-db]
            [taoensso.timbre :as log]))

(def failed-attempts [{:email "email-1"
                       :ip-address "ip-1"}
                      {:email "email-1"
                       :ip-address "ip-1"}
                      {:email "email-2"
                       :ip-address "ip-1"}
                      {:email "email-3"
                       :ip-address "ip-3"}
                      {:email "email-4"
                       :ip-address "ip-4"}
                      {}
                      {:email "email-4"}])

(against-background [(before :contents (test-db/setup-db))
                     (after :contents (test-db/teardown-db))]
                    
                    (facts "Create some failed attempts" 
                           (let [stores (auth-db/create-auth-stores
                                         (test-db/get-test-db))]
                             (doseq [attempt failed-attempts]
                               (fl/new-attempt! (:failed-login-store stores)
                                                (:email attempt)
                                                (:ip-address attempt)))
                             (fact "Count the number of all failed attempts the last 10 seconds"
                                   (fl/number-attempts (-> stores :failed-login-store)
                                                       10
                                                       {}) => (count failed-attempts)

                                   (fl/number-attempts (-> stores :failed-login-store)
                                                       10
                                                       {:email "email-1"}) => (count (filter (comp (partial = "email-1") :email) failed-attempts))

                                   (fl/number-attempts (-> stores :failed-login-store)
                                                       10
                                                       {:ip-address "ip-1"})  => (count (filter (comp (partial = "ip-1") :ip-address) failed-attempts))
                                   (fl/number-attempts (-> stores :failed-login-store)
                                                       0
                                                       {}) => 0))))
