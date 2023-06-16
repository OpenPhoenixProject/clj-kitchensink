(ns puppetlabs.kitchensink.classpath
  (:import (java.net URLClassLoader URL))
  (:require [clojure.java.io :refer [file Coercions]]
            [dynapath.util :as dp])
  (:refer-clojure :exclude (add-classpath)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Here, we have copied `add-classpath` out of pomegranate
;;;; (https://github.com/cemerick/pomegranate).
;;;;
;;;; We did this because we needed a corollary to the (deprecated)
;;;; `add-classpath` function in clojure.core,
;;;; but we did not want to pull pomegranate's rather large dependency tree
;;;; (we tried excluding as many of its dependencies as possible, but only
;;;; a small part of its dependency tree was exclude-able).
;;;;
;;;; There are no tests for these functions because pomegranate does not
;;;; contain any tests for them.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- classloader-hierarchy
  "Returns a seq of classloaders, with the tip of the hierarchy first.
   Uses the current thread context ClassLoader as the tip ClassLoader
   if one is not provided."
  ([] (classloader-hierarchy (. (Thread/currentThread) getContextClassLoader)))
  ([tip]
   (->> tip
        (iterate #(.getParent ^ClassLoader %))
        (take-while boolean))))

(defn- modifiable-classloader?
  "Returns true iff the given ClassLoader is of a type that satisfies
   the dynapath.dynamic-classpath/DynamicClasspath protocol, and it can
   be modified."
  [cl]
  (dp/addable-classpath? cl))

(defn- ensure-modifiable-classloader
  "Check if there is a modifiable classloader in the current hierarchy, and add
  one if not."
  []
  (let [classloader (.. Thread currentThread getContextClassLoader)]
    (when (not-any? modifiable-classloader? (classloader-hierarchy classloader))
      (let [new-cl (clojure.lang.DynamicClassLoader. classloader)]
        (.. Thread currentThread (setContextClassLoader new-cl))))))

(defn add-classpath
  "A corollary to the (deprecated) `add-classpath` in clojure.core. This implementation
   requires a java.io.File or String path to a jar file or directory, and will attempt
   to add that path to the right classloader (with the search rooted at the current
   thread's context classloader).

   Because this function is a replacement for `add-classpath` in clojure.core,
   if you simply `:require` this namespace and then `:refer` to this function, you
   will get the following warning:

      WARNING: add-classpath already refers to: #'clojure.core/add-classpath in
      namespace: [...], being replaced by:
      #'puppetlabs.kitchensink.classpath/add-classpath

   You can avoid this by referencing this function through its namespace.

   This function is copied out of the 'pomegranate' library
   (https://github.com/cemerick/pomegranate)."
  ([jar-or-dir classloader]
   (when-not (dp/add-classpath-url classloader (.toURL (file jar-or-dir)))
     (throw (IllegalStateException. (str classloader " is not a modifiable classloader")))))
  ([jar-or-dir]
   (ensure-modifiable-classloader)
   (let [classloaders (classloader-hierarchy)]
     (if-let [cl (last (filter modifiable-classloader? classloaders))]
       (add-classpath jar-or-dir cl)
       (throw (IllegalStateException. (str "Could not find a suitable classloader to modify from "
                                           classloaders)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; end of functions copied from pomegranate (see note above)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn jar-or-dir-to-url
  "Given the path to a jar file or a directory, return a `java.net.URL`
  object suitable for using with a `URLClassLoader`"
  [jar-or-dir]
  {:pre [(satisfies? Coercions jar-or-dir)]
   :post [(instance? URL %)]}
  ;; explicitly calling `getAbsoluteFile` causes relative file paths to
  ;; be evaluated relative to the system property `user.dir` (which is
  ;; usually set to the current working directory).  This is useful for
  ;; tests and other code that wants to emulated changing the working
  ;; directory.
  (.. (file jar-or-dir) getAbsoluteFile toURL))

(defmacro with-additional-classpath-entries
  "This macro takes a list of paths as an argument.  It then temporarily
  overrides the classpath to include the specified paths; the original
  classpath is restored prior to returning."
  [jars-and-dirs & body]
  `{:pre [(coll? ~jars-and-dirs)
          (every? (partial satisfies? Coercions) ~jars-and-dirs)]}
  `(let [orig-loader# (.. Thread currentThread getContextClassLoader)
         temp-loader# (URLClassLoader.
                        (into-array
                          URL
                          (map jar-or-dir-to-url ~jars-and-dirs))
                        orig-loader#)]
     (try
       (.. Thread currentThread (setContextClassLoader temp-loader#))
       ~@body
       (finally (.. Thread currentThread (setContextClassLoader orig-loader#))))))
