(ns vinary.main.password-adapters
  "Provider adapters for the native password-manager bridge. All provider execution stays in the Electron
   main process and uses child_process.spawn with argv arrays, never a shell. Search results are sanitized
   before they cross IPC; revealed secrets are returned only to vinary.main.passwords so they can be sent
   directly to the isolated web-view preload."
  (:require ["child_process" :as cp]
            [clojure.string :as str]
            [vinary.main.password-util :as pu]))

(def default-timeout-ms 15000)
(def secret-timeout-ms 60000)
(def default-max-output (* 4 1024 1024))
(def large-max-output (* 24 1024 1024))

(def builtin-providers
  [{:id "onepassword" :label "1Password" :kind :onepassword :bin "op" :save-supported? true}
   {:id "lastpass" :label "LastPass" :kind :lastpass :bin "lpass" :save-supported? true}
   {:id "bitwarden" :label "Bitwarden" :kind :bitwarden :bin "bw" :save-supported? false}
   {:id "proton-pass" :label "Proton Pass" :kind :proton-pass :bin "pass-cli" :save-supported? false}])

(defn- finish-once [settled resolve payload]
  (when-not @settled
    (reset! settled true)
    (resolve payload)))

(defn- append-limited! [acc chunk ^js child max-output overflow?]
  (let [s    (.toString chunk "utf8")
        next (str @acc s)]
    (if (> (count next) max-output)
      (do
        (reset! overflow? true)
        (reset! acc (subs next 0 max-output))
        (try (.kill child "SIGTERM") (catch :default _ nil)))
      (reset! acc next))))

