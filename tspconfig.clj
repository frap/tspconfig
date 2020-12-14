#!/usr/bin/env bb

;; Generated with script/gen_script.clj. Do not edit directly.

#!/usr/bin/env bb
(ns atea.tspconfig
  (:require [clojure.java.io :as io]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.set]
            [clojure.string :as str]
            [clojure.java.shell :refer [sh]]
            )
  (:import [java.lang ProcessBuilder$Redirect]
           [java.net URL HttpURLConnection]
           [java.nio.file Files FileSystems CopyOption]))

(def version "20.09.09")
(def tspconfig-clj-version
  (when (.exists (io/file "resources/TSPCONFIG_VERSION"))
        (-> (io/file "resources/TSPCONFIG_VERSION")
            (slurp)
            (str/trim))))

(def ansi-styles
  {:red   "[31m"
   :green "[32m"
   :reset "[0m"})

(defn ansi [style]
  (str \u001b (style ansi-styles)))

(defn colourise
  [text colour]
  (str (ansi colour) text (ansi :reset)))


(def help-text (colourise (str "Version: " version "
Usage: tspconfig  [--] [init-opt*] [main-opt] [arg*]
       clj     [dep-opt*] [--] [init-opt*] [main-opt] [arg*]
The tspconfig script is a script to set redhat networking features.
You can use a customers config from Atea's email or change some part
of the config using command line options.
invoke a command-line of the form:

-s                   Change host's hostname
-i                   Change host' IP address
-g                   Change host's IP Gateway
 -                   Run a script from standard input
 -h, -?, --help      Print this help message and exit
") :green))

(def parse-opts->keyword
  {"-s" :hostname
   "-i" :ip
   "-g" :gateway
   "-n" :netmask
   "-t" :time-zone
   })

(defn shell-command
  "Executes shell command. Exits script when the shell-command has a non-zero exit code, propagating it.
  Accepts the following options:
  `:input`: instead of reading from stdin, read from this string.
  `:to-string?`: instead of writing to stdout, write to a string and
  return it."
  ([args] (shell-command args nil))
  ([args {:keys [:input :to-string?]}]
   (let [args (mapv str args)
         pb (cond-> (-> (ProcessBuilder. ^java.util.List args)
                        (.redirectError ProcessBuilder$Redirect/INHERIT))
              (not to-string?) (.redirectOutput ProcessBuilder$Redirect/INHERIT)
              (not input) (.redirectInput ProcessBuilder$Redirect/INHERIT))
         proc (.start pb)]
     (when input
       (with-open [w (io/writer (.getOutputStream proc))]
         (binding [*out* w]
           (print input)
           (flush))))
     (let [string-out
           (when to-string?
             (let [sw (java.io.StringWriter.)]
               (with-open [w (io/reader (.getInputStream proc))]
                 (io/copy w sw))
               (str sw)))
           exit-code (.waitFor proc)]
       (when-not (zero? exit-code)
         (System/exit exit-code))
       string-out))))

(defn current-vm-config [ network-cfg ]
  (let [name      (-> (sh "cat" "/etc/hostname") :out str/trim )
        network   (slurp network-cfg)
        [_ ip]    (re-find #"IPADDR=(.*)" network)
        [_ dhcp]  (re-find #"BOOTPROTO=(.*)" network)
        ]
    {:hostname name
     :ip       ip
     :dhcp     dhcp })
  )

(defn normalise-test-result [result]
  "bash test results return 0 if true and -1 if false"
   (= result 0)
    )

(defn test-exists? [file-name]
  (-> (sh "test" "-e" file-name) :exit normalise-test-result))

(defn parse-customer-config [config]
  (let [[_ company]  (re-find #"Company Name.+:\s+([A-Za-z0-9\-]+)\n" config)
        [_ email]    (re-find #"Customer Email.+:\s+([A-Za-z0-9\-@\.]+)\n" config)
        [_ hostname] (re-find #"Hostname.+:\s+([A-Za-z0-9]+)\n" config)
        [_ ip]       (re-find #"IP Address.+:\s+((?:[0-9]{1,3}\.){3}[0-9]{1,3})\n" config)
        [_ mask]     (re-find #"Subnet Mask.+:\s+((?:[0-9]{1,3}\.){3}[0-9]{1,3})" config)
        [_ gw]       (re-find #"DefaultGateway.+:\s+((?:[0-9]{1,3}\.){3}[0-9]{1,3})+" config)
        [_ dns]      (re-find #"DNS Server.+:\s+((?:[0-9]{1,3}\.){3}[0-9]{1,3})+" config)
        [_ smtp]     (re-find #"SMTP Server.+:\s+((?:[0-9]{1,3}\.){3}[0-9]{1,3})+\n" config)
        [_ time]     (re-find #"Time Zone.+:\s+([\w/]+)\n" config)
        [_ ntp]      (re-find #"NTP Server.+:\s+((?:[0-9]{1,3}\.){3}[0-9]{1,3})+\n" config)
        [_ cucmhost] (re-find #"CUCM Hostname.+:\s+([A-Za-z0-9\-]+)[a-z0-9].*\n" config)
        [_ cucmip]   (re-find #"CUCM Server.+:\s+((?:[0-9]{1,3}\.){3}[0-9]{1,3})+\n" config)

        ]
  {:customer   company
   :email      email
   :hostname   hostname
   :ip         ip
   :subnet     mask
   :gateway    gw
   :time-zone  time
   :dns        dns
   :ntp        ntp
   :smtp       smtp
   :cucm-host  cucmhost
   :cucm-ip    cucmip
   }))


;(-main)
(defn -main [& command-line-args]
  (let args (loop [command-line-args (seq command-line-args)
                    acc {}]
               (if command-line-args
                 (let [arg (first command-line-args)]
                   (cond
                    ;; (= "--" arg) (assoc acc :args (next command-line-args))
                     (some #(str/starts-with? arg %) ["-s" "-i" "-g" "-n" "-t"])
                     (recur (next command-line-args)
                            (update acc (get parse-opts->keyword (subs arg 0 2))
                                    str (subs arg 2)))
                     (or (= "-h" arg)
                         (= "--help" arg)) (assoc acc :help true)
                     :else (assoc acc :file command-line-args)))
                 acc))
       _ (when (:help args)
            (println help-text)
            (System/exit 0))
       (when (:file args)
         (let [f (io/file (:file args))]
           (if (.exists f)
             (do (println f " exists!")
                 (System/exit 0))
             (binding [*out* *err*]
               (println f "does not exist")
               (System/exit 1)))))
       (println args))
       )
)

(apply -main *command-line-args*)
