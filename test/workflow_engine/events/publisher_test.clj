(ns workflow-engine.events.publisher-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [workflow-engine.events.publisher :as pub]))

(defn cleanup-fixture [f]
  (pub/clear-subscribers!)
  (try
    (f)
    (finally
      (pub/clear-subscribers!))))

(use-fixtures :each cleanup-fixture)

(deftest subscribe-and-publish-test
  (testing "subscriber receives published events"
    (let [received (atom nil)]
      (pub/subscribe! :step-started (fn [e] (reset! received e)))
      (pub/publish! {:type :step-started :step :step-a})
      (is (= :step-started (:type @received)))
      (is (= :step-a (:step @received))))))

(deftest unsubscribe-test
  (testing "unsubscribing stops events"
    (let [received (atom nil)]
      (let [unsub (pub/subscribe! :step-started (fn [e] (reset! received e)))]
        (unsub)
        (pub/publish! {:type :step-started :step :step-a})
        (is (nil? @received))))))

(deftest multiple-subscribers-test
  (testing "multiple subscribers all receive events"
    (let [r1 (atom nil) r2 (atom nil)]
      (pub/subscribe! :step-started (fn [e] (reset! r1 e)))
      (pub/subscribe! :step-started (fn [e] (reset! r2 e)))
      (pub/publish! {:type :step-started :step :step-a})
      (is (some? @r1))
      (is (some? @r2)))))

(deftest subscriber-count-test
  (testing "counts subscribers"
    (pub/subscribe! :step-started (fn [_]))
    (pub/subscribe! :step-started (fn [_]))
    (pub/subscribe! :step-completed (fn [_]))
    (is (= 2 (pub/subscriber-count :step-started)))
    (is (= 1 (pub/subscriber-count :step-completed)))
    (is (= 3 (pub/subscriber-count)))))

(deftest subscribe-all-test
  (testing "subscribe-all receives all event types"
    (let [received (atom [])]
      (let [unsub (pub/subscribe-all! (fn [e] (swap! received conj e)))]
        (pub/publish! {:type :workflow-started})
        (pub/publish! {:type :step-started})
        (pub/publish! {:type :step-completed})
        (is (= 3 (count @received)))
        (unsub)))))

(deftest subscriber-exception-test
  (testing "other subscribers still receive when one throws"
    (let [r1 (atom nil)
          r2 (atom nil)]
      (pub/subscribe! :step-started (fn [e] (throw (Exception. "boom"))))
      (pub/subscribe! :step-started (fn [e] (reset! r1 e)))
      (pub/subscribe! :step-started (fn [e] (reset! r2 e)))
      (pub/publish! {:type :step-started :step :s1})
      (is (some? @r1))
      (is (some? @r2)))))

(deftest subscribe-all-remaining-events-test
  (testing "subscribe-all covers all 7 event types"
    (let [received (atom [])]
      (let [unsub (pub/subscribe-all! (fn [e] (swap! received conj e)))]
        (pub/publish! {:type :step-failed})
        (pub/publish! {:type :workflow-completed})
        (pub/publish! {:type :workflow-failed})
        (pub/publish! {:type :workflow-cancelled})
        (is (= 4 (count @received)))
        (unsub)))))

(deftest subscriber-count-empty-type-test
  (testing "returns 0 for unsubscribed type"
    (is (= 0 (pub/subscriber-count :nonexistent)))))

(deftest clear-subscribers-test
  (testing "clearing removes all subscribers"
    (pub/subscribe! :step-started (fn [_]))
    (pub/subscribe! :step-completed (fn [_]))
    (is (pos? (pub/subscriber-count)))
    (pub/clear-subscribers!)
    (is (= 0 (pub/subscriber-count)))))
