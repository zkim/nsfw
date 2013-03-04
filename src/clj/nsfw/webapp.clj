(ns nsfw.webapp
  (:require [net.cgrand.moustache :as moustache]
            [nsfw.html :as html]
            [nsfw.middleware :as nm]
            [nsfw.util :as nu]
            [clojure.string :as str]))

(defmacro routes [& [opts & rest :as body]]
  (let [opts (if (map? opts) opts {})
        body (if (map? opts) rest body)]
    `(moustache/app
      (nm/wrap-web-defaults ~opts)
      ~@body)))

(defn cs
  "Provies a route helper to set up a clojurescript app.

   (webapp/cs :examples ; name of js file
              :entry 'nsfw.foo ; runs nsfw.foo.main()
              :data {:functions fns}) ; embeds `fns` as
                                      ; `var functions = ...`"
  [script & ops]
  (fn [r]
    (let [{:keys [entry title data css google-maps js]}
          (apply hash-map ops)
          data (when data
                 (data r))
          css (if (coll? css)
                css
                [css])]
      {:headers {"Content-Type" "text/html"}
       :body (html/html5
              [:head
               [:meta {:name "viewport" :content "width=device-width" :initial-scale "1"}]
               (when title
                 [:title title])
               (if-not (empty? css)
                 (map #(html/stylesheet (str "/css/" (name %) ".css")) css)
                 (html/stylesheet (str "/css/" (name script) ".css")))]
              [:body
               (when js
                 (map (fn [src]
                        [:script {:type "text/javascript"
                                  :src (if (keyword? src)
                                         (str "/js/" (name src) ".js")
                                         src)}])
                      js))
               (when google-maps
                 [:script {:type "text/javascript"
                           :src "http://maps.googleapis.com/maps/api/js?sensor=false"}])
               (when data
                 [:script {:type "text/javascript"}
                  (str
                   (->> data
                        (map #(str "window."
                                   (str/replace (name (key %)) #"-" "_")
                                   " = "
                                   (-> % val pr-str nu/to-json)))
                        (interpose ";")
                        (apply str))
                   ";")])
               (html/script (str "/js/" (name script) ".js"))
               (when entry
                 [:script {:type "text/javascript"}
                  (str (name entry) ".main()")])])})))