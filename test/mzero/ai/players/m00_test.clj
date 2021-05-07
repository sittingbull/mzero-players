(ns mzero.ai.players.m00-test
  (:require [clojure.tools.logging :as log]
            [clojure.test :refer [is testing]]
            [mzero.utils.testing :refer [deftest]]
            [mzero.utils.utils :as u]
            [mzero.game.events :as ge]
            [mzero.ai.player :as aip]
            [mzero.ai.world :as aiw :refer [world]]
            [mzero.ai.main :as aim]
            [mzero.ai.players.m00 :as sut]
            [mzero.ai.players.network :as mzn]
            [uncomplicate.neanderthal.random :as rnd]
            [uncomplicate.neanderthal.native :as nn]
            [uncomplicate.neanderthal.vect-math :as nvm]
            [uncomplicate.neanderthal.core :as nc]
            [clojure.data.generators :as g]))

(def seed 44)
(deftest sparsify-test
  (let [input-dim 1024 nb-cols 1000
        w (#'sut/sparse-weights input-dim nb-cols (java.util.Random. seed))
        nb-nonzero-weights (nc/sum (nvm/ceil (nvm/abs w)))
        nb-neg-weights (- (nc/sum (nvm/floor w)))
        neg-ratio (/ nb-neg-weights nb-nonzero-weights)
        total-ratio (/ nb-nonzero-weights (nc/dim w))
        expected-total-ratio (/ (sut/nonzero-weights-nb input-dim 0.5) input-dim)]
    (is (u/almost= neg-ratio sut/neg-weight-ratio (* 0.05 neg-ratio)))
    (is (every? #(u/almost= 1.0 (nc/sum %)) (nc/cols w)))
    (is (u/almost= total-ratio expected-total-ratio (* 0.05 total-ratio)))))

(defn run-n-steps
  [player size world acc]
  (if (> size 0)
    (let [next-player
          (aip/update-player player world)
          next-movement
          (-> next-player :next-movement)
          next-world
          (aiw/compute-new-state
           (assoc world ::aiw/requested-movements
                  (if next-movement {:player next-movement} {})))]
      
      (recur next-player (dec size) next-world (conj acc next-movement)))
    acc))

(deftest m00-instrumented-test
  ;; WARNING : seeded random not working here (but test seems valid
  ;; apart from that)
  ;;
  ;; this is because instrumentation checks calls to new-layers, 
  ;; taking as arg unpure random functions. The check calls those
  ;; function, messing with the state. 
  (testing "Runs intrumented version of m00 to check fn calls, and
  checks than in 50 steps there are more than 3 moves (minimum moves
  via rand-move-reflexes)"
    (let [test-world (world 25 seed)
          m00-opts {:seed seed :layer-dims [50 50 150]}
          m00-player
          (aip/load-player "m00" m00-opts test-world)
          dl-updates
          (u/timed (run-n-steps m00-player 50 test-world []))]
      (is (<= 3 (count (remove nil? (second dl-updates))))))))

(deftest ^:integration m00-run
  :unstrumented
  (testing "Speed should be above ~2.5 GFlops, equivalently 25 iters per sec"
    (let [test-world (world 30 seed)
          expected-gflops 1.5 ;; more than 1.5 Gflops => average probably around 2.5
          expected-iters-sec 20
          ;; layer constant ~= nb of ops for each matrix elt for a layer
          ;; layer nb is inc'd to take into account 
          layer-nb 8 dim 1024 layer-constant 10 steps 250
          forward-pass-ops (* dim dim (inc layer-nb) layer-constant steps)
          m00-opts {:seed seed :layer-dims (repeat layer-nb dim)}
          game-opts
          (aim/parse-run-args "-v WARNING -n %d -t m00 -o'%s'" steps m00-opts)
          time-ms
          (first (u/timed (aim/run game-opts test-world)))
          actual-gflops
          (/ forward-pass-ops time-ms 1000000)
          iterations-per-sec
          (/ steps time-ms 0.001)]
      (log/info actual-gflops " GFlops, " iterations-per-sec " iters/sec")
      (is (< expected-gflops actual-gflops))
      (is (< expected-iters-sec iterations-per-sec)))))


