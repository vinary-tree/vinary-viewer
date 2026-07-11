(ns vinary.ui.ssh
  "Renderer UI for SSH/SFTP remote files: the authentication prompt (password / key passphrase / multi-prompt
   keyboard-interactive) and a connection-error toast.

   The typed secret lives ONLY in this component's local reagent state — it is dispatched straight to main via
   [:ssh/prompt-reply …] (→ vv:ssh-prompt-reply) and NEVER enters app-db or any persisted store. app-db holds
   only the non-secret request (kind / host / user / attempt / prompt), mirroring the password-bridge doctrine."
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [vinary.ui.modal :as modal]))

(defn- label-for [req]
  (let [host (:host req) user (:user req) who (str (or user "?") "@" host)]
    (case (:kind req)
      "passphrase"           (str "Passphrase for key " (or (:keyPath req) "") "  (" who ")")
      "keyboard-interactive" (or (not-empty (:prompt req)) (str "Verification for " who))
      (str "Password for " who))))

(defn prompt-dialog
  "The SSH secret prompt. Form-2 so the typed value is component-local (never app-db)."
  []
  (let [secret (r/atom "")]
    (fn []
      (when-let [req @(rf/subscribe [:ui/ssh-prompt])]
        (let [reply!  (fn [v] (rf/dispatch [:ssh/prompt-reply (:promptId req) v]) (reset! secret ""))
              submit! (fn [] (reply! @secret))
              cancel! (fn [] (reply! nil))]
          [modal/modal
           {:on-close cancel!
            :title    "SSH authentication"
            :class    "vv-ssh-prompt"
            :actions  [:<>
                       [:button.vv-btn {:type "button" :on-click cancel!} "Cancel"]
                       [:button.vv-btn.vv-btn-primary {:type "button" :on-click submit!} "OK"]]}
           [:div.vv-ssh-prompt-body
            [:label.vv-ssh-label (label-for req)
             (when (and (:attempt req) (> (:attempt req) 1))
               [:span.vv-ssh-attempt (str "  (attempt " (:attempt req) ")")])]
            [:input.vv-ssh-input
             {:type        (if (:echo req) "text" "password")
              :auto-focus  true
              :value       @secret
              :on-change   (fn [^js e] (reset! secret (.. e -target -value)))
              :on-key-down (fn [^js e] (when (= "Enter" (.-key e)) (.preventDefault e) (submit!)))}]]])))))

(defn error-toast
  "A dismissable toast for a non-prompt SSH error (auth failed, host key changed/rejected, unreachable, dropped)."
  []
  (when-let [err @(rf/subscribe [:ui/ssh-error])]
    [:div.vv-ssh-error {:role "alert"}
     [:span.vv-ssh-error-msg (str "SSH: " (:message err))]
     [:button.vv-ssh-error-x {:type "button" :aria-label "Dismiss"
                              :on-click #(rf/dispatch [:ssh/dismiss-error])} "✕"]]))
