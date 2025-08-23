(ns jepsen.ratis.db
  "Database setup for Ratis Jepsen tests"
  (:require [clojure.tools.logging :refer [info warn error debug]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [jepsen.db :as db]
            [jepsen.control :as c]
            [jepsen.control.util :as cu]
            [jepsen.util :refer [meh timeout]]))

;; Configuration constants
(def ratis-dir "/opt/ratis")
(def ratis-bin (str ratis-dir "/bin"))
(def ratis-log-dir "/var/log/ratis")
(def ratis-data-dir "/var/lib/ratis")
(def ratis-port 6000)
(def counter-port 9999)

;; Helper functions for Ratis server management

(defn install-java!
  "Install Java 11 on the node if not present"
  []
  (info "Installing Java...")
  (c/su
    (try
      (c/exec :java :-version)
      (info "Java already installed")
      (catch Exception _
        (info "Installing OpenJDK 11...")
        (c/exec :apt-get :update)
        (c/exec :apt-get :install :-y :openjdk-11-jdk)))))

(defn setup-directories!
  "Create necessary directories for Ratis"
  []
  (info "Setting up Ratis directories...")
  (c/su
    (c/exec :mkdir :-p ratis-dir ratis-bin ratis-log-dir ratis-data-dir)
    (c/exec :chmod :755 ratis-dir ratis-bin ratis-log-dir ratis-data-dir)))

(defn copy-ratis-binary!
  "Copy Ratis JAR to the target node"
  [version]
  (info "Copying Ratis binary...")
  (c/su
    (let [jar-name (str "ratis-examples-" version ".jar")
          local-jar (str "../ratis-examples/target/" jar-name)
          remote-jar (str ratis-bin "/" jar-name)]
      (c/upload local-jar remote-jar)
      (c/exec :chmod :755 remote-jar))))

(defn create-config!
  "Create Ratis configuration for the node"
  [test node]
  (info "Creating Ratis configuration for" node)
  (let [nodes (:nodes test)
        node-id (str node)
        peers (str/join "," 
                       (map #(str % ":" ratis-port) nodes))
        config-content (str "# Ratis configuration for " node "\n"
                           "raft.server.id=" node-id "\n"
                           "raft.server.address=" node ":" ratis-port "\n"
                           "raft.client.address=" node ":" counter-port "\n"
                           "raft.server.rpc.type=GRPC\n"
                           "raft.client.rpc.type=GRPC\n"
                           "raft.server.storage.dir=" ratis-data-dir "/" node-id "\n"
                           "raft.server.log.level=INFO\n")]
    (c/su
      (c/exec :mkdir :-p (str ratis-data-dir "/" node-id))
      (cu/write-file! (str ratis-dir "/server.properties") config-content))))

(defn start-ratis!
  "Start Ratis server on the node"
  [test node version]
  (info "Starting Ratis server on" node)
  (let [jar-path (str ratis-bin "/ratis-examples-" version ".jar")
        node-id (str node)
        nodes (:nodes test)
        peers (str/join "," 
                       (map #(str % ":" ratis-port) nodes))
        log-file (str ratis-log-dir "/" node ".log")]
    (c/su
      (c/exec :mkdir :-p ratis-log-dir)
      (cu/start-daemon! 
        {:logfile log-file
         :pidfile (str ratis-dir "/" node ".pid")
         :chdir ratis-dir}
        :java
        :-Dlog4j.configuration=log4j.properties
        :-jar jar-path
        :counterServer
        :-id node-id
        :-cluster peers
        :-port counter-port
        :-raftPort ratis-port))))

(defn stop-ratis!
  "Stop Ratis server on the node"
  [node]
  (info "Stopping Ratis server on" node)
  (c/su
    (meh (cu/stop-daemon! (str ratis-dir "/" node ".pid")))
    (meh (c/exec :pkill :-f "ratis-examples"))
    (c/exec :rm :-f (str ratis-dir "/" node ".pid"))))

(defn wipe-data!
  "Wipe all Ratis data from the node"
  [node]
  (info "Wiping Ratis data on" node)
  (c/su
    (meh (c/exec :rm :-rf ratis-data-dir))
    (meh (c/exec :rm :-rf ratis-log-dir))
    (c/exec :mkdir :-p ratis-data-dir ratis-log-dir)))

;; Real Ratis database implementation
(defrecord RatisDB [version]
  db/DB
  (setup! [_ test node]
    (info node "Setting up Ratis database version" version)
    (install-java!)
    (setup-directories!)
    (copy-ratis-binary! version)
    (create-config! test node)
    (start-ratis! test node version)
    (Thread/sleep 5000) ; Wait for server to start
    (info node "Ratis setup complete"))

  (teardown! [_ test node]
    (info node "Tearing down Ratis database")
    (stop-ratis! node)
    (wipe-data! node)
    (info node "Ratis teardown complete"))

  db/LogFiles
  (log-files [_ test node]
    [(str ratis-log-dir "/" node ".log")]))

;; Keep MockRatisDB for backward compatibility during testing
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
  "Create a Ratis database for the given version"
  [version]
  (RatisDB. version))

(defn mock-db
  "Create a mock Ratis database for the given version"
  [version]
  (MockRatisDB. version)) 