(defn run-command
  "Run a provider command without a shell. Resolves to {:ok? :exit :stdout :stderr :error?}; it never logs
   stdout/stderr because some CLIs necessarily print secrets during reveal/search."
  ([cmd args] (run-command cmd args nil))
  ([cmd args {:keys [stdin timeout-ms max-output]}]
   (js/Promise.
    (fn [resolve _reject]
      (let [stdout     (atom "")
            stderr     (atom "")
            settled    (atom false)
            overflow?  (atom false)
            timeout-ms (or timeout-ms default-timeout-ms)
            max-output (or max-output default-max-output)
            child      (.spawn cp (str cmd) (clj->js (mapv str (or args [])))
                               (clj->js {:shell false
                                         :stdio (if (some? stdin)
                                                  ["pipe" "pipe" "pipe"]
                                                  ["ignore" "pipe" "pipe"])}))
            timer      (js/setTimeout
                        (fn []
                          (try (.kill child "SIGTERM") (catch :default _ nil))
                          (finish-once settled resolve
                                       {:ok? false :exit nil :stdout @stdout :stderr @stderr
                                        :error "timeout"}))
                        timeout-ms)]
        (when-let [out (.-stdout child)]
          (.on out "data" #(append-limited! stdout % child max-output overflow?)))
        (when-let [err (.-stderr child)]
          (.on err "data" #(append-limited! stderr % child max-output overflow?)))
        (.on child "error"
             (fn [e]
               (js/clearTimeout timer)
               (finish-once settled resolve
                            {:ok? false :exit nil :stdout @stdout :stderr @stderr
                             :error (or (.-code e) (.-message e) "spawn-error")})))
        (.on child "close"
             (fn [code _signal]
               (js/clearTimeout timer)
               (finish-once settled resolve
                            {:ok? (and (zero? code) (not @overflow?))
                             :exit code
                             :stdout @stdout
                             :stderr @stderr
                             :error (when @overflow? "output-too-large")})))
        (when (some? stdin)
          (try
            (.write (.-stdin child) (str stdin))
            (.end (.-stdin child))
            (catch :default e
              (js/clearTimeout timer)
              (finish-once settled resolve
                           {:ok? false :exit nil :stdout @stdout :stderr @stderr
                            :error (or (.-message e) "stdin-error")})))))))))

(defn parse-json [s]
  (try (js->clj (js/JSON.parse (or s "")) :keywordize-keys true)
       (catch :default _ nil)))

(defn json-string [m]
  (js/JSON.stringify (clj->js m)))

(defn- missing-command? [result]
  (#{"ENOENT" "EACCES"} (:error result)))

(defn unavailable-status [provider result]
  {:id (:id provider)
   :label (:label provider)
   :status (if (missing-command? result) "not-installed" "unavailable")
   :message (cond
              (missing-command? result) (str (:bin provider) " is not installed or is not on PATH.")
              (= "timeout" (:error result)) (str (:bin provider) " did not respond before the timeout.")
              (= "output-too-large" (:error result)) (str (:bin provider) " returned too much output.")
              :else (or (pu/blank->nil (:stderr result))
                        (pu/blank->nil (:stdout result))
                        (pu/blank->nil (:error result))
                        "Provider command failed."))})

(defn process-status [provider result]
  (if (missing-command? result)
    (unavailable-status provider result)
    {:id (:id provider)
     :label (:label provider)
     :status (pu/status-from-process-output (:exit result) (:stdout result) (:stderr result))
     :message (or (pu/blank->nil (:stderr result))
                  (pu/blank->nil (:stdout result))
                  "")}))

(defn bitwarden-status-from-json [provider result]
  (if (missing-command? result)
    (unavailable-status provider result)
    (let [m (parse-json (:stdout result))
          s (some-> (:status m) str/lower-case)]
      {:id (:id provider)
       :label (:label provider)
       :status (case s
                 "unlocked" "ready"
                 "locked" "reauth-required"
                 "unauthenticated" "reauth-required"
                 (pu/status-from-process-output (:exit result) (:stdout result) (:stderr result)))
       :message (or (pu/blank->nil (:message m))
                    (pu/blank->nil (:stderr result))
                    (pu/blank->nil (:stdout result))
                    "")})))

(defn provider-status [provider]
  (case (:kind provider)
    :onepassword (-> (run-command (:bin provider) ["account" "list" "--format" "json"]
                                  {:timeout-ms 5000 :max-output 262144})
                     (.then #(process-status provider %)))
    :lastpass    (-> (run-command (:bin provider) ["status" "--color=never"]
                                  {:timeout-ms 5000 :max-output 262144})
                     (.then #(process-status provider %)))
    :bitwarden   (-> (run-command (:bin provider) ["status" "--raw"]
                                  {:timeout-ms 5000 :max-output 262144})
                     (.then #(bitwarden-status-from-json provider %)))
    :proton-pass (-> (run-command (:bin provider) ["info"]
                                  {:timeout-ms 5000 :max-output 262144})
                     (.then #(process-status provider %)))
    :json-command
    (if-let [cmd (get-in provider [:commands :status])]
      (-> (run-command (:cmd cmd) (:args cmd) {:timeout-ms 5000 :max-output 262144})
          (.then #(process-status provider %)))
      (js/Promise.resolve {:id (:id provider) :label (:label provider) :status "ready" :message ""}))
    (js/Promise.resolve {:id (:id provider) :label (:label provider) :status "error"
                         :message "Unsupported provider kind."})))

(defn- first-url [urls]
  (first (pu/item-urls {:urls urls})))

(defn parse-op-item [provider item]
  (let [urls (vec (keep (fn [u] (cond
                                  (string? u) u
                                  (map? u)    (or (:href u) (:url u))
                                  :else       nil))
                         (:urls item)))]
    {:provider (:id provider)
     :provider-label (:label provider)
     :id (:id item)
     :vault-id (or (get-in item [:vault :id]) (get-in item [:vault :name]))
     :title (or (:title item) (:name item))
     :username (or (:additional_information item) (:username item) "")
     :url (or (:url item) (first-url urls))
     :urls urls}))

(defn- field-text [f k]
  (some-> (get f k) str str/lower-case))

(defn- matching-field [fields pred]
  (first (filter pred fields)))

(defn extract-op-credentials [item]
  (let [fields   (vec (:fields item))
        username (or (some-> (matching-field fields #(or (= "username" (field-text % :purpose))
                                                          (= "username" (field-text % :id))
                                                          (= "username" (field-text % :label))))
                              :value)
                     "")
        password (or (some-> (matching-field fields #(or (= "password" (field-text % :purpose))
                                                          (= "password" (field-text % :id))
                                                          (= "password" (field-text % :label))
                                                          (= "concealed" (field-text % :type))))
                              :value)
                     "")
        urls     (vec (keep (fn [u] (or (:href u) (:url u))) (:urls item)))]
    {:username username
     :password password
     :url (or (first urls) (:url item) "")
     :urls urls}))

(defn- op-search [provider page-url]
  (-> (run-command (:bin provider)
                   ["item" "list" "--categories" "Login" "--long" "--format" "json"]
                   {:timeout-ms secret-timeout-ms :max-output large-max-output})
      (.then
       (fn [result]
         (if (:ok? result)
           (let [items (or (parse-json (:stdout result)) [])
                 rows  (->> items
                            (map #(parse-op-item provider %))
                            (pu/matching-items page-url)
                            (map pu/sanitize-item)
                            vec)]
             {:ok? true :items rows})
           {:ok? false :status (process-status provider result) :items []})))))

(defn- op-reveal [provider item]
  (let [args (cond-> ["item" "get" (:id item) "--format" "json" "--reveal"]
               (pu/blank->nil (:vault-id item)) (conj "--vault" (:vault-id item)))]
    (-> (run-command (:bin provider) args {:timeout-ms secret-timeout-ms :max-output default-max-output})
        (.then
         (fn [result]
           (if (:ok? result)
             (let [m     (parse-json (:stdout result))
                   creds (extract-op-credentials m)]
               (if (seq (:password creds))
                 {:ok? true :credentials creds}
                 {:ok? false :status "error" :message "Selected 1Password item has no password field."}))
             {:ok? false :status (pu/status-from-process-output (:exit result) (:stdout result) (:stderr result))
              :message (or (pu/blank->nil (:stderr result)) "Could not reveal the 1Password item.")}))))))

(defn- op-save [provider candidate]
  (let [template {:title (or (pu/blank->nil (:title candidate))
                             (str "Login for " (or (pu/url-host (:url candidate)) (:url candidate))))
                  :category "LOGIN"
                  :urls [{:label "website" :href (:url candidate)}]
                  :fields [{:id "username" :type "STRING" :purpose "USERNAME" :label "username"
                            :value (or (:username candidate) "")}
                           {:id "password" :type "CONCEALED" :purpose "PASSWORD" :label "password"
                            :value (:password candidate)}]}
        args     (cond-> ["item" "create" "-" "--format" "json"]
                   (pu/blank->nil (:vault provider)) (conj "--vault" (:vault provider)))]
    (-> (run-command (:bin provider) args
                     {:stdin (json-string template)
                      :timeout-ms secret-timeout-ms
                      :max-output default-max-output})
        (.then (fn [result]
                 (if (:ok? result)
                   {:ok? true :message "Saved in 1Password."}
                   {:ok? false :status (pu/status-from-process-output (:exit result) (:stdout result) (:stderr result))
                    :message (or (pu/blank->nil (:stderr result)) "Could not save in 1Password.")}))))))

(defn parse-lastpass-entry [provider item]
  {:provider (:id provider)
   :provider-label (:label provider)
   :id (str (or (:id item) (:fullname item) (:name item)))
   :title (or (:name item) (:fullname item) "LastPass login")
   :username (or (:username item) "")
   :url (or (:url item) "")
   :urls (cond-> [] (:url item) (conj (:url item)))})

(defn- first-json-entry [x]
  (cond
    (vector? x) (first x)
    (map? x)    x
    :else       nil))

(defn- lastpass-search [provider page-url]
  ;; LastPass CLI has no reliable metadata-only URL search. The all-item JSON path is contained to the main
  ;; process, capped, never logged, and immediately sanitized before renderer IPC.
  (-> (run-command (:bin provider)
                   ["show" "--json" "--all" "--sync=auto" "--color=never"]
                   {:timeout-ms secret-timeout-ms :max-output large-max-output})
      (.then
       (fn [result]
         (if (:ok? result)
           (let [items (or (parse-json (:stdout result)) [])
                 rows  (->> items
                            (map #(parse-lastpass-entry provider %))
                            (pu/matching-items page-url)
                            (map pu/sanitize-item)
                            vec)]
             {:ok? true :items rows})
           {:ok? false :status (process-status provider result) :items []})))))

(defn- lastpass-reveal [provider item]
  (-> (run-command (:bin provider)
                   ["show" "--json" "--sync=auto" "--color=never" (:id item)]
                   {:timeout-ms secret-timeout-ms :max-output default-max-output})
      (.then
       (fn [result]
         (if (:ok? result)
           (let [m (first-json-entry (parse-json (:stdout result)))
                 creds {:username (or (:username m) "")
                        :password (or (:password m) "")
                        :url (or (:url m) "")}]
             (if (seq (:password creds))
               {:ok? true :credentials creds}
               {:ok? false :status "error" :message "Selected LastPass item has no password field."}))
           {:ok? false :status (pu/status-from-process-output (:exit result) (:stdout result) (:stderr result))
            :message (or (pu/blank->nil (:stderr result)) "Could not reveal the LastPass item.")})))))

(defn- lpass-field! [provider op field-name item-name value]
  (run-command (:bin provider)
               [op "--sync=now" "--non-interactive" field-name "--color=never" item-name]
               {:stdin (str (or value "") "\n")
                :timeout-ms secret-timeout-ms
                :max-output default-max-output}))

(defn- lastpass-save [provider candidate]
  (let [name (or (pu/blank->nil (:title candidate))
                 (str "Login for " (or (pu/url-host (:url candidate)) (:url candidate))))]
    (-> (lpass-field! provider "add" "--username" name (:username candidate))
        (.then (fn [r1]
                 (if (:ok? r1)
                   (lpass-field! provider "edit" "--password" name (:password candidate))
                   (js/Promise.resolve r1))))
        (.then (fn [r2]
                 (if (:ok? r2)
                   (lpass-field! provider "edit" "--url" name (:url candidate))
                   (js/Promise.resolve r2))))
        (.then (fn [r3]
                 (if (:ok? r3)
                   {:ok? true :message "Saved in LastPass."}
                   {:ok? false :status (pu/status-from-process-output (:exit r3) (:stdout r3) (:stderr r3))
                    :message (or (pu/blank->nil (:stderr r3)) "Could not save in LastPass.")}))))))

(defn parse-bitwarden-item [provider item]
  (let [login (:login item)
        uris  (vec (keep (fn [u] (:uri u)) (:uris login)))]
    {:provider (:id provider)
     :provider-label (:label provider)
     :id (:id item)
     :title (:name item)
     :username (or (:username login) "")
     :url (first uris)
     :urls uris}))

(defn- bitwarden-search [provider page-url]
  (-> (run-command (:bin provider) ["list" "items" "--url" page-url]
                   {:timeout-ms secret-timeout-ms :max-output large-max-output})
      (.then
       (fn [result]
         (if (:ok? result)
           (let [items (or (parse-json (:stdout result)) [])
                 rows  (->> items
                            (map #(parse-bitwarden-item provider %))
                            (pu/matching-items page-url)
                            (map pu/sanitize-item)
                            vec)]
             {:ok? true :items rows})
           {:ok? false :status (process-status provider result) :items []})))))

(defn- bitwarden-reveal [provider item]
  (-> (run-command (:bin provider) ["get" "item" (:id item)]
                   {:timeout-ms secret-timeout-ms :max-output default-max-output})
      (.then
       (fn [result]
         (if (:ok? result)
           (let [m (:login (parse-json (:stdout result)))
                 creds {:username (or (:username m) "")
                        :password (or (:password m) "")
                        :url (or (some-> (:uris m) first :uri) "")}]
             (if (seq (:password creds))
               {:ok? true :credentials creds}
               {:ok? false :status "error" :message "Selected Bitwarden item has no password field."}))
           {:ok? false :status (pu/status-from-process-output (:exit result) (:stdout result) (:stderr result))
            :message (or (pu/blank->nil (:stderr result)) "Could not reveal the Bitwarden item.")})))))

(defn parse-proton-item [provider item]
  (let [urls (vec (keep identity (concat (:urls item)
                                         (when-let [u (:url item)] [u])
                                         (when-let [u (get-in item [:login :url])] [u]))))]
    {:provider (:id provider)
     :provider-label (:label provider)
     :id (or (:id item) (:item-id item) (:itemId item))
     :title (or (:name item) (:title item))
     :username (or (:username item) (get-in item [:login :username]) "")
     :url (first urls)
     :urls urls}))

(defn- proton-search [provider page-url]
  (-> (run-command (:bin provider) ["item" "list" "--output" "json"]
                   {:timeout-ms secret-timeout-ms :max-output large-max-output})
      (.then
       (fn [result]
         (if (:ok? result)
           (let [items (or (parse-json (:stdout result)) [])
                 rows  (->> items
                            (map #(parse-proton-item provider %))
                            (pu/matching-items page-url)
                            (map pu/sanitize-item)
                            vec)]
             {:ok? true :items rows})
           {:ok? false :status (process-status provider result) :items []})))))

(defn- proton-reveal [provider item]
  (-> (run-command (:bin provider) ["item" "view" (:id item) "--output" "json"]
                   {:timeout-ms secret-timeout-ms :max-output default-max-output})
      (.then
       (fn [result]
         (if (:ok? result)
           (let [m     (parse-json (:stdout result))
                 login (or (:login m) m)
                 creds {:username (or (:username login) (:username m) "")
                        :password (or (:password login) (:password m) "")
                        :url (or (:url login) (:url m) "")}]
             (if (seq (:password creds))
               {:ok? true :credentials creds}
               {:ok? false :status "error" :message "Selected Proton Pass item has no password field."}))
           {:ok? false :status (pu/status-from-process-output (:exit result) (:stdout result) (:stderr result))
            :message (or (pu/blank->nil (:stderr result)) "Could not reveal the Proton Pass item.")})))))

(defn- expand-arg [ctx arg]
  (-> (str arg)
      (str/replace "{url}" (or (:url ctx) ""))
      (str/replace "{origin}" (or (:origin ctx) ""))
      (str/replace "{host}" (or (:host ctx) ""))
      (str/replace "{id}" (or (:id ctx) ""))))

(defn- command-for [provider op]
  (get-in provider [:commands op]))

(defn- run-json-command [provider op ctx stdin]
  (when-let [cmd (command-for provider op)]
    (run-command (:cmd cmd)
                 (mapv #(expand-arg ctx %) (:args cmd))
                 (cond-> {:timeout-ms secret-timeout-ms :max-output large-max-output}
                   (some? stdin) (assoc :stdin stdin)))))

(defn- custom-search [provider page-url]
  (let [ctx {:url page-url :origin (pu/url-origin page-url) :host (pu/url-host page-url)}]
    (if-let [p (run-json-command provider :search ctx nil)]
      (-> p
          (.then (fn [result]
                   (if (:ok? result)
                     (let [items (or (parse-json (:stdout result)) [])
                           rows  (->> items
                                      (map #(merge {:provider (:id provider) :provider-label (:label provider)} %))
                                      (pu/matching-items page-url)
                                      (map pu/sanitize-item)
                                      vec)]
                       {:ok? true :items rows})
                     {:ok? false :status (process-status provider result) :items []}))))
      (js/Promise.resolve {:ok? true :items []}))))

(defn- custom-reveal [provider item]
  (if-let [p (run-json-command provider :reveal {:id (:id item)} nil)]
    (-> p
        (.then (fn [result]
                 (if (:ok? result)
                   (let [m (parse-json (:stdout result))]
                     (if (seq (:password m))
                       {:ok? true :credentials {:username (or (:username m) "")
                                                :password (:password m)
                                                :url (or (:url m) "")}}
                       {:ok? false :status "error" :message "Custom provider returned no password."}))
                   {:ok? false :status (process-status provider result)
                    :message (or (pu/blank->nil (:stderr result)) "Custom provider reveal failed.")}))))
    (js/Promise.resolve {:ok? false :status "error" :message "Custom provider has no reveal command."})))

(defn- custom-save [provider candidate]
  (let [cmd (command-for provider :save)]
    (if (and cmd (:stdin-json? cmd))
      (-> (run-json-command provider :save {:url (:url candidate)
                                            :origin (pu/url-origin (:url candidate))
                                            :host (pu/url-host (:url candidate))}
                            (json-string (select-keys candidate [:url :username :password :title])))
          (.then (fn [result]
                   (if (:ok? result)
                     {:ok? true :message (str "Saved in " (:label provider) ".")}
                     {:ok? false :status (process-status provider result)
                      :message (or (pu/blank->nil (:stderr result)) "Custom provider save failed.")}))))
      (js/Promise.resolve {:ok? false :status "error"
                           :message "Custom provider save requires :stdin-json? true."}))))

(defn search [provider page-url]
  (case (:kind provider)
    :onepassword  (op-search provider page-url)
    :lastpass     (lastpass-search provider page-url)
    :bitwarden    (bitwarden-search provider page-url)
    :proton-pass  (proton-search provider page-url)
    :json-command (custom-search provider page-url)
    (js/Promise.resolve {:ok? false :items [] :status {:status "error" :message "Unsupported provider."}})))

(defn reveal [provider item]
  (case (:kind provider)
    :onepassword  (op-reveal provider item)
    :lastpass     (lastpass-reveal provider item)
    :bitwarden    (bitwarden-reveal provider item)
    :proton-pass  (proton-reveal provider item)
    :json-command (custom-reveal provider item)
    (js/Promise.resolve {:ok? false :status "error" :message "Unsupported provider."})))

(defn save [provider candidate]
  (if-not (:save-supported? provider)
    (js/Promise.resolve {:ok? false :status "error" :message "This provider does not support saving yet."})
    (case (:kind provider)
      :onepassword  (op-save provider candidate)
      :lastpass     (lastpass-save provider candidate)
      :json-command (custom-save provider candidate)
      (js/Promise.resolve {:ok? false :status "error" :message "This provider does not support saving yet."}))))
