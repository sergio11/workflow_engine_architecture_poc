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

(deftest publish-no-subscribers-test
  (testing "publishing to unsubscribed type does not error"
    (pub/publish! {:type :nonexistent-event})
    (is true "no error on publish with no subscribers")))

(deftest subscribe-all-immediate-unsub-test
  (testing "subscribe-all and immediately unsubscribe without publishing"
    (let [received (atom [])
          unsub (pub/subscribe-all! (fn [e] (swap! received conj e)))]
      (unsub)
      (is (= 0 (count @received))))))

(deftest publish-single-subscriber-test
  (testing "publish to single subscriber with specific event type"
    (let [received (atom nil)]
      (pub/subscribe! :workflow-cancelled (fn [e] (reset! received e)))
      (pub/publish! {:type :workflow-cancelled :data "test"})
      (is (= :workflow-cancelled (:type @received)))
      (is (= "test" (:data @received))))))

(deftest multiple-publishes-test
  (testing "multiple publishes to same type"
    (let [count-atom (atom 0)]
      (pub/subscribe! :step-started (fn [e] (swap! count-atom inc)))
      (pub/publish! {:type :step-started :n 1})
      (pub/publish! {:type :step-started :n 2})
      (pub/publish! {:type :step-started :n 3})
      (is (= 3 @count-atom)))))

(deftest publish-different-types-test
  (testing "publish different event types to different subscribers"
    (let [wf-events (atom [])
          step-events (atom [])]
      (pub/subscribe! :workflow-started (fn [e] (swap! wf-events conj e)))
      (pub/subscribe! :step-completed (fn [e] (swap! step-events conj e)))
      (pub/publish! {:type :workflow-started})
      (pub/publish! {:type :step-completed})
      (pub/publish! {:type :workflow-started})
      (is (= 2 (count @wf-events)))
      (is (= 1 (count @step-events))))))

(deftest subscriber-count-arity-test
  (testing "subscriber-count with 0 and 1 arity"
    (pub/subscribe! :step-started (fn [_]))
    (pub/subscribe! :step-completed (fn [_]))
    (is (= 2 (pub/subscriber-count)))
    (is (= 1 (pub/subscriber-count :step-started)))
    (is (= 1 (pub/subscriber-count :step-completed)))
    (is (= 0 (pub/subscriber-count :workflow-cancelled)))))
