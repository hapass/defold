(ns editor.targets-test
  (:require [clojure.string :as string]
            [clojure.test :refer :all]
            [editor.targets :as targets]
            [integration.test-util :as test-util])
  (:import (com.dynamo.upnp DeviceInfo SSDP)
           (java.io IOException)
           (java.net SocketTimeoutException URL)))

(def ^:private device-desc-template "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n
<root xmlns=\"urn:schemas-upnp-org:device-1-0\" xmlns:defold=\"urn:schemas-defold-com:DEFOLD-1-0\">\n
    <specVersion>\n
        <major>1</major>\n
        <minor>0</minor>\n
    </specVersion>\n
    <device>\n
        <deviceType>upnp:rootdevice</deviceType>\n
        <friendlyName>${NAME}</friendlyName>\n
        <manufacturer>Defold</manufacturer>\n
        <modelName>Defold Engine 1.0</modelName>\n
        <UDN>${UDN}</UDN>\n
        <defold:url>http://${HOSTNAME}:${DEFOLD_PORT}</defold:url>\n
        <defold:logPort>${DEFOLD_LOG_PORT}</defold:logPort>\n
    </device>\n
</root>\n")

(def ^:private required-device-desc-device-tags
  ["defold:logPort"
   "defold:url"
   "friendlyName"
   "manufacturer"
   "modelName"
   "UDN"])

(defn- ^:private device-desc-template-without
  "Returns the device desc template with the specified device tag removed."
  [device-tag]
  (->> device-desc-template
       string/split-lines
       (remove (fn [line] (string/includes? line (str "<" device-tag ">"))))
       (string/join "\n")))

(def ^:private defold-port (.getPort (URL. (:url targets/local-target))))
(def ^:private defold-log-port 12345)

(defn- make-udn
  [local-address device-model]
  (str "defold-" local-address "-" device-model))

(defn- make-device-url
  [hostname id]
  (URL. (str "http://" hostname ":" defold-port "/" id)))

(defn- make-device-desc
  [template name device-model hostname]
  (-> template
      (string/replace "${NAME}" name)
      (string/replace "${UDN}" (make-udn hostname device-model))
      (string/replace "${HOSTNAME}" hostname)
      (string/replace "${DEFOLD_PORT}" (str defold-port))
      (string/replace "${DEFOLD_LOG_PORT}" (str defold-log-port))))

(def ^:private local-hostname (:local-address targets/local-target))
(def ^:private local-id "local-id")
(def ^:private local-url (make-device-url local-hostname local-id))
(def ^:private iphone-hostname "iphone-hostname")
(def ^:private iphone-id "iphone-id")
(def ^:private iphone-url (make-device-url iphone-hostname iphone-id))
(def ^:private tablet-hostname "tablet-hostname")
(def ^:private tablet-id "tablet-id")
(def ^:private tablet-url (make-device-url tablet-hostname tablet-id))

(def ^:private fetch-url
  {local-url (make-device-desc device-desc-template (:name targets/local-target) "osx" local-hostname)
   iphone-url (make-device-desc device-desc-template "iPhone" "ios" iphone-hostname)
   tablet-url (make-device-desc device-desc-template "Tablet" "android" tablet-hostname)})

(defn- make-context
  []
  {:targets-atom (atom #{targets/local-target})
   :blacklist-atom (atom #{})
   :descriptions-atom (atom {})
   :log-fn (test-util/make-call-logger)
   :fetch-url-fn fetch-url
   :on-targets-changed-fn (test-util/make-call-logger)})

(defn- make-device-info
  [hostname id]
  (let [address (str (make-device-url hostname id))]
    (DeviceInfo/create {"LOCATION" address
                        "SERVER" SSDP/SSDP_SERVER_IDENTIFIER} address hostname)))

(def ^:private make-local-device-info (partial make-device-info local-hostname local-id))
(def ^:private make-iphone-device-info (partial make-device-info iphone-hostname iphone-id))
(def ^:private make-tablet-device-info (partial make-device-info tablet-hostname tablet-id))

(defn- targets-hostnames
  [targets]
  (->> targets (map :local-address) sort vec))

(defn- blacklist-hostnames
  [blacklist]
  (->> blacklist (map #(.getHost %)) sort vec))

(defn- descriptions-hostnames
  [descriptions]
  (->> descriptions keys (map #(.getHost %)) sort vec))

(def ^:private call-count (comp count test-util/call-logger-calls))

(deftest update-targets-with-new-devices
  (let [{:keys [targets-atom
                descriptions-atom
                blacklist-atom
                on-targets-changed-fn] :as context} (make-context)]
    (testing "iPhone joins"
      (targets/update-targets! context [(make-local-device-info) (make-iphone-device-info)])
      (is (= [iphone-hostname local-hostname] (targets-hostnames @targets-atom)))
      (is (= [iphone-hostname local-hostname] (descriptions-hostnames @descriptions-atom)))
      (is (= [] (blacklist-hostnames @blacklist-atom)))
      (is (= 1 (call-count on-targets-changed-fn))))
    (testing "iPhone remains, tablet joins"
      (targets/update-targets! context [(make-local-device-info) (make-iphone-device-info) (make-tablet-device-info)])
      (is (= [iphone-hostname local-hostname tablet-hostname] (targets-hostnames @targets-atom)))
      (is (= [iphone-hostname local-hostname tablet-hostname] (descriptions-hostnames @descriptions-atom)))
      (is (= [] (blacklist-hostnames @blacklist-atom)))
      (is (= 2 (call-count on-targets-changed-fn))))))

(deftest update-targets-with-known-devices
  (let [{:keys [targets-atom
                descriptions-atom
                blacklist-atom
                on-targets-changed-fn] :as context} (make-context)]
    (testing "iPhone joins, then remains"
      (targets/update-targets! context [(make-local-device-info) (make-iphone-device-info)])
      (targets/update-targets! context [(make-local-device-info) (make-iphone-device-info)])
      (is (= [iphone-hostname local-hostname] (targets-hostnames @targets-atom)))
      (is (= [iphone-hostname local-hostname] (descriptions-hostnames @descriptions-atom)))
      (is (= [] (blacklist-hostnames @blacklist-atom)))
      (is (= 1 (call-count on-targets-changed-fn))))))

(deftest update-targets-temporarily-rejects-new-target-if-device-info-location-is-malformed-url
  (let [address (str (make-device-url iphone-hostname iphone-id))
        malformed-headers {"LOCATION" "malformed-url"
                           "SERVER" SSDP/SSDP_SERVER_IDENTIFIER}
        malformed-device-info (DeviceInfo/create malformed-headers address iphone-hostname)
        {:keys [targets-atom
                descriptions-atom
                blacklist-atom] :as context} (make-context)]
    (targets/update-targets! context [(make-local-device-info) malformed-device-info])
    (testing "Target is rejected"
      (is (= [local-hostname] (targets-hostnames @targets-atom))))
    (testing "Descriptions cache is unaltered"
      (is (= [local-hostname] (descriptions-hostnames @descriptions-atom))))
    (testing "Blacklist is unaltered"
      (is (= [] (blacklist-hostnames @blacklist-atom))))))

(deftest update-targets-permanently-rejects-new-target-if-fetch-url-returns-malformed-xml
  (let [{:keys [targets-atom
                descriptions-atom
                blacklist-atom] :as context} (assoc-in (make-context) [:fetch-url-fn iphone-url] "<malformed-xml></")]
    (targets/update-targets! context [(make-local-device-info) (make-iphone-device-info)])
    (testing "Target is rejected"
      (is (= [local-hostname] (targets-hostnames @targets-atom))))
    (testing "Descriptions cache is unaltered"
      (is (= [local-hostname] (descriptions-hostnames @descriptions-atom))))
    (testing "Host is blacklisted"
      (is (= [iphone-hostname] (blacklist-hostnames @blacklist-atom))))))

(deftest update-targets-permanently-rejects-new-target-if-fetch-url-returns-xml-missing-required-data
  (doseq [device-tag required-device-desc-device-tags]
    (let [invalid-device-desc-template (device-desc-template-without device-tag)
          invalid-device-desc (make-device-desc invalid-device-desc-template "iPhone" "ios" iphone-hostname)
          {:keys [targets-atom
                  descriptions-atom
                  blacklist-atom] :as context} (assoc-in (make-context) [:fetch-url-fn iphone-url] invalid-device-desc)]
      (targets/update-targets! context [(make-local-device-info) (make-iphone-device-info)])
      (testing "Target is rejected"
        (is (= [local-hostname] (targets-hostnames @targets-atom))))
      (testing "Incomplete description is cached"
        (is (= [iphone-hostname local-hostname] (descriptions-hostnames @descriptions-atom))))
      (testing "Host is blacklisted"
        (is (= [iphone-hostname] (blacklist-hostnames @blacklist-atom)))))))

(deftest update-targets-temporarily-rejects-new-target-if-fetch-url-throws-socket-timeout-exception
  (let [{:keys [targets-atom
                descriptions-atom
                blacklist-atom
                on-targets-changed-fn] :as context} (make-context)]
    (targets/update-targets! context [(make-local-device-info)])
    (is (= [local-hostname] (targets-hostnames @targets-atom)))
    (is (= [local-hostname] (descriptions-hostnames @descriptions-atom)))
    (is (= 1 (call-count on-targets-changed-fn)))
    (let [throwing-context (assoc context :fetch-url-fn (fn [_] (throw (SocketTimeoutException.))))]
      (targets/update-targets! throwing-context [(make-local-device-info) (make-iphone-device-info)])
      (testing "Target is rejected"
        (is (= [local-hostname] (targets-hostnames @targets-atom))))
      (testing "Descriptions cache is unaltered"
        (is (= [local-hostname] (descriptions-hostnames @descriptions-atom))))
      (testing "Blacklist is unaltered"
        (is (= [] (blacklist-hostnames @blacklist-atom))))
      (testing "Menus are not invalidated"
        (is (= 1 (call-count on-targets-changed-fn)))))))

(deftest update-targets-permanently-rejects-new-target-if-fetch-url-throws-io-exception
  (let [{:keys [targets-atom
                descriptions-atom
                blacklist-atom
                on-targets-changed-fn] :as context} (make-context)]
    (targets/update-targets! context [(make-local-device-info)])
    (is (= [local-hostname] (targets-hostnames @targets-atom)))
    (is (= [local-hostname] (descriptions-hostnames @descriptions-atom)))
    (is (= 1 (call-count on-targets-changed-fn)))
    (let [throwing-context (assoc context :fetch-url-fn (fn [_] (throw (IOException.))))]
      (targets/update-targets! throwing-context [(make-local-device-info) (make-iphone-device-info)])
      (testing "Target is rejected"
        (is (= [local-hostname] (targets-hostnames @targets-atom))))
      (testing "Descriptions cache is unaltered"
        (is (= [local-hostname] (descriptions-hostnames @descriptions-atom))))
      (testing "Host is blacklisted"
        (is (= [iphone-hostname] (blacklist-hostnames @blacklist-atom))))
      (testing "Menus are not invalidated"
        (is (= 1 (call-count on-targets-changed-fn)))))))

(deftest update-targets-throws-if-fetch-url-returns-nil
  (let [context (assoc (make-context) :fetch-url-fn (fn [_] nil))]
    (is (thrown? NullPointerException (targets/update-targets! context [(make-local-device-info)])))))

(deftest update-targets-throws-if-fetch-url-throws-non-io-exception
  (let [devices [(make-local-device-info)]
        make-throwing-context (fn [exception]
                                (assoc (make-context) :fetch-url-fn (fn [_] (throw exception))))]
    (is (thrown? Exception (targets/update-targets! (make-throwing-context (Exception.)) devices)))
    (is (thrown? NullPointerException (targets/update-targets! (make-throwing-context (NullPointerException.)) devices)))
    (is (thrown? IllegalArgumentException (targets/update-targets! (make-throwing-context (IllegalArgumentException.)) devices)))))