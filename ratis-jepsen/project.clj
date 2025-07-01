(defproject ratis-jepsen "0.1.0-SNAPSHOT"
  :description "Jepsen tests for Apache Ratis"
  :url "https://ratis.apache.org"
  :license {:name "Apache License 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [jepsen "0.3.9"]
                 [cheshire "5.11.0"]]
  :exclusions [org.slf4j/slf4j-log4j12]
  :main jepsen.ratis.core
  :profiles {:uberjar {:aot :all}
             :dev {:dependencies [[org.clojure/tools.namespace "1.4.4"]]
                   :source-paths ["dev"]}}
  :jvm-opts ["-Djava.awt.headless=true"]) 