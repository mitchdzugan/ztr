(ns ztr.core
  (:require [clojure.core.async :as a :refer [<! >!]]
            [clojure.string :as str])
  (:import (org.lwjgl.glfw Callbacks GLFW GLFWErrorCallback)
           (org.lwjgl.opengl GL GL11)
           (org.lwjgl.system MemoryUtil)
           (io.github.humbleui.skija.paragraph FontCollection
                                               Paragraph
                                               ParagraphBuilder
                                               ParagraphStyle
                                               TextStyle
                                               TypefaceFontProvider)
           (io.github.humbleui.skija BackendRenderTarget
                                     Canvas
                                     ColorSpace
                                     DirectContext
                                     FramebufferFormat
                                     Font
                                     FontMgr
                                     FontStyle
                                     Paint
                                     PaintMode
                                     Surface
                                     SurfaceColorFormat
                                     SurfaceOrigin
                                     Typeface)
           (io.github.humbleui.types Rect))
  (:gen-class))

(defn Done [] {:base :Cmd :var :Done})
(defn Render [el] {:base :Cmd :var :Render :el el})
(defn Leaf
  [fill-base text]
  {:base :Element :var :Leaf :text text :fill-base fill-base})
(defn Node
  [props children]
  {:base :Element :var :Node :props props :children children})
(defn Styled
  [styles children]
  {:base :Token :var :Styled :styles styles :children children})
(defn Terminal [text] {:base :Token :var :Terminal :text text})

(defn fill-front-bias? [fill] (get {:end true :start false} fill nil))

(def ^:dynamic *env* {})

;!zprint {:fn-map {"in-updated-env" :arg1}}
(defmacro in-updated-env [f & body] `(binding [*env* (~f *env*)] ~@body))

;!zprint {:fn-map {"in-merged-env" :arg1}}
(defmacro in-merged-env [m & body] `(binding [*env* (merge *env* ~m)] ~@body))

