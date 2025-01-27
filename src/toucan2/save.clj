(ns toucan2.save
  (:require
   [methodical.core :as m]
   [toucan2.connection :as conn]
   [toucan2.instance :as instance]
   [toucan2.log :as log]
   [toucan2.model :as model]
   [toucan2.protocols :as protocols]
   [toucan2.update :as update]
   [toucan2.util :as u]))

(set! *warn-on-reflection* true)

(m/defmulti save!*
  {:arglists '([object])}
  (fn [object]
    (protocols/dispatch-value (protocols/model object))))

(m/defmethod save!* :around :default
  [object]
  (u/try-with-error-context ["save changes" {::model   (protocols/model object)
                                             ::object  object
                                             ::changes (protocols/changes object)}]
    (log/debugf :compile "Save %s %s changes %s" (protocols/model object) object (protocols/changes object))
    (next-method object)))

(m/defmethod save!* :default
  [object]
  (assert (instance/instance? object)
          (format "Don't know how to save something that's not a Toucan instance. Got: ^%s %s"
                  (some-> object class .getCanonicalName)
                  (pr-str object)))
  (if-let [changes (not-empty (protocols/changes object))]
    (let [model         (protocols/model object)
          pk-values     (select-keys object (model/primary-keys (protocols/model object)))
          rows-affected (update/update! model pk-values changes)]
      (when-not (pos? rows-affected)
        (throw (ex-info (format "Unable to save object: %s with primary key %s does not exist."
                                (pr-str model)
                                (pr-str pk-values))
                        {:object object
                         :pk     pk-values})))
      (when (> rows-affected 1)
        (log/warnf :results "Warning: more than 1 row affected when saving %s with primary key %s" model pk-values))
      (instance/reset-original object))
    object))

(defn save!
  ([object]
   (save!* object))
  ([connectable object]
   (if connectable
     (binding [conn/*current-connectable* connectable]
       (save!* object))
     (save!* object))))
