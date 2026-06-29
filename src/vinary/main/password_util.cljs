(ns vinary.main.password-util
  "Pure helpers for the native password-manager bridge. This namespace contains no IO and no secrets:
   origin matching, provider/status normalization, metadata sanitization, and redaction live here so they
   can be unit-tested independently of Electron and provider CLIs."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]))

(def provider-labels
  {"onepassword"  "1Password"
   "lastpass"     "LastPass"
   "bitwarden"    "Bitwarden"
   "proton-pass"  "Proton Pass"})

(def status-rank
  {"ready"                 0
   "reauth-required"       1
   "mfa-required"          2
   "hardware-key-required" 3
   "locked"                4
   "not-installed"         5
   "unavailable"           6
   "error"                 7})

(def secret-keys
  #{:password :secret :totp :otp :token :access-token :refresh-token :stdin :pass
    "password" "secret" "totp" "otp" "token" "access-token" "refresh-token" "stdin" "pass"})

(defn provider-label [id]
  (or (get provider-labels (str id)) (str id)))

(defn now-ms [] (.now js/Date))

(defn blank->nil [s]
  (let [s (some-> s str str/trim)]
    (when (seq s) s)))

(defn normalize-status [status]
  (let [s (some-> status name)]
    (if (contains? status-rank s) s "error")))

(defn ready-status? [status]
  (= "ready" (normalize-status status)))

(defn save-supported? [provider]
  (boolean (:save-supported? provider)))

(defn web-url? [url]
  (try
    (let [u (js/URL. (str url))]
      (#{"http:" "https:"} (.-protocol u)))
    (catch :default _ false)))

(defn url-origin [url]
  (try
    (let [u (js/URL. (str url))]
      (when (#{"http:" "https:"} (.-protocol u))
        (.-origin u)))
    (catch :default _ nil)))

(defn url-host [url]
  (try
    (let [u (js/URL. (str url))]
      (when (#{"http:" "https:"} (.-protocol u))
        (some-> (.-hostname u) str/lower-case)))
    (catch :default _ nil)))

(defn host-suffix-match?
  "True when page-host exactly matches item-host, or is a subdomain of item-host."
  [page-host item-host]
  (let [page-host (some-> page-host str/lower-case blank->nil)
        item-host (some-> item-host str/lower-case blank->nil)]
    (boolean
     (and page-host item-host
          (or (= page-host item-host)
              (str/ends-with? page-host (str "." item-host)))))))

(defn item-urls [item]
  (->> (concat
        (when-let [u (:url item)] [u])
        (when-let [u (:href item)] [u])
        (when-let [us (:urls item)]
          (map (fn [u] (cond
                         (string? u) u
                         (map? u)    (or (:href u) (:url u))
                         :else       nil))
               us)))
       (keep blank->nil)
       distinct
       vec))

(defn match-score [page-url item]
  (let [page-origin (url-origin page-url)
        page-host   (url-host page-url)
        urls        (item-urls item)
        exact?      (some #(= page-origin (url-origin %)) urls)
        host?       (some #(host-suffix-match? page-host (url-host %)) urls)
        title       (some-> (:title item) str/lower-case)
        host-title? (and page-host title (str/includes? title page-host))]
    (cond
      exact?      100
      host?       80
      host-title? 20
      :else       0)))

(defn matching-items [page-url items]
  (->> items
       (map #(assoc % :score (match-score page-url %)))
       (filter #(pos? (:score %)))
       (sort-by (juxt (comp - :score)
                      (comp str/lower-case str :title)
                      (comp str/lower-case str :username)))
       vec))

(defn sanitize-item [item]
  (let [urls (item-urls item)
        url  (or (:url item) (first urls))]
    (cond-> {:provider       (str (:provider item))
             :provider-label (or (:provider-label item) (provider-label (:provider item)))
             :id             (str (:id item))
             :title          (or (blank->nil (:title item)) "Untitled login")
             :username       (or (blank->nil (:username item)) "")
             :url            (or url "")
             :origin         (or (url-origin url) "")
             :score          (or (:score item) 0)}
      (:vault-id item) (assoc :vault-id (:vault-id item))
      (:account-id item) (assoc :account-id (:account-id item)))))

(defn provider-public [provider status]
  {:id              (str (:id provider))
   :label           (or (:label provider) (provider-label (:id provider)))
   :kind            (some-> (:kind provider) name)
   :status          (normalize-status (:status status))
   :message         (or (:message status) "")
   :save-supported? (boolean (:save-supported? provider))})

(defn redact [x]
  (walk/postwalk
   (fn [v]
     (if (map? v)
       (into {} (map (fn [[k value]]
                       [k (if (contains? secret-keys k) "[redacted]" value)])
                     v))
       v))
   x))

(defn status-from-process-output [exit stdout stderr]
  (let [text (str/lower-case (str stdout "\n" stderr))]
    (cond
      (zero? (or exit 1)) "ready"
      (or (str/includes? text "not logged in")
          (str/includes? text "not signed in")
          (str/includes? text "sign in")
          (str/includes? text "signin")
          (str/includes? text "unauthenticated")) "reauth-required"
      (or (str/includes? text "mfa")
          (str/includes? text "multi-factor")
          (str/includes? text "two-factor")
          (str/includes? text "totp")
          (str/includes? text "one-time")) "mfa-required"
      (or (str/includes? text "hardware key")
          (str/includes? text "security key")
          (str/includes? text "webauthn")
          (str/includes? text "u2f")) "hardware-key-required"
      :else "error")))
