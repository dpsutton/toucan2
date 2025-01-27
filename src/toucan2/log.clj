(ns toucan2.log
  "Toucan 2 logging utilities. This is basically just a fancy wrapper around `clojure.tools.logging` that supports some
  additional debugging facilities, such as dynamically enabling logging for different topics or levels from the REPL."
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [clojure.tools.logging :as tools.log]
   [clojure.tools.logging.impl :as tools.log.impl]
   [environ.core :as env]
   [pretty.core :as pretty]
   [puget.color]
   [puget.printer :as puget]))

(set! *warn-on-reflection* true)

(defonce ^{:doc "All known Toucan 2 logging topics. Log messages must be associated with one of these topics. Some
  namespaces define additional topics, such as [[toucan2.tools.hydrate]]."} ^:no-doc
  all-topics
  (atom #{:compile :execute :results}))

(def ^:dynamic *level*
  "The current log level (as a keyword) to log messages directly to stdout with. By default this is `nil`, which means
  don't log any messages to stdout regardless of their level. (They are still logged via `clojure.tools.logging`.)

  You can dynamically bind this to enable logging at a higher level in the REPL for debugging purposes. You can also set
  a default value for this by setting the atom [[level]]."
  nil)

(defonce ^{:doc "The default log level to log messages directly to stdout with. Takes the value of the env var
  `TOUCAN_DEBUG_LEVEL`, if set. Can be overridden with [[*level*]]. This is stored as an atom, so you can `swap!` or
  `reset!` it."} level
  (atom (some-> (env/env :toucan-debug-level) keyword)))

(def ^:dynamic *topics*
  "The set of topics to log directly to stdout, if the [[*level*]] is high enough. By default, this is `nil`, which means
  log *all* topics. You can specify default topics by setting the atom [[topics]]."
  nil)

(defn- env-var-topics
  ([]
   (env-var-topics (env/env :toucan-debug-topics)))
  ([s]
   (when (seq s)
     (when-let [topic-strs (str/split s #",")]
       (into #{} (map keyword) topic-strs)))))

(defonce ^{:doc "Default *topics* to log directly to stdout, if the level is high enough. This can be set with a
  comma-separated list with the env var `TOUCAN_DEBUG_TOPICS`. It is an atom, so you can also `swap!` or `reset!` it at
  runtime."}
  topics
  (atom (env-var-topics)))

(def ^:private ^:dynamic *color*
  "Whether or not to print the trace in color. True by default, unless the env var `NO_COLOR` is true."
  (if-let [env-var-value (env/env :no-color)]
    (complement (Boolean/parseBoolean env-var-value))
    true))

(defmulti ^:no-doc print-handler
  "Puget printer method used when logging. Dispatches on class."
  {:arglists '([klass])}
  (fn [klass]
    klass))

(defmethod print-handler :default
  [_klass]
  nil)

(defmethod print-handler pretty.core.PrettyPrintable
  [_klass]
  (fn [printer x]
    (puget/format-doc printer (pretty/pretty x))))

(defmethod print-handler clojure.core.Eduction
  [_klass]
  (fn [printer ^clojure.core.Eduction ed]
    (puget/format-doc printer (list 'eduction (.xform ed) (.coll ed)))))

(defmethod print-handler clojure.lang.IRecord
  [klass]
  (when (isa? klass clojure.lang.IReduceInit)
    (fn [_printer x]
      [:text (str x)])))

(defrecord ^:no-doc Doc [forms])

(defmethod print-handler Doc
  [_klass]
  (fn [printer {:keys [forms ns-symb]}]
    (let [ns-symb (when ns-symb
                    (symbol (last (str/split (name ns-symb) #"\."))))]
      [:group
       (when ns-symb
         [:span
          (puget.color/document printer :number (name ns-symb))
          [:text " "]])
       [:align
        (for [form forms]
          (puget/format-doc printer form))]])))

(defmethod print-handler Class
  [_klass]
  (fn [printer ^Class klass]
    (puget.color/document printer :class-name (.getCanonicalName klass))))

(defrecord ^:no-doc Text [s])

(defmethod print-handler Text
  [_klass]
  (fn [_printer {:keys [s]}]
    [:span
     [:text s]
     [:line]]))

(prefer-method print-handler pretty.core.PrettyPrintable clojure.lang.IRecord)

(def ^:private printer-options
  {:print-handlers print-handler
   :width          120
   :print-meta     true
   :coll-limit     5})

(defn- default-color-printer [x]
  ;; don't print in black. I can't see it
  (puget/cprint x (assoc printer-options :color-scheme {:nil [:green]})))

(defn- default-boring-printer [x]
  (puget/pprint x printer-options))

(defn- pretty-printer []
  (if *color*
    default-color-printer
    default-boring-printer))

(defn ^:no-doc pprint-doc
  "Pretty print a `doc`."
  ([doc]
   (pprint-doc nil doc))
  ([ns-symb doc]
   (try
     ((pretty-printer) (assoc doc :ns-symb ns-symb))
     (catch Throwable e
       (throw (ex-info (format "Error pretty printing doc: %s" (ex-message e))
                       {:doc doc}
                       e))))))

(defn ^:no-doc pprint-doc-to-str
  "Implementation of the `log` macros. You shouldn't need to use this directly."
  [doc]
  (str/trim (with-out-str (pprint-doc doc))))

(defn- interleave-all
  "Exactly like [[interleave]] but includes the entirety of both collections even if the other collection is shorter. If
  one collection 'runs out', the remaining elements of the other collection are appended directly to the end of the
  resulting collection."
  [x y]
  (loop [acc [], x x, y y]
    (let [acc (cond-> acc
                (seq x) (conj (first x))
                (seq y) (conj (first y)))
          x   (next x)
          y   (next y)]
      (if (and (empty? x) (empty? y))
        acc
        (recur acc x y)))))

(defn- build-doc
  "Convert `format-string` and `args` into something that can be pretty-printed by puget."
  [format-string & args]
  (let [->Text* (fn [s]
                  (list `->Text (str/trimr s)))
        texts   (map ->Text* (str/split format-string #"%s"))]
    `(->Doc ~(vec (interleave-all texts args)))))

(defn- level->int [a-level default]
  (case a-level
    :disabled 5
    :error    4
    :warn     3
    :info     2
    :debug    1
    :trace    0
    default))

(defn ^:no-doc enable-level?
  "Whether to enable logging for `a-level`."
  [a-level]
  (let [current-level (or *level* @level)
        level-int     (level->int current-level Integer/MAX_VALUE)
        a-level-int   (level->int a-level 0)]
    (>= a-level-int level-int)))

(defn ^:no-doc enable-topic?
  "Whether to enable logging for `a-topic`."
  [a-topic]
  (if-let [current-topics (or *topics* @topics)]
    (contains? current-topics a-topic)
    true))

;;; TODO -- this gets called all over the place over and over again; we could easily optimize it by turning this into a
;;; macro and doing some of the calculations like level keyword => int in the macroexpansion itself instead of over and
;;; over at runtime

(defn ^:no-doc enable-debug-message?
  "Whether to enable logging for `a-level` *and* `a-topic`"
  [a-level a-topic]
  (and (enable-level? a-level)
       (enable-topic? a-topic)))

(defn ^:no-doc enabled-logger
  "Get a logger factor for the namespace named by symbol `ns-symb` at `a-level`, **iff** logging is enabled for that
  namespace and level. The logger returned is something that satisfies the `clojure.tools.logging.impl.LoggerFactory`
  protocol."
  [ns-symb a-level]
  (let [logger (tools.log.impl/get-logger tools.log/*logger-factory* ns-symb)]
    (when (tools.log.impl/enabled? logger a-level)
      logger)))

(defmacro ^:no-doc log*
  "Implementation of various `log` macros. Don't use this directly."
  [a-level a-topic e doc]
  `(let [doc# (delay ~doc)]
     (when (enable-debug-message? ~a-level ~a-topic)
       (pprint-doc '~(ns-name *ns*) @doc#))
     (when-let [logger# (enabled-logger '~(ns-name *ns*) ~a-level)]
       (tools.log/log* logger# ~a-level ~e (pprint-doc-to-str @doc#)))))

(defmacro ^:no-doc logf
  "Implementation of various `log` macros. Don't use this directly."
  [a-level a-topic & args]
  (let [[e format-string & args] (if (string? (first args))
                                   (cons nil args)
                                   args)]
    (assert (string? format-string))
    (let [doc (apply build-doc format-string args)]
      `(log* ~a-level ~a-topic ~e ~doc))))

;;; The log macros only work with `%s` for now.
(defn- correct-number-of-args-for-format-string? [{:keys [msg args]}]
  (let [matcher                 (re-matcher #"%s" msg)
        format-string-arg-count (count (take-while some? (repeatedly #(re-find matcher))))]
    (= (count args) format-string-arg-count)))

(defn- pr-str-form? [form]
  (and (seq? form)
       (= (first form) 'pr-str)))

(s/def ::args
  (s/& (s/cat :topic #(contains? @all-topics %)
              :e     (s/? (complement string?))
              :msg   string?
              :args  (s/* (complement pr-str-form?)))
       correct-number-of-args-for-format-string?))

(defmacro errorf
  "Log an error message for a `topic`. Optionally include a `throwable`. Only things that are actually serious errors
  should be logged at this level."
  {:arglists '([topic throwable? s] [topic throwable? format-string & args])}
  [& args]
  `(logf :error ~@args))

(s/fdef errorf
  :args ::args
  :ret  any?)

(defmacro warnf
  "Log a warning for a `topic`. Optionally include a `throwable`. Bad things that can be worked around should be logged at
  this level."
  {:arglists '([topic throwable? s] [topic throwable? format-string & args])}
  [& args]
  `(logf :warn ~@args))

(s/fdef warnf
  :args ::args
  :ret  any?)

(defmacro infof
  "Only things that all users should see by default without configuring a logger should be this level."
  {:arglists '([topic throwable? s] [topic throwable? format-string & args])}
  [& args]
  `(logf :info ~@args))

(s/fdef infof
  :args ::args
  :ret  any?)

(defmacro debugf
  "Most log messages should be this level."
  {:arglists '([topic throwable? s] [topic throwable? format-string & args])}
  [& args]
  `(logf :debug ~@args))

(s/fdef debugf
  :args ::args
  :ret  any?)

(defmacro tracef
  "Log messages that are done once-per-row should be this level."
  {:arglists '([topic throwable? s] [topic throwable? format-string & args])}
  [& args]
  `(logf :trace ~@args))

(s/fdef tracef
  :args ::args
  :ret  any?)

(doseq [varr [#'errorf #'warnf #'infof #'debugf #'tracef]]
  (alter-meta! varr assoc :arglists (list
                                     [@all-topics 'e? 'msg]
                                     [@all-topics 'e? 'format-string '& 'args])))

(comment
  (reset! topics #{:compile})
  (reset! level :debug)
  (tracef :compile "VERY NICE MESSAGE %s 1000" :abc))
