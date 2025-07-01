(ns jepsen.ratis.db
  "Mock database setup for Ratis Jepsen tests"
  (:require [clojure.tools.logging :refer [info warn error debug]]
            [jepsen.db :as db]))

(defrecord MockRatisDB [version]
  db/DB
  (setup! [_ test node]
    (info node "Setting up mock Ratis database")
    (Thread/sleep 1000) ; Simulate setup time
    (info node "Mock Ratis setup complete"))

  (teardown! [_ test node]
    (info node "Tearing down mock Ratis database")
    (Thread/sleep 500) ; Simulate teardown time
    (info node "Mock Ratis teardown complete"))

  db/LogFiles
  (log-files [_ test node]
    []))

(defn db
  "Create a mock Ratis database for the given version"
  [version]
  (MockRatisDB. version)) 