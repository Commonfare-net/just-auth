;; Just auth - a simple two factor authenticatiokn library

;; part of Decentralized Citizen Engagement Technologies (D-CENT)
;; R&D funded by the European Commission (FP7/CAPS 610349)

;; Copyright (C) 2017 Dyne.org foundation

;; Sourcecode designed, written and maintained by
;; Aspasia Beneti  <aspra@dyne.org>

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

(ns just-auth.core
  (:require [just-auth.db 
             [account :as account]
             [password-recovery :as pr]]
            [just-auth
             [schema :refer [HashFns
                             AuthStores
                             EmailSchema
                             EmailSignUp
                             StoreSchema]]
             [messaging :as m]
             [util :as u]]
            [taoensso.timbre :as log]
            [schema.core :as s]
            [fxc.core :as fxc]
            [failjure.core :as f]))

(defprotocol Authentication
  ;; About names http://www.kalzumeus.com/2010/06/17/falsehoods-programmers-believe-about-name
  (sign-up [this name email password second-step-conf other-names])

  (sign-in [this email password])

  ;; TODO: maybe add password?
  (activate-account [this email second-step-conf])

  (send-activation-message [this email second-step-conf])

  (send-password-reset-message [this email second-step-conf])

  (de-activate-account [this email password])

  (reset-password [this email old-password new-password second-step-conf]))


(s/defn ^:always-validate sign-up-with-email
  [authenticator :- just_auth.core.Authentication
   account-store :- StoreSchema
   {:keys [name
           other-names
           email
           password 
           activation-uri]} :- EmailSignUp
   hash-fns :- HashFns]
  (if (account/fetch account-store email)
     (do (account/new-account! account-store
                               (cond-> {:name name
                                        :email email
                                        :password password}
                                 other-names (assoc :other-names other-names))
                               hash-fns)
         (send-activation-message authenticator email activation-uri))))
    ;; TODO: warn with an email for this attempt?
    (f/fail "An account with this email already exists")

;; TODO: We could use something like https://github.com/adambard/failjure for error handling
(s/defrecord EmailBasedAuthentication
    [account-store :-  StoreSchema
     password-recovery-store :- StoreSchema
     account-activator :- EmailSchema
     password-recoverer :- EmailSchema
     hash-fns :- HashFns]

  Authentication
  (send-activation-message [_ email {:keys [activation-uri]}]
    (let [account (account/fetch account-store email)]
      (if account
        (if (:activated account)
          (f/fail "This account is already active")
          (let [activation-id (fxc.core/generate 32)
                activation-link (u/construct-link {:uri activation-uri
                                                   :token activation-id})]
            (if-not (m/email-and-update! account-activator email activation-link)
              (f/fail "The email was not sent.")
              account)))
        ;; TODO: send an email to that email
        (f/fail (str "No account found for email " email)))))

  (send-password-reset-message [_ email {:keys [reset-uri]}]
    (let [account (account/fetch account-store email)]
      (if account
        (if (pr/fetch password-recovery-store email)
          (f/fail "A password recovery request has already been sent.")
          (let [password-reset-id (fxc.core/generate 32)
                password-reset-link (u/construct-link {:uri reset-uri
                                                       :token password-reset-id})]
            (if-not (m/email-and-update! password-recoverer email password-reset-link)
              (f/fail "The email was not sent.")
              account)))
        ;; TODO: send an email to that email?
        (f/fail (str "No account found for email " email)))))
  
  (sign-up [this name email password {:keys [activation-uri]} other-names]
    (sign-up-with-email this account-store {:name name
                                            :other-names other-names
                                            :email email
                                            :password password
                                            :activation-uri activation-uri} hash-fns))
  
  (sign-in [_ email password]
    (if-let [account (account/fetch account-store email)]
      (if (:activated account)
        (if (account/correct-password? account-store email password (:hash-check-fn hash-fns))
          {:email email
           :name (:name account)
           :other-names (:other-names account)}
          ;; TODO: send email?
          (f/fail "Wrong username and/or password"))
        (f/fail "The account needs to be activated first"))
      (f/fail (str "No account found for email " email))))

  (activate-account [_ email {:keys [activation-link]}]
    (let [account (account/fetch-by-activation-token account-store activation-link)]
      (if account
        (if (= (:email account) email)
          (account/activate! account-store email)
          (f/fail "The activation code does not match the email."))
        (f/fail (str "No account found for activation link " activation-link)))))

  (de-activate-account [_ email de-activation-link]
    ;; TODO
    )

  (reset-password [_ email old-password new-password {:keys [password-reset-link]}]
    (if (= (pr/fetch-by-password-recovery-token password-recovery-store password-reset-link))
      (if (account/correct-password? account-store email old-password (:hash-check-fn hash-fns))
        (account/update-password! account-store email new-password (:hash-fn hash-fns))
        ;; TODO: send email?
        (f/fail "Wrong username and/or password"))
      (f/fail "Link might have expired. Please request a new one."))))


(s/defn ^:always-validate new-email-based-authentication
  [stores :- AuthStores
   account-activator :- EmailSchema
   password-recoverer :- EmailSchema
   hash-fns :- HashFns]
  (s/validate just_auth.core.Authentication
              (map->EmailBasedAuthentication {:account-store (:account-store stores)
                                              :password-recovery-store (:password-recovery-store stores)
                                              :password-recoverer password-recoverer
                                              :account-activator account-activator
                                              :hash-fns hash-fns})))
