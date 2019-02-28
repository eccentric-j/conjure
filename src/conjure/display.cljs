(ns conjure.display
  "Ways to inform the user about responses, results and errors."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [cljs.core.async :as a]
            [expound.alpha :as expound]
            [conjure.nvim :as nvim]))

;; TODO Rename this to just conjure.cljc once I completely replace the Rust version.
(def log-buffer-name "/tmp/conjure-log.cljc")
(def log-window-widths {:small 40 :large 80})

(defn- <tabpage-log-window []
  (a/go
    (let [tabpage (a/<! (nvim/<tabpage))]
      (loop [[window & windows] (a/<! (nvim/<windows tabpage))]
        (when window
          (let [buffer (a/<! (nvim/<buffer window))]
            (if (= (a/<! (nvim/<name buffer)) log-buffer-name)
              window
              (recur windows))))))))

(defn- <upsert-tabpage-log-window! []
  (a/go
    (if-let [window (a/<! (<tabpage-log-window))]
      window
      (do
        (nvim/command! "botright" (str (:small log-window-widths) "vnew") log-buffer-name)
        (nvim/command! "setlocal winfixwidth")
        (nvim/command! "setlocal buftype=nofile")
        (nvim/command! "setlocal bufhidden=hide")
        (nvim/command! "setlocal nowrap")
        (nvim/command! "setlocal noswapfile")
        (nvim/command! "wincmd w")
        (a/<! (<tabpage-log-window))))))

(do
  ;; TODO Run all output through here
  ;; TODO Make the window auto expand and hide 
  ;; TODO Have a way to open it (optionally focus)
  ;; TODO Trim the log when it's too long
  (defn log! [{:keys [conn value]}]
    (a/go
      (let [window (a/<! (<upsert-tabpage-log-window!))
            buffer (a/<! (nvim/<buffer window))
            length (a/<! (nvim/<length buffer))
            sample (a/<! (nvim/<get-lines buffer {:start 0, :end 1}))
            prefix (str ";" (name (:tag conn)) "/" (name (:tag value)) ";")
            val-lines (str/split (:val value) #"\n")]

        (when (and (= length 1) (= sample [""]))
          (nvim/set-lines! buffer {:start 0} ";conjure/out; Welcome!"))

        (if (contains? #{:ret :tap} (:tag value))
          (nvim/append! buffer prefix val-lines)
          (doseq [line val-lines]
            (nvim/append! buffer (str prefix " " line)))) 

        (nvim/scroll-to-bottom! window))))

  (log! {:conn {:tag :test}
         :value {:tag :ret
                 :val ":henlo"}}))

(defn message! [tag & args]
  (apply nvim/out-write-line! (when tag (str "[" (name tag) "]")) args))

(defn error! [tag & args]
  (apply nvim/err-write-line! (when tag (str "[" (name tag) "]")) args))

(defn result! [tag result]
  (message! tag (name (:tag result)) "=>" (:val result)))

(defn ensure! [spec form]
  (if (s/valid? spec form)
    form
    (do
      (error! nil (expound/expound-str spec form))
      nil)))