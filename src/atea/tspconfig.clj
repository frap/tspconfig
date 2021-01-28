(ns atea.tspconfig
  (:require [clojure.java.io :as io]
            [clojure.set]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.java.shell :refer [sh]]
            ;[babashka.process :refer [$ check]]
            )
  (:import [java.lang ProcessBuilder$Redirect]
           [java.net URL HttpURLConnection]
           [java.nio.file Files FileSystems CopyOption])
  (:gen-class))

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

(defn is-redhat?
  [{:keys [release-file] :or {release-file "/etc/redhat-release"}}]
   (.exists (io/file release-file)))

(defn redhat-version
  [{:keys [release-file] :or {release-file "/etc/redhat-release"}}]
  (if (is-redhat? {:release-file release-file})
    (first (re-seq #"\d\.\d" (slurp release-file)))
    "N/A"
    ))

(defn ansi [style]
  (str \u001b (style ansi-styles)))

(defn colourise
  [text colour]
  (str (ansi colour) text (ansi :reset)))

(def exit-codes
  {:success      0
   :args-error   4
   :general-err  8})

(defn exit-message
  [message colour]
  (colourise message colour))

(defn system-exit!
  [message exit-code]
  (let [code (get exit-codes exit-code exit-code) ;; map key not-found
        colour (if (= code 0) :green :red)]
    (println (exit-message message colour))
    (System/exit code)))

(defn exec-command!
  "execute babashka shell command(s)"
  [cmd]
  (let [{:keys [exit err out]} (sh "sh" "-c" cmd)]
    (if (zero? exit)
      (system-exit! out :success)
      (let [msg (if (str/blank? err)
                  (format "failed to execute command [%s]: exit code [%d]" cmd exit)
                  err)]
        (println msg)
        (system-exit! msg exit)))))



(def help-text (colourise (str "Version: " tspconfig-clj-version "
Usage: tspconfig  [--] [init-opt*] [main-opt] [arg*]
       clj     [dep-opt*] [--] [init-opt*] [main-opt] [arg*]
The tspconfig script is a script to set redhat networking features.
You can use a customers config from Atea's email or change some part
of the config using command line options.
invoke a command-line of the form:

-H --hostname        Change host's hostname
-i --ipaddr          Change host' IP address
-g --gateway         Change host's IP Gateway
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

(defn current-vm-config [ hostname  network-cfg ]
  (let [name      (-> (sh "cat" hostname) :out str/trim )
        network   (slurp network-cfg)
        [_ ip]    (re-find #"IPADDR=(.*)" network)
        [_ gw]    (re-find #"GATEWAY=(.*)" network)
        [_ net]   (re-find #"NETMASK=(.*)" network)
        [_ dhcp]  (re-find #"BOOTPROTO=(.*)" network)
        ]
    {:hostname name
     :ip       ip
     :gateway  gw
     :subnet   net
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
        [_ hostname] (re-find #"Hostname\s?:\s+([A-Za-z0-9]+)\n" config)
        [_ ip]       (re-find #"IP Address\s?:\s+((?:[0-9]{1,3}\.){3}[0-9]{1,3})\n" config)
        [_ mask]     (re-find #"Subnet Mask\s?:\s+((?:[0-9]{1,3}\.){3}[0-9]{1,3})\n" config)
        [_ gw]       (re-find #"DefaultGateway\s?:\s+((?:[0-9]{1,3}\.){3}[0-9]{1,3})+" config)
        [_ dns]      (re-find #"DNS Server\s?:\s+((?:[0-9]{1,3}\.){3}[0-9]{1,3})[\s,]?" config)
        [_ smtp]     (re-find #"SMTP Server\s?:\s+((?:[0-9]{1,3}\.){3}[0-9]{1,3})+\n" config)
        [_ time]     (re-find #"Time Zone\s?:\s+([\w/]+)\n" config)
        [_ ntp]      (re-find #"NTP Server\s?:\s+((?:[0-9]{1,3}\.){3}[0-9]{1,3})+\n" config)
        [_ cucmhost] (re-find #"CUCM Hostname\s?:\s+([A-Za-z0-9\-]+)[a-z0-9].*\n" config)
        [_ cucmip]   (re-find #"CUCM Server\s?:\s+((?:[0-9]{1,3}\.){3}[0-9]{1,3})+\n" config)

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

(comment

  (println (:out (sh "ls" "-l")))
  (println (sh "ls" "-l" "/no-such-thing"))
  (println (sh "sed" "s/[aeiou]/oo/g" :in "hello there\n"))
  (println (:out (sh "sed" "s/[aeiou]/oo/g" :in (java.io.StringReader. "hello there\n"))))
  (println (shell-command "ls" "-l"))
  (parse-customer-config "Company Name : test \n Hostname: test\nDNS Server : 10.1.1.1, 10.1.1.2\n NTP Server : 6.6.6.6, 10.66.66.3")
  (println help-text)

                                        ; ...
  )
;(-main)
(defn -main [& command-line-args]
  (let [args (loop [command-line-args (seq command-line-args)
                    acc {}]
               (if command-line-args
                 (let [arg (first command-line-args)
                       string-opt-keyword (get parse-opts->keyword arg)]
                   (cond
                     string-opt-keyword (recur
                                         (nnext command-line-args)
                                         (assoc acc string-opt-keyword
                                                (second command-line-args)))
                     (or (= "-h" arg)
                         (= "--help" arg)) (assoc acc :help true)
                     :else (assoc acc :file arg )))
                 acc))
        - (println "args: " args)
        _ (when (:help args)
            (println help-text)
            ;;(System/exit 0)
            )
        args (when (:file args)
                 (let [f (io/file (:file args))]
                   (if (.exists f)
                     (merge args (parse-customer-config (slurp f))
                            ;;(System/exit 0)
                            )
                     (binding [*out* *err*]
                       (println f "does not exist")
                       ;;(System/exit 1)
                       ))))]
    (println "exit config: " args)
    ))
