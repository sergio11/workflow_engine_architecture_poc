(ns demo.formatting)

(def reset "\u001b[0m")
(def bold "\u001b[1m")
(def dim "\u001b[2m")
(def italic "\u001b[3m")
(def underline "\u001b[4m")

(def black "\u001b[30m")
(def red "\u001b[31m")
(def green "\u001b[32m")
(def yellow "\u001b[33m")
(def blue "\u001b[34m")
(def magenta "\u001b[35m")
(def cyan "\u001b[36m")
(def white "\u001b[37m")

(def bg-green "\u001b[42m")
(def bg-red "\u001b[41m")
(def bg-yellow "\u001b[43m")
(def bg-blue "\u001b[44m")
(def bg-magenta "\u001b[45m")
(def bg-cyan "\u001b[46m")

(defn colorize [color text]
  (str color text reset))

(defn green-text [text] (colorize green text))
(defn red-text [text] (colorize red text))
(defn yellow-text [text] (colorize yellow text))
(defn cyan-text [text] (colorize cyan text))
(defn blue-text [text] (colorize blue text))
(defn magenta-text [text] (colorize magenta text))
(defn bold-text [text] (colorize bold text))
(defn dim-text [text] (colorize dim text))

(defn status-badge [status]
  (let [label (name status)
        padded (str " " label " ")]
    (case status
      :completed (str bg-green bold white padded reset)
      :failed    (str bg-red bold white padded reset)
      :running   (str bg-yellow bold black padded reset)
      :pending   (str bg-cyan bold black padded reset)
      :waiting   (str bg-magenta bold white padded reset)
      :cancelled (str dim white padded reset)
      (str dim padded reset))))

(defn format-duration [ms]
  (cond
    (< ms 1000) (str ms "ms")
    (< ms 60000) (format "%.2fs" (/ ms 1000.0))
    :else (format "%dm %ds" (quot ms 60000) (mod (quot ms 1000) 60))))

(defn format-instant [inst]
  (when inst
    (.format (java.time.format.DateTimeFormatter/ofPattern "HH:mm:ss.SSS")
             (if (string? inst)
               (java.time.Instant/parse inst)
               inst))))

(defn print-separator []
  (println (dim-text (str (apply str (repeat 60 "\u2500"))))))

(defn print-double-separator []
  (println (dim-text (str (apply str (repeat 60 "\u2550"))))))

(defn print-header [title]
  (println)
  (print-double-separator)
  (println (str "  " bold-text title))
  (print-double-separator)
  (println))

(defn print-scenario [number title description]
  (println)
  (println (str "  " cyan-text (str number ". ") bold-text title reset))
  (println (str "     " dim-text description reset))
  (println))

(defn pad-right [s width]
  (let [visible-len (count (clojure.string/replace s #"\u001b\[[0-9;]*m" ""))
        padding (max 0 (- width visible-len))]
    (str s (apply str (repeat padding " ")))))

(defn pad-left [s width]
  (let [visible-len (count (clojure.string/replace s #"\u001b\[[0-9;]*m" ""))
        padding (max 0 (- width visible-len))]
    (str (apply str (repeat padding " ")) s)))

(defn print-table [headers rows]
  (let [col-count (count headers)
        col-widths (vec
                     (for [i (range col-count)]
                       (max
                         (count (nth headers i))
                         (apply max (map #(count (str (nth % i ""))) rows)))))
        divider (str "+"
                      (clojure.string/join "+"
                        (map #(apply str (repeat % "-")) col-widths))
                      "+")]
    (println divider)
    (println (str "|"
                  (clojure.string/join "|"
                    (map-indexed
                      (fn [i h]
                        (pad-right (bold-text h) (nth col-widths i)))
                      headers))
                  "|"))
    (println divider)
    (doseq [row rows]
      (println (str "|"
                    (clojure.string/join "|"
                      (map-indexed
                        (fn [i cell]
                          (pad-right (str cell) (nth col-widths i)))
                        row))
                    "|")))
    (println divider)))

(defn print-metrics-row [label value & [color-fn]]
  (let [formatted (if color-fn (color-fn (str value)) (str value))]
    (println (str "  " (pad-right (dim-text label) 25) bold-text formatted reset))))

(defn print-success [msg]
  (println (str "  " green-text "\u2714 " reset msg)))

(defn print-error [msg]
  (println (str "  " red-text "\u2718 " reset msg)))

(defn print-info [msg]
  (println (str "  " cyan-text "\u2139 " reset msg)))

(defn print-warning [msg]
  (println (str "  " yellow-text "\u26a0 " reset msg)))

(defn print-step [step-num total title]
  (println (str (bold-text (str "  [" step-num "/" total "] ")) title)))

(defn print-code-block [code]
  (println (str "  " dim-text "```clojure" reset))
  (doseq [line (clojure.string/split-lines code)]
    (println (str "  " cyan-text line reset)))
  (println (str "  " dim-text "```" reset)))

(defn print-key-value [key val]
  (println (str "  " (pad-right (dim-text (str key ":")) 20) (str val))))

(defn spinner-frame [frame]
  (let [frames ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"]]
    (nth frames (mod frame (count frames)))))

(defn clear-line []
  (print "\r\u001b[K")
  (flush))

(defn print-box [lines]
  (let [max-len (apply max (map count lines))
        border (apply str (repeat (+ max-len 4) "\u2500"))]
    (println (str "  \u256c" border "\u2563"))
    (doseq [line lines]
      (println (str "  \u2502 " (pad-right line max-len) " \u2502")))
    (println (str "  \u2570" border "\u256f"))))

(defn print-clojure-advantage [title description]
  (println)
  (println (str "  " magenta-text "\u2605 " bold-text title reset))
  (println (str "    " dim-text description reset))
  (println))
