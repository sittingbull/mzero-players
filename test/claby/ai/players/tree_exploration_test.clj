(ns claby.ai.players.tree-exploration-test
  (:require [claby.ai.players.tree-exploration :as sut]
            [clojure.test :refer [is testing]]
            [claby.utils.testing :refer [deftest count-calls]]
            [claby.ai.world :as aiw]
            [claby.game.state-test :as gst]
            [claby.game.events :as ge]
            [claby.game.state :as gs]
            [claby.ai.player :as aip]
            [claby.ai.main :as aim]
            [claby.game.board :as gb]
            [clojure.test.check.generators :as gen]
            [clojure.zip :as zip]
            [claby.game.generation :as gg]
            [claby.utils.utils :as u]))


(def world-state (-> gst/test-state-2
                     (assoc ::gs/player-position [2 0])
                     (assoc-in [::gb/game-board 4 2] :fruit)
                     aiw/get-initial-world-state))
(def initial-player
  (aip/init-player (sut/map->TreeExplorationPlayer {}) {:nb-sims 100} nil))

(deftest sum-children-frequencies-test
  (is (= 6 (sut/sum-children-frequencies {::sut/children
                                          {:up {::sut/frequency 2}
                                           :down {::sut/frequency 4}}}))))

(deftest update-children-test
  (let [test-node (reduce
                   #(sut/append-child %1 {::sut/frequency 1 ::ge/direction %2})
                   {::sut/frequency 3}
                   '(:up :down :right))
        updated-node (#'sut/update-children test-node)]
    (is (contains? (::sut/children updated-node) :right))
    (is (= (#'sut/update-children updated-node) updated-node))))

(deftest move-to-min-child-test
  (let [test-zipper
        (zip/vector-zip [{:a 8} [{:a 1} {:a 2} {:a 0} {:a 3} {:a 1}]])]
    (is (= {:a 0} (-> test-zipper
                      zip/down zip/right
                      (#'sut/move-to-min-child :a)
                      zip/node)))))

(deftest impl-equivalence-test
  :unstrumented
  (testing "The 2 impls given via 'root-node' and 'root-zipper' should
  be equivalent. We assume they are if players end up at the same
  place after 10 steps on a board, tested on 10 different boards"
    (let [game-args
          (fn [impl]
            (str "-v WARNING -n 10 -t tree-exploration "
                    (format "-o '{:node-constructor %s}'" impl)))
          ending-player-position
          (fn [impl world]
            (-> (aim/run (aim/parse-run-args (game-args impl)) world)
                :world ::gs/game-state ::gs/player-position))
          random-worlds
          (map aiw/get-initial-world-state
               (gg/generate-game-states 10 25 43))]
      (is (= (map (partial ending-player-position "root-node") random-worlds)
             (map (partial ending-player-position "root-zipper") random-worlds))))))

(deftest tree-exploration-player-test  
  (let [tree-root (-> initial-player
                      (aip/update-player world-state)
                      :root-node
                      sut/node)]
    (is (= (sut/sum-children-frequencies tree-root)
           100))
    (is (= ge/directions (set (keys (::sut/children tree-root)))))
    (is (every? #(= (::sut/frequency %) 25) (sut/children tree-root)))
    (is (= (-> tree-root ::sut/children :up ::sut/children :right ::sut/value) 0))
    (is (= (-> tree-root ::sut/children :up ::sut/value) 1))))

(deftest te-exploration-simulation-test
  (testing "Player should go eat the close fruit (up then right), then reset tree
    exploration from scratch--meaning after a simulation run the
    frequency of the root will be exactly the number of simlations"
    (let [{:keys [world player]}
          (aim/run (aim/parse-run-args "-t tree-exploration -n 2")
            world-state initial-player)

          root-node-after-sim
          (-> player (aip/update-player world) :root-node)]
      (is (= (-> world ::gs/game-state ::gs/player-position) [1 1]))
      (is (= (sut/sum-children-frequencies root-node-after-sim)
             (:nb-sims player))))))

(deftest ^:integration te-blocking-bug
  :unstrumented
  (testing "After a while, the player should not stop moving. Frequent
  bug, reproduced on the below sample board starting at step 62 -- the
  player stops moving"
    (let [bugged-world-example
          (aiw/get-initial-world-state
           (first (gg/generate-game-states 1 20 2 true)))
          world-and-player-states
          (aim/run
            (aim/parse-run-args "-t tree-exploration -n 62 -v WARNING")
            bugged-world-example)])))

(deftest ^:integration te-stability-test
  :unstrumented ;; speed test would be hindered by instrumentation
  (testing "2 tests in 1 : stackoverflow bug and speed

    1/ ERROR: recursivity in tree-simulate should not throw
    stackoverflow even big boards

    2/ FAILURE: it should perform about 20Kops/secs, on big boards.
    An op is ~ a call to tree-simulate. Amounts to about "
    (with-redefs [sut/tree-simulate (count-calls sut/tree-simulate)]
      (let [expected-sims-per-sec 1000
            board-size 50, nb-steps 10, sims-per-step 500
            time-to-run-ms
            (* nb-steps sims-per-step (/ 1000 expected-sims-per-sec))
            initial-world ;; seeded generator, always same board
            (aiw/get-initial-world-state
             (first (gg/generate-game-states 1 board-size 9)))
            game-run
            (future
              (u/timed
               (aim/run
                 (aim/parse-run-args "-t tree-exploration -n %d -o '{:nb-sims %d}'"
                                     nb-steps sims-per-step)
                 initial-world)))
            game-result
            (deref game-run time-to-run-ms nil)]
        
        (is (not (nil? game-result)) (str "time > than " time-to-run-ms))
        (let [nb-ops ((:call-count (meta sut/tree-simulate)))
              time-in-s (/ (first game-result) 1000)]
          (is (> (/ nb-ops time-in-s) 200000)
              (str "Nb of steps " nb-ops " in time " time-in-s))))))) 

#_(deftest ^:integration tree-exploration-player-run
  (testing "A game with tree-exploration be won in < 300 steps
  on a small 10*10 board"
    (let [game-result
          (future (aim/run
                    (aim/parse-run-args
                     "-t tree-exploration -s 10 -i -n 100 -o {:nb-sims 100}")))
          counter (atom 0)]
      ;; simulate interactivity commands to let the game run for 300 steps
      ;; continue 3 times then quit
      (with-redefs [clojure.core/read-line
                    (fn []
                      (Thread/sleep 1000)
                      (swap! counter inc)
                      (nth (cycle '("" "" "" "q")) @counter))]
        (is (= (-> @game-result ::gs/game-state ::gs/status) :won))))))
               
