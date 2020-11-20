(ns kaocha.plugin.cloverage
  (:require [cloverage.coverage :as c]
            [clojure.tools.reader :as reader]
            [kaocha.api :as api]
            [kaocha.plugin :as plugin :refer [defplugin]]
            [kaocha.result :as result]
            [kaocha.classpath :as cp]
            [slingshot.slingshot :refer [throw+]]))

(defn accumulate [m k v]
  (update m k (fnil conj []) v))

(def default-opts
  {:output "target/coverage"
   :text? false
   :html? true
   :emma-xml? false
   :lcov? false
   :codecov? false
   :coveralls? false
   :summary? true
   :fail-threshold 0
   :low-watermark 50
   :high-watermark 80
   :nop? false
   :src-ns-path []
   :ns-regex []
   :ns-exclude-regex []
   :exclude-call []
   :test-ns-regex []})

(def cli-opts*
  [["--cov-output PATH" "Cloverage output directory."]
   ["--[no-]cov-text" "Produce a text report."]
   ["--[no-]cov-html" "Produce a HTML report."]
   ["--[no-]emma-xml"
    "Produce an EMMA XML report. [emma.sourceforge.net]"]
   ["--[no-]lcov"
    "Produce a lcov/gcov report."]
   ["--[no-]codecov"
    "Generate a JSON report for Codecov.io"]
   ["--[no-]coveralls"
    "Send a JSON report to Coveralls if on a CI server"]
   ["--[no-]cov-summary"
    "Prints a summary"]
   ["--cov-fail-threshold PERCENT"
    "Sets the percentage threshold at which cloverage will abort the build. Default: 0%"
    :parse-fn #(Integer/parseInt %)]
   ["--cov-low-watermark PERCENT"
    "Sets the low watermark percentage (valid values 0..100). Default: 50%"
    :parse-fn #(Integer/parseInt %)]
   ["--cov-high-watermark PERCENT"
    "Sets the high watermark percentage (valid values 0..100). Default: 80%"
    :parse-fn #(Integer/parseInt %)]
   ["--[no-]cov-nop" "Instrument with noops."]
   ["--cov-test-ns-regex REGEX"
    "Regex for test namespaces (can be repeated)."
    :assoc-fn accumulate]
   ["--cov-ns-regex REGEX"
    "Regex for instrumented namespaces (can be repeated)."
    :assoc-fn accumulate]
   ["--cov-ns-exclude-regex REGEX"
    "Regex for namespaces not to be instrumented (can be repeated)."
    :assoc-fn accumulate]
   ["--cov-src-ns-path PATH"
    "Path (string) to directory containing test namespaces (can be repeated). Defaults to test suite source paths."
    :assoc-fn accumulate]
   ["--cov-exclude-call NAME"
    "Name of fn/macro whose call sites are not to be instrumented (can be repeated)."
    :assoc-fn accumulate]])

(def cli-opts (map #(into [nil] %) cli-opts*))

(defn update-config [config]
  (let [opts (:kaocha/cli-options config)]
    (update config
            :cloverage/opts
            #(cond-> (merge default-opts %)
               (contains? opts :cov-output)
               (assoc :output (:cov-output opts))

               (contains? opts :cov-text)
               (assoc :text? (:cov-text opts))

               (contains? opts :cov-html)
               (assoc :html? (:cov-html opts))

               (contains? opts :emma-xml)
               (assoc :emma-xml? (:emma-xml opts))

               (contains? opts :lcov)
               (assoc :lcov? (:lcov opts))

               (contains? opts :codecov)
               (assoc :codecov? (:codecov opts))

               (contains? opts :coveralls)
               (assoc :coveralls? (:coveralls opts))

               (contains? opts :cov-summary)
               (assoc :summary? (:cov-summary opts))

               (contains? opts :cov-fail-threshold)
               (assoc :fail-threshold (:cov-fail-threshold opts))

               (contains? opts :cov-low-watermark)
               (assoc :low-watermark (:cov-low-watermark opts))

               (contains? opts :cov-high-watermark)
               (assoc :high-watermark (:cov-high-watermark opts))

               (contains? opts :cov-nop)
               (assoc :nop? (:cov-nop opts))

               (contains? opts :cov-src-ns-path)
               (assoc :src-ns-path (:cov-src-ns-path opts))

               (contains? opts :cov-exclude-call)
               (update :exclude-call into (map symbol (:cov-exclude-call opts)))

               (contains? opts :cov-ns-regex)
               (assoc :ns-regex (:cov-ns-regex opts))

               (contains? opts :cov-ns-exclude-regex)
               (update :ns-exclude-regex into (:cov-ns-exclude-regex opts))

               (contains? opts :cov-test-ns-regex)
               (update :test-ns-regex into (:cov-test-ns-regex opts))

               :always
               (update :test-ns-regex (partial mapv re-pattern))

               :always
               (update :ns-regex (partial mapv re-pattern))

               :always
               (update :ns-exclude-regex (partial mapv re-pattern))))))

(defn run-cloverage [opts]
  ;; Compatibility with future versions
  (let [arity      (count (first (:arglists (meta #'c/run-main))))
        decls      (-> []
                       (.getClass)
                       (.getClassLoader)
                       (.getResources "data_readers.clj")
                       enumeration-seq)
        read-decls (comp read-string slurp)
        readers    (reduce merge {} (map read-decls decls))]
    (binding [reader/*data-readers* readers]
      (case arity
        1 (c/run-main [opts])
        2 (c/run-main [opts] {})))))

(defplugin kaocha.plugin/cloverage
  (cli-options [opts]
    (into opts cli-opts))

  ;; The config hook only gets called inside api/run, which is too late for us,
  ;; so update-config also gets called explicitly in `main`. Calling
  ;; update-config here anyway so people can see the result of option merging
  ;; with --print-config. It's a bit of duplicated work though, we might have to
  ;; rethink this to make people implementing custom `main` hooks able to still
  ;; use `config` hooks.

  (config [config]
    (update-config config))

  (main [config]
    (binding [c/*exit-after-test* false]
      (let [config         (update-config config)
            suites         (remove :kaocha.testable/skip (:kaocha/tests config))
            source-paths   (mapcat :kaocha/source-paths suites)
            test-paths     (mapcat :kaocha/test-paths suites)
            tests          (map :kaocha.testable/id suites)
            cloverage-opts (:cloverage/opts config)
            opts           (assoc cloverage-opts
                                  :runner :kaocha
                                  :src-ns-path (if-let [src-ns-path (seq (:src-ns-path cloverage-opts))]
                                                 src-ns-path
                                                 source-paths)
                                  :extra-test-ns tests
                                  ;;:debug? true
                                  ::config config)]
        (run! cp/add-classpath test-paths)
        (throw+ {:kaocha/early-exit (run-cloverage opts)})))))

(defmethod c/runner-fn :kaocha [opts]
  (fn [_test-nses]
    (let [result (->> (::config opts)
                      api/run
                      (plugin/run-hook :kaocha.hooks/post-summary)
                      :kaocha.result/tests
                      result/totals)]
      {:errors (+ (::result/error result)
                  (::result/fail result))})))
