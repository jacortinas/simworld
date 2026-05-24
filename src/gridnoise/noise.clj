(ns gridnoise.noise
  "Seeded value-noise primitives — game-agnostic. A `field` is a pure
   `(fn [x y] -> double in [0,1])` sampling fractal (fbm) value noise.

   Value noise = a hash at integer lattice points, smoothstep-interpolated
   between them. Keying the hash by coordinate+seed (instead of a sequential
   java.util.Random stream) makes sampling order-independent and trivially
   deterministic.")

(set! *warn-on-reflection* true)

(defn- smoothstep ^double [^double t]
  (* t t (- 3.0 (* 2.0 t))))

(defn- lerp ^double [^double a ^double b ^double t]
  (+ a (* t (- b a))))

(defn- hash01
  "Deterministic pseudo-random double in [0,1] for an integer lattice point.
   Integer hash (xorshift-ish) folded to a non-negative double."
  ^double [^long x ^long y ^long seed]
  (let [h (unchecked-long (+ (unchecked-multiply x 374761393)
                             (unchecked-multiply y 668265263)
                             (unchecked-multiply seed 1442695040888963407)))
        h (unchecked-long (bit-xor h (unsigned-bit-shift-right h 13)))
        h (unchecked-long (unchecked-multiply h 1274126177))
        h (bit-and h 0x7fffffff)]
    (/ (double h) 2147483647.0)))

(defn value-noise
  "Single-octave value noise at (x,y). Returns a double in [0,1]."
  ^double [^double x ^double y ^long seed]
  (let [x0  (long (Math/floor x))
        y0  (long (Math/floor y))
        x1  (inc x0)
        y1  (inc y0)
        u   (smoothstep (- x (double x0)))
        v   (smoothstep (- y (double y0)))
        n00 (hash01 x0 y0 seed)
        n10 (hash01 x1 y0 seed)
        n01 (hash01 x0 y1 seed)
        n11 (hash01 x1 y1 seed)
        nx0 (lerp n00 n10 u)
        nx1 (lerp n01 n11 u)]
    (lerp nx0 nx1 v)))

(defn field
  "Return a pure (fn [x y] -> double in [0,1]) sampling fractal value noise.
   opts: :seed :freq :octaves :persistence (all have sensible defaults).
   Each octave doubles frequency, scales amplitude by persistence, and uses
   seed+octave so layers don't align. Normalized by the amplitude sum."
  [{:keys [seed freq octaves persistence]
    :or   {seed 0 freq 0.08 octaves 4 persistence 0.5}}]
  (let [seed        (long seed)
        base-freq   (double freq)
        octaves     (long octaves)
        persistence (double persistence)]
    (fn [x y]
      (let [x (double x) y (double y)]
        (loop [o 0, f base-freq, amp 1.0, sum 0.0, norm 0.0]
          (if (= o octaves)
            (/ sum norm)
            (recur (inc o)
                   (* f 2.0)
                   (* amp persistence)
                   (+ sum (* amp (value-noise (* x f) (* y f) (+ seed o))))
                   (+ norm amp))))))))