;!zprint {:fn-map {"in-env->" :arg1}}
(defmacro in-env->
  [fbody & body]
  (let [[fbhead & fbrest] (if (list? fbody) fbody (list fbody))]
    `(in-updated-env (fn [x#] (~fbhead x# ~@fbrest)) ~@body)))

(defn ask [f] (f *env*))

(defn -font-collection [] (ask :font-collection))
(defn -cellw [] (ask :cellw))
(defn -cellh [] (ask :cellh))
(defn -padding [] (ask :padding))

(defn vcase [v spec] ((or (get spec (:var v)) (get spec nil)) v))

(defn el-fill
  [el]
  (vcase el
         {:Leaf :fill-base
          :Node #(-> %1
                     :props
                     :fill)}))

(def char-width-overrides
  {\⌫ 2
   \⎇ 2
   \⌃ 2
   \⇧ 2
   \❖ 2})

(defn char-width [c] (get char-width-overrides c 1))

(def str-width #(->> (into [] %1) (map char-width) (reduce + 0)))

(defn token-width
  [token]
  (vcase token
         {:Terminal #(->> (into [] (:text %1))
                          (map char-width)
                          (reduce + 0))
          :Styled #(->> %1
                        :children
                        (map token-width)
                        (reduce + 0))}))

(defn add-min-size
  [el]
  (vcase el
         {:Leaf #(-> %1
                     (assoc :min-height 1)
                     (assoc :min-width (str-width (:text %1))))
          :Node (fn [el]
                  (let [updated (update el :children #(map add-min-size %))
                        children (get updated :children)
                        tag (-> el :props :tag)
                        rows? (= tag :rows)
                        fill? (#{:fill :hr} tag)
                        dmax #(->> %2
                                   (map %1)
                                   (apply max 0))
                        dsum #(->> %2
                                   (map %1)
                                   (reduce + 0))
                        min-height ((if rows? dsum dmax) :min-height children)
                        min-width ((if rows? dmax dsum) :min-width children)]
                    (if fill?
                      (merge updated {:min-width 1 :min-height 1})
                      (-> updated
                          (assoc :min-height min-height)
                          (assoc :min-width min-width)))))}))

(defn zipcat
  [& l2]
  (let [rows (apply max 0 (map count l2))]
    (->> (range rows)
         (map #(apply concat (map (fn [group] (nth group %1 [])) l2))))))

(defn fill-space
  [map-group complete extra fill groups]
  (let [num-spaces (-> groups
                       count
                       inc)
        last-space-ind (dec num-spaces)
        get-space
          #(let [first? (= %1 0)
                 last? (= %1 last-space-ind)
                 front-bias? (fill-front-bias? fill)
                 bookend (cond first? :be-L
                               last? :be-R
                               :else nil)
                 bookend-fill [bookend fill]
                 bookend-data [bookend front-bias? num-spaces]
                 spread (fn [off-tot off-ind]
                          (let [tot (- num-spaces off-tot)
                                ind (- %1 off-ind)]
                            (+ (quot extra tot)
                               (if (< ind (mod extra tot)) 1 0))))]
             (cond (= [:be-L nil 2] bookend-data) (quot extra 2)
                   (= [:be-R nil 2] bookend-data) (- extra (quot extra 2))
                   (= [:be-L :extremes] bookend-fill) (quot extra 2)
                   (= [:be-R :extremes] bookend-fill) (- extra (quot extra 2))
                   (= :extremes fill) 0
                   (= [:be-L true 2] bookend-data) 0
                   (= [:be-R true 2] bookend-data) extra
                   (= [:be-L false 2] bookend-data) extra
                   (= [:be-R false 2] bookend-data) 0
                   (= [:be-L :start] bookend-fill) extra
                   (= :start fill) 0
                   (= [:be-R :end] bookend-fill) extra
                   (= :end fill) 0
                   (= :even fill) (spread 0 0)
                   (= [nil :between] bookend-fill) (spread 2 1)
                   (= :between fill) 0
                   (= [nil :around] bookend-fill) (spread 1 1)
                   (= [:be-L :around] bookend-fill) (quot (spread 1 1) 2)
                   (= [:be-R :around] bookend-fill) (- (spread 1 1)
                                                       (quot (spread 1 1) 2))))
        mapped (map-indexed #(map-group %2 (get-space %1)) groups)]
    (complete mapped (get-space last-space-ind))))

(defn str-pad [l] (str/join (map (fn [_] " ") (range l))))

(defn pad-horz
  [pl pr tokens]
  (concat (if (= 0 pl) [] [(Terminal (str-pad pl))])
          tokens
          (if (= 0 pr) [] [(Terminal (str-pad pr))])))

(declare to-line-tokens)

(defn to-line-tokens-fundemental
  [aw ah el]
  (let [eh (:min-height el)
        ew (:min-width el)
        xh (- ah eh)
        xw (- aw ew)
        rows? (= :rows
                 (-> el
                     :props
                     :tag))
        mkup? (= :mkup
                 (-> el
                     :props
                     :tag))
        ch #(if rows? (:min-height %1) eh)
        cw #(if rows? ew (:min-width %1))
        xl [(Terminal (str-pad ew))]
        xls #(map (fn [_] xl) (range %1))
        mapper #(to-line-tokens (cw %1) (ch %1) %1)
        groups (vcase el
                      {:Leaf #(-> [[[(Terminal (:text %1))]]])
                       :Node #(let [mapped (map mapper (:children %1))]
                                (if mkup? [(apply zipcat mapped)] mapped))})
        fill-horz (partial fill-space
                           #(map (partial pad-horz %2 0) %1)
                           #(map (partial pad-horz 0 %2) (apply zipcat %1))
                           xw)
        fill-vert (partial fill-space
                           #(concat (xls %2) %1)
                           #(concat (apply concat %1) (xls %2))
                           xh)
        fill (el-fill el)
        fills (if rows? [fill-vert fill-horz] [fill-horz fill-vert])
        [f1 f2] (map (fn [f] #(f fill %1)) fills)
        tokenized (f2 [(f1 groups)])
        style (get-in el [:props :style] {})]
    (map #(-> [(Styled style %1)])
      tokenized)))

(defmulti fundemental-el (fn [_ _ {{:keys [tag]} :props}] tag))
(defmethod fundemental-el :default [_ _ el] el)
(defmethod fundemental-el :fill [aw _ el]
  (-> [leaf (-> el :props :with)]
      (loop (if (< (count leaf) aw) (recur (str leaf leaf)) leaf))
      (subs 0 aw)
      ((fn [leaf-str]
         (-> el
             (assoc-in [:props :tag] :mkup)
             (assoc :children [(add-min-size (Leaf :end leaf-str))]))))))
(defmethod fundemental-el :hr [aw ah el]
  (fundemental-el aw ah (update el :props merge {:tag :fill :with "━"})))

(defn to-line-tokens [aw ah el]
  (to-line-tokens-fundemental aw ah (fundemental-el aw ah el)))

(defn to-render-data
  [el]
  (let [rh (:min-height el)
        rw (:min-width el)]
    {:rw rw :rh rh :line-tokens (to-line-tokens rw rh el)}))

(defn color-of-long [^long l] (.intValue (Long/valueOf l)))
(defn color [c]
  (cond
    (vector? c) (let [cnth #(min 255 (max 0 (nth c %1 %2)))
                      r (cnth 0 0)
                      g (cnth 1 0)
                      b (cnth 2 0)
                      a (cnth 3 255)]
                  (color-of-long (+ (* 0x01000000 a)
                                    (* 0x00010000 r)
                                    (* 0x00000100 g)
                                    (* 0x00000001 b))))
    :else (color-of-long c)
    )
  )

(defn mk-font [^Typeface tf ^Float v] (Font. tf v))

(defn set-color [^TextStyle ts ^Long c] (.setColor ts (color c)))

(defn set-foreground [^TextStyle ts ^Long c]
  (.setForeground ts (doto (Paint.) (.setColor (color c)))))

(defn set-background [^TextStyle ts ^Long c]
  (.setBackground ts (doto (Paint.) (.setColor (color c)))))

(defn set-font-style [^TextStyle ts fs]
  (.setFontStyle ts (get {:normal FontStyle/NORMAL
                          :bold FontStyle/BOLD
                          :italic FontStyle/ITALIC
                          :bold-italic FontStyle/BOLD_ITALIC}
                         fs
                         FontStyle/NORMAL)))

(defn push-style [^ParagraphBuilder pb ^TextStyle ts] (.pushStyle pb ts))

(defn pop-style [^ParagraphBuilder pb] (.popStyle pb))

(defn base-text-style
  []
  (let [families (into-array String ["MonaspiceKr NFM"])]
    (doto (TextStyle.)
      (.setFontSize 14.0)
      (.setColor (color 0xFFFFFFFF))
      (.setFontFamilies families))))

(defn fresh-pb
  [fc]
  (let [ts (base-text-style)
        ps (ParagraphStyle.)]
    (doto (ParagraphBuilder. ps fc) (push-style ts))))

(defn -fresh-pb [] (fresh-pb (-font-collection)))

(defn -ts [] (ask :text-style))

(defn add-text [^ParagraphBuilder pb txt] (.addText pb txt))
(defn build [^ParagraphBuilder pb] (.build pb))
(defn layout [^Paragraph p w] (.layout p w))
(defn paint-paragraph [^Paragraph p ^Canvas c x y] (.paint p c x y))
(defn get-longest-line [^Paragraph p] (.getLongestLine p))
(defn get-height [^Paragraph p] (.getHeight p))

(defn -state [] (ask :state))
(defn -p
  [dims]
  (-> @(-state)
      :p-by-dims
      (get dims)))

(defn draw
  [dims ^Canvas canvas]
  (when-let [p (-p dims)]
    (let [[cw ch] dims
          width (+ (* 2 (-padding)) (* cw (-cellw)))
          height (+ (* 2 (-padding)) (* ch (-cellh)))
          border-paint (doto (Paint.)
                         (.setColor (color 0xffc4a7e7))
                         (.setMode PaintMode/STROKE)
                         (.setStrokeWidth 2))
          border-rect (Rect/makeXYWH 2 1 (- (int width) 3) (- (int height) 3))]
      (.drawRect canvas border-rect border-paint)
      (paint-paragraph p
                       canvas
                       (-padding)
                       (+ (-padding) 1)))))

(defn display-scale
  [^Long window]
  (let [x (float-array 1)
        y (float-array 1)]
    (GLFW/glfwGetWindowContentScale window x y)
    [(first x) (first y)]))

(defn set-subpixel [^Font f b] (.setSubpixel f b))
(defn get-metrics [^Font f] (.getMetrics f))

(defn runtime-init
  []
  (.set (GLFWErrorCallback/createPrint System/err))
  (GLFW/glfwInit)
  (GLFW/glfwWindowHint GLFW/GLFW_VISIBLE GLFW/GLFW_FALSE)
  (GLFW/glfwWindowHint GLFW/GLFW_RESIZABLE GLFW/GLFW_FALSE)
  (GLFW/glfwWindowHint GLFW/GLFW_TRANSPARENT_FRAMEBUFFER GLFW/GLFW_TRUE)
  (let [tf (Typeface/makeFromName "monospace" FontStyle/NORMAL)
        font (set-subpixel (mk-font tf 13.0) true)
        fc (FontCollection.)
        fp (TypefaceFontProvider.)]
    (.setDefaultFontManager fc (FontMgr/getDefault))
    (.registerTypeface fp tf)
    (.setAssetFontManager fc fp)
    (let [pb (fresh-pb fc)]
      (add-text pb ".")
      (let [first-c (a/chan)
            p (doto (build pb) (layout 0.0))]
        {:state (atom {:first? true
                       :win-id 0
                       :wait-first (fn [] (a/<!! first-c))
                       :post-handle
                         (fn []
                           (when (:first? @(-state)) (a/go (a/>! first-c true)))
                           (swap! (-state) #(assoc %1 :first? false)))})
         :metrics (get-metrics font)
         :font font
         :padding 13.0
         :font-collection fc
         :cellw (get-longest-line p)
         :cellh (get-height p)}))))

(defn -post-handle [] ((:post-handle @(-state))))
(defn -wait-first [] ((:wait-first @(-state))))
(defn -window [] (:window @(-state)))
(defn -dims [] (:dims @(-state)))
(defn -next-dims [] (:next-dims @(-state)))
(defn -win-id [] (:win-id @(-state)))

(defn -fill-base [] (ask #(get %1 :fill-base :end)))

(defn parse-node-toplevel
  [[tag & args]]
  (loop [props {:tag (condp = tag
                       'Cols :cols
                       'Rows :rows
                       'Fill :fill
                       'Hr :hr
                       :mkup)}
         [arg1 & rest :as all] args]
    (cond (#{:fill-contents :fill :with} arg1)
          (recur (assoc props arg1 (first rest)) (drop 1 rest))
          (#{:bg :fg :font-style} arg1)
          (recur (assoc-in props [:style arg1] (first rest)) (drop 1 rest))
          :else
          [props all])))

(declare parse-el)

(defn parse-node
  [el]
  (let [[caller-props children-edn] (parse-node-toplevel el)
        fill (get caller-props :fill (-fill-base))
        fill-contents (get caller-props :fill-contents fill)
        props (merge caller-props {:fill fill :fill-contents fill-contents})]
    (in-merged-env {:fill-base (:fill-contents props)}
      (Node props (into [] (map parse-el children-edn))))))

(defn parse-el [el] (if (string? el) (Leaf (-fill-base) el) (parse-node el)))

(declare pb-add-tokens)

(defn clone-text-styles
  [^TextStyle src]
  (-> (TextStyle.)
      (.setColor (.getColor src))
      (.setForeground (.getForeground src))
      (.setBackground (.getBackground src))
      (.setDecorationStyle (.getDecorationStyle src))
      (.setFontSize (.getFontSize src))
      (.setFontStyle (.getFontStyle src))
      (.setFontFamilies (.getFontFamilies src))
      (.setTypeface (.getTypeface src))))

(defn pb-add-token
  [pb token]
  (case (:var token)
    :Terminal (add-text pb (:text token))
    :Styled (let [ts (loop [in-styles (into [] (:styles token))
                            wip (clone-text-styles (-ts))]
                       (if-let [[[k v] & rest]
                                (if (empty? in-styles) nil in-styles)]
                         (recur rest (case k
                                       :font-style (set-font-style wip v)
                                       :bg (set-background wip v)
                                       :fg (set-foreground wip v)))
                         wip))]
              (in-merged-env {:text-style ts}
                             (push-style pb ts)
                             (pb-add-tokens pb (:children token))
                             (pop-style pb)))))

(defn pb-add-tokens [pb tokens] (doseq [t tokens] (pb-add-token pb t)))

(defn to-paragraph
  [{:keys [rw line-tokens]}]
  (in-merged-env {:text-style (base-text-style)}
    (let [pb (-fresh-pb)]
      (doseq [tokens line-tokens]
        (pb-add-tokens pb tokens)
        (add-text pb (str \newline)))
      (layout (build pb) (* rw (inc (-cellw)))))))

(defn close-curr-window
  []
  (when-let [window (-window)]
    (GLFW/glfwSetWindowShouldClose ^Long window true)))

(defn handle-input
  [input]
  (if-not (list? input)
    (println "command must be of the form (Cmd arg1 arg2 ... argN):" input)
    (let [[cmd & args] input]
      (condp = cmd
        'Done (do (close-curr-window) (-post-handle))
        'Render (try (let [el (parse-el (first args))
                           render-data (-> el
                                           add-min-size
                                           to-render-data)
                           dims [(:rw render-data) (:rh render-data)]
                           p (to-paragraph render-data)
                           new-win? (not= dims (-dims))
                           next-dims (if new-win? dims dims)
                           up-win-id (if new-win? inc #(-> %1))]
                       (swap! (-state) #(-> %1
                                            (update :win-id up-win-id)
                                            (assoc :next-dims next-dims)
                                            (assoc :p-by-dims {dims p})))
                       (when new-win? (close-curr-window))
                       (-post-handle))
                     (catch Exception e (println :EXCEPTION) (println e)))
        cmd (println (str "invalid command: (" (str/join " " input) ")"))))))

(defn run
  [input-chan]
  (in-merged-env (runtime-init)
    (a/go (while true (handle-input (<! input-chan))))
    (-wait-first)
    (loop []
      (let [dims (-next-dims)
            curr-win-id (-win-id)
            [cw ch] dims
            width (+ (* 2 (-padding)) (* cw (-cellw)))
            height (+ (* 2 (-padding)) (* ch (-cellh)))
            window (GLFW/glfwCreateWindow (inc (int width))
                                          (inc (int height))
                                          "ztr"
                                          MemoryUtil/NULL
                                          MemoryUtil/NULL)]
        (GLFW/glfwSetWindowAttrib window
                                  GLFW/GLFW_FOCUS_ON_SHOW
                                  GLFW/GLFW_FALSE)
        (GLFW/glfwMakeContextCurrent window)
        (GLFW/glfwSwapInterval 1)
        (GLFW/glfwShowWindow window)
        (GL/createCapabilities)
        (let [context (DirectContext/makeGL)
              fb-id (GL11/glGetInteger 0x8CA6)
              [scale-x scale-y] (display-scale window)
              rgba8 FramebufferFormat/GR_GL_RGBA8
              target (BackendRenderTarget/makeGL (* scale-x width)
                                                 (* scale-y height)
                                                 0
                                                 8
                                                 fb-id
                                                 rgba8)
              surface (Surface/wrapBackendRenderTarget
                        context
                        target
                        SurfaceOrigin/BOTTOM_LEFT
                        SurfaceColorFormat/RGBA_8888
                        (ColorSpace/getSRGB))
              canvas (.getCanvas surface)]
          (.scale canvas scale-x scale-y)
          (swap! (-state) #(-> %1
                               (assoc :window window)
                               (assoc :dims dims)))
          (loop []
            (when (not (GLFW/glfwWindowShouldClose window))
              (.clear canvas (color 0xff191724))
              (let [layer (.save canvas)]
                (draw dims canvas)
                (.restoreToCount canvas layer))
              (.flush context)
              (GLFW/glfwSwapBuffers window)
              (GLFW/glfwPollEvents)
              (recur)))
          (Callbacks/glfwFreeCallbacks window)
          (GLFW/glfwHideWindow window)
          (GLFW/glfwDestroyWindow window)
          (GLFW/glfwPollEvents)
          (.close surface)
          (.close target)
          (.close context))
        (when (not= curr-win-id (-win-id))
          (recur))))
    (GLFW/glfwTerminate)
    (.free (GLFW/glfwSetErrorCallback nil))))

(defn -main
  [& _]
  (let [c (a/chan)]
    (a/go (println "welcome to ztr - have fun =^)")
          (loop []
            (let [reader-exc clojure.lang.LispReader$ReaderException
                  [input err] (try [(read)] (catch Exception e [nil e]))]
              (cond (nil? err) (do (>! c input) (recur))
                    (= (type err) reader-exc)
                    (do (>! c (list 'Done)) nil)
                    :else (do (println "unknown error type" (type err))
                              (println err)
                              (recur))))))
    (run c)
    (shutdown-agents)))
