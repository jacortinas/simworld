(ns sim.rng
  "Pure, seedable, splittable PRNG (SplitMix64 — the algorithm behind Java's
   SplittableRandom). The sim's SINGLE source of deterministic sim-time
   randomness, so same-seed runs stay bit-identical regardless of thread or
   iteration order.

   State is a plain `^long` (the seed IS the state). The stateful fns return
   `[value state']` so a PURE tick can thread the advanced state forward:

     (let [[cell s'] (rng/pick state cells)] ...)

   Determinism by construction, NOT by threading one mutable RNG: sim-time
   consumers `derive-seed` a per-(tick, entity) stream from the world's
   `:rng-seed`, so an entity's randomness is a pure function of its OWN
   coordinates — independent of how many values other entities drew and of the
   order entities are visited. That is the same discipline the `:kinds`
   sorted-set gave reservations, and it makes the RNG parallel-ready: each
   worker derives its own stream with no shared mutable state.

   SplitMix64 is chosen over a reseeded java.util.Random because it is pure-
   functional (state in, state out), splittable by construction, and free of
   adjacent-seed correlation."
  (:refer-clojure :exclude [shuffle]))

(set! *warn-on-reflection* true)

;; SplitMix64 constants. These exceed Long/MAX_VALUE, so the reader auto-promotes
;; the hex literals to BigInt; `unchecked-long` truncates each to its low 64 bits
;; as the signed (negative) long the unchecked 64-bit arithmetic below expects.
(def ^:private ^:const golden (unchecked-long 0x9e3779b97f4a7c15))
(def ^:private ^:const mix-a  (unchecked-long 0xbf58476d1ce4e5b9))
(def ^:private ^:const mix-b  (unchecked-long 0x94d049bb133111eb))

;; 2^-53 — scales a 53-bit mantissa into [0,1). 9007199254740992.0 is 2^53, and
;; 1.0/2^53 is exactly representable. (NOT ^double on a def: `double` there
;; collides with clojure.core/double.)
(def ^:private double-unit (/ 1.0 9007199254740992.0))

(defn- mix64
  "The SplitMix64 finalizer: avalanche a state word into a well-distributed
   output. A strong bijective-style hash of one 64-bit word."
  ^long [^long z0]
  (let [z1 (unchecked-multiply (bit-xor z0 (unsigned-bit-shift-right z0 30)) mix-a)
        z2 (unchecked-multiply (bit-xor z1 (unsigned-bit-shift-right z1 27)) mix-b)]
    (bit-xor z2 (unsigned-bit-shift-right z2 31))))

(defn next-long
  "Advance the stream one step. Returns [^long value ^long state']. The value is
   the finalizer applied to the advanced state; thread state' into the next draw."
  [^long state]
  (let [s' (unchecked-add state golden)]
    [(mix64 s') s']))

(defn next-int
  "A value in [0, n) plus the advanced state. n must be positive. Uses floorMod
   so a negative finalizer word still maps into range; bias is irrelevant here
   (we need reproducibility, not uniformity to the last ULP)."
  [^long state ^long n]
  (let [s' (unchecked-add state golden)]
    [(Math/floorMod (mix64 s') n) s']))

(defn next-double
  "A double in [0.0, 1.0) plus the advanced state. Top 53 bits scaled by 2^-53."
  [^long state]
  (let [s' (unchecked-add state golden)
        v  (mix64 s')]
    [(* (unsigned-bit-shift-right v 11) double-unit) s']))

(defn pick
  "Pick one element of `coll` (vectorized) plus the advanced state. Empty coll ->
   [nil state] (state passes through untouched — nothing was drawn)."
  [^long state coll]
  (let [v (vec coll)
        n (count v)]
    (if (zero? n)
      [nil state]
      (let [[i s'] (next-int state n)]
        [(nth v i) s']))))

(defn shuffle
  "Fisher-Yates (Durstenfeld) shuffle of `coll` plus the advanced state. Returns
   [shuffled-vector state']. Deterministic for a given seed."
  [^long state coll]
  (let [arr (object-array coll)]
    (loop [i (dec (alength arr))
           s state]
      (if (pos? i)
        (let [[j s'] (next-int s (inc i))
              tmp    (aget arr i)]
          (aset arr i (aget arr j))
          (aset arr j tmp)
          (recur (dec i) (long s')))
        [(vec arr) s]))))

(defn derive-seed
  "Mix a base seed with coordinates (e.g. tick then entity-id) into an
   independent stream seed. ORDER-SENSITIVE: (derive-seed s a b) differs from
   (derive-seed s b a). This is how a sim-time consumer gets a per-(tick,entity)
   stream that does not depend on iteration order or on other entities' draws."
  [seed & coords]
  (reduce (fn [acc c]
            (mix64 (unchecked-add (long acc)
                                  (unchecked-multiply (unchecked-add (long c) 1) golden))))
          (mix64 (long seed))
          coords))
