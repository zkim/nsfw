(defproject nsfw "0.9.1"
  :description "No Such Framework -- Experimental"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "0.0-3308"]
                 [clj-stacktrace "0.2.8"]
                 [cheshire "5.5.0"]
                 [hiccup "1.0.5"]
                 [congomongo "0.4.4"]
                 [ring "1.4.0"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [com.cognitect/transit-cljs "0.8.220"]
                 [com.cognitect/transit-clj "0.8.275"]
                 [aleph "0.4.0"]
                 [byte-transforms "0.1.4"]
                 [slingshot "0.12.2"]
                 [clj-http "1.1.2"]
                 [prismatic/dommy "1.1.0"]
                 [joda-time/joda-time "2.8.1"]
                 [oauth-clj "0.1.13"]
                 [org.clojure/tools.namespace "0.2.11"]
                 [markdown-clj "0.9.67"]
                 [prismatic/plumbing "0.4.4"]
                 [clout "2.1.2"]
                 [org.pegdown/pegdown "1.4.1"]
                 [com.draines/postal "1.11.3"]
                 [bidi "1.15.0" :exclusions [org.clojure/clojure]]
                 [hashobject/hashids "0.2.0"]
                 [org.clojure/java.classpath "0.2.2"]
                 [prismatic/schema "0.4.3" :exclusions [potemkin]]
                 [garden "1.2.6"]]
  :repl-options {:init (load-file "reup.clj")}
  :source-paths ["src/clj"]
  :test-paths ["test/clj"]
  :jar-name "nsfw.jar"
  :cljsbuild {:builds
              [{:source-paths ["src/cljs"]
                :compiler {:output-to "resources/test.js"
                           :optimizations :whitespace}
                :jar true}]})
