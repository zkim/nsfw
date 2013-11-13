(ns nsfw.dom
  "Utilities for DOM interation / event binding."
  (:use [nsfw.util :only [log]])
  (:require [dommy.template :as template]
            [goog.dom :as dom]
            [goog.dom.classes :as classes]
            [goog.style :as style]
            [goog.events :as events]
            [goog.dom.query]
            [goog.dom.forms :as forms]
            [goog.net.XhrIo]
            [cljs.core :as cc]
            [nsfw.util :as util]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [nsfw.pubsub :as ps])
  (:refer-clojure :exclude [val replace remove empty drop > select]))

(extend-type js/NodeList
  ISeqable
  (-seq [array] (array-seq array 0)))

(extend-type js/NodeList
  ICollection
  (-conj [coll o]
    (throw "Error: Can't conj onto a NodeList.")))

(extend-type js/HTMLCollection
  ISeqable
  (-seq [array]
    (array-seq array 0)))

(extend-type js/NodeList
  ICollection
  (-conj [coll o]
    (throw "Error: Can't conj onto a NodeList.")))

(defn node-list? [o]
  (= js/NodeList (type o)))

(defn ge->map
  "Turns a google closure event into a map.
   See http://goo.gl/Jxgbo for more info."
  [e]
  (let [res {:type (.-type e)
             :timestamp (.-timestamp e)
             :target (.-target e)
             :current-target (.-currentTarget e)
             :related-target (.-relatedTarget e)
             :offset-x (.-offsetX e)
             :offset-y (.-offsetY e)
             :client-x (.-clientX e)
             :client-y (.-clientY e)
             :screen-x (.-screenX e)
             :screen-y (.-screenY e)
             :button (.-button e)
             :key-code (.-keyCode e)
             :ctrl-key (.-ctrlKey e)
             :alt-key (.-altKey e)
             :shift-key (.-shiftKey e)
             :meta-key (.-metaKey e)
             :default-prevented (.-defaultPrevented e)
             :state (.-state e)
             :event e}
        nsfw-payload (when-let [event_ (.-event_ e)]
                       (aget event_ "nsfw_payload"))]
    (if nsfw-payload
      (merge res nsfw-payload)
      res)))

(defn ensure-coll [el]
  (if (or (coll? el)
          (= js/Array (type el)))
    el
    [el]))

(defn ensure-el [coll]
  (if (or (coll? coll)
          (= js/Array (type coll)))
    (first coll)
    coll))

;; Dom

(defn root []
  (aget (dom/getElementsByTagNameAndClass "html") 0))

(defn query
  ([s]
     (dom/query (name s)))
  ([base s]
     (dom/query (name s) base)))

(defn node [o]
  (template/node o))

(defn $
  ([o]
     (cond
      (coll? o) (template/node o)
      (or (keyword? o)
          (string? o)) (query (name o))
      :else o))
  ([base o]
     (query (ensure-el base) (name o))))

(def > $)

(defn unwrap [el]
  (if (coll? el)
    (first el)
    el))

(defn val
  ([el]
     (when el
       (let [res (forms/getValue (unwrap el))]
         (when-not (empty? res) res))))
  ([el new-value]
     (doseq [el (ensure-coll el)]
       (forms/setValue el new-value))
     el))

(defn wrap-content
  [content]
  (cond
   (and (coll? content)
        (keyword? (first content))) ($ content)
   (string? content) ($ content)
   :else content))

(defn append [els content]
  (doseq [el (if (or (keyword? els)
                     (string? els))
               (query els)
               (ensure-coll els))]
    (if el
      (if (and (coll? content)
               (not (keyword? (first content))))
        (doseq [c content]
          (when c
            (.appendChild el (wrap-content c))))
        (when content
          (.appendChild el (wrap-content content))))
      (throw "Can't call nsfw.dom/append on a null element")))
  els)

(defn prepend [els content]
  (doseq [el (ensure-coll els)]
    (if el
      (if (and (coll? content)
               (not (keyword? (first content))))
        (doseq [c content]
          (when c
            (dom/insertChildAt el (wrap-content c) 0))
          (when content
            (when-let [on-insert (aget content "on-insert")]
              (on-insert el))))
        (do (when content
              (dom/insertChildAt el (wrap-content content) 0))
            (when content
              (when-let [on-insert (aget content "on-insert")]
                (on-insert el)))))
      (throw "Can't call dom/append on a null element")))
  els)

(def apd append)

(defn append-to [child parents]
  (doseq [el (ensure-coll parents)]
    (append el child))
  child)

(defn insert-at [parent child index]
  (let [child (node child)]
    (doseq [parent (ensure-coll parent)]
      (dom/insertChildAt parent child index))))

(defn parse-html [s]
  (dom/htmlToDocumentFragment s))

(defn style [els css-map]
  (let [jsobj (clj->js css-map)]
    (doseq [el (ensure-coll els)]
      (style/setStyle el jsobj)))
  els)

(defn size [el]
  (let [res (style/getSize el)]
    [(.-width res)
     (.-height res)]))

(defn attrs
  [els m]
  (doseq [el (ensure-coll els)]
    (doseq [key (keys m)]
      (let [v (get m key)]
        (if (nil? v)
          (.removeAttribute el (name key))
          (.setAttribute el (name key) (get m key))))))
  els)

(defn attr [el attr]
  (.getAttribute el (name attr)))

(defn text
  ([els]
     (dom/getTextContent (unwrap els)))
  ([els text]
     (doseq [el (ensure-coll els)]
       (dom/setTextContent el (str text)))
     els))

(defn replace [els content]
  (doseq [el (ensure-coll els)]
    (dom/replaceNode content el))
  els)

(defn remove [els]
  (doseq [el (ensure-coll els)]
    (dom/removeNode el)))

(defn empty [els]
  (doseq [el (ensure-coll els)]
    (dom/removeChildren el))
  els)

(defn children [el]
  (seq (dom/getChildren (ensure-el el))))

(defn html
  ([els html-string]
     (doseq [$el (ensure-coll els)]
       (-> $el
           empty
           (append (dom/htmlToDocumentFragment html-string))))))

(defn has-class? [el cls]
  (classes/has (unwrap el) (name cls)))

(defn add-class [els cls]
  (doseq [el (ensure-coll els)]
    (when el
      (classes/add el (name cls))))
  els)

(defn rem-class [els cls]
  (doseq [el (ensure-coll els)]
    (when el
      (classes/remove el (name cls))))
  els)

(defn tog-class [els cls]
  (doseq [el (ensure-coll els)]
    (classes/toggle el (name cls))))

(def body ($ "body"))

;; Events

(defn prevent [e]
  (.preventDefault (:event e)))

(defn stop-prop [e]
  (.stopPropagation (:event e)))

(defn onload [f]
  (set! (.-onload js/window) f))

(def listen-error-handler (atom nil))

(defn set-listen-error-handler [f]
  (reset! listen-error-handler f))

(defn listen [els evt f]
  (when els
    (doseq [el (ensure-coll els)]
      (events/listen el (name evt)
        #(try
           (f (ge->map %) el)
           (catch js/Error e
             (if @listen-error-handler
               (@listen-error-handler e)
               (throw e))))))
    els))

(defn handler [evt]
  (fn
    ([els f]
       (listen els (name evt) f))
    ([els sel f]
       (listen (dom/$ els sel) (name evt) f)
       els)))

(def click (handler :click))
(def dblclick (handler :dblclick))

(def mousedown (handler :mousedown))
(def mouseup (handler :mouseup))
(def mouseover (handler :mouseover))
(def mouseout (handler :mouseout))
(def mousemove (handler :mousemove))
(def selectstart (handler :selectstart))

(def keypress (handler :keypress))
(def keydown (handler :keydown))
(def keyup (handler :keyup))

(def change (handler :change))
#_(def select (handler :select))
(def submit (handler :submit))
(def input (handler :input))

(def dragstart (handler :dragstart))
(def dragenter (handler :dragenter))
(def dragover (handler :dragover))
(def dragleave (handler :dragleave))
(def drop (handler :drop))

(def touchstart (handler :touchstart))
(def touchmove (handler :touchmove))
(def touchend (handler :touchend))
(def touchcancel (handler :touchcancel))

(def contextmenu (handler :contextmenu))
(def error (handler :error))
(def help (handler :help))
(def load (handler :load))
(def losecapture (handler :losecapture))
(def readstatechange (handler :readstatechange))
(def resize (handler :resize))
(def scroll (handler :scroll))
(def unload (handler :unload))

(def hashchange (handler :hashchange))
(def pagehide (handler :pagehide))
(def pageshow (handler :pageshow))
(def popstate (handler :popstate))

(defn focus
  ([el]
     (.focus (ensure-el el))
     el)
  ([els f]
     (listen els :focus f)))

(defn blur
  ([el]
     (.blur (ensure-el el))
     el)
  ([els f]
     (listen els :blur f)))

(defn select [el]
  (.select el))

(defn match-key [els key f]
  (doseq [el (ensure-coll els)]
    (keyup el (fn [e]
                (let [kc (.-keyCode e)]
                  (when (= key kc)
                    (f el (val el)))))))
  els)

(defn val-changed
  ([els f]
     (doseq [el (ensure-coll els)]
       (let [f (fn [e]
                 (f el (val el)))]  ; el, then val - side effects only
         (keyup el f)
         (change el f)))
     els)
  ([base sel f]
     (let [$el ($ base)
           $target ($ $el sel)]
       (val-changed $target f)
       $el)))

(defn on-enter [els f]
  (doseq [el (ensure-coll els)]
    (keydown el (fn [e]
                  (when (= 13 (:key-code e))
                    (prevent e)
                    (stop-prop e)
                    (f e el)))))
  els)

(defn keys-down [root & args]
  (let [sel (when (-> args count odd?)
              (first args))
        args (if (-> args count odd?)
               (rest args)
               args)
        pairs (partition 2 args)
        els (if sel ($ root sel) root)]
    (when (> (count els) 0)
      (doseq [[target-key f] pairs]
        (keydown
         els
         (fn [{:keys [key-code] :as e}]
           (when (= key-code target-key)
             (f e root))))))
    root))

(defn scroll-end [els f]
  (doseq [el (ensure-coll els)]
    (let [timer (atom -1)]
      (listen js/window "scroll"
              (fn [e]
                (when (> @timer -1)
                  (util/clear-timeout @timer))
                (reset! timer (util/timeout #(f e) 150))))))
  els)

(defn on-insert [els f]
  (doseq [el (ensure-coll els)]
    (aset el "on-insert" f)))

(defn viewport []
  (let [vp (dom/getViewportSize)]
    [(.-width vp)
     (.-height vp)]))

(defn bounds [el]
  (let [b (style/getBounds el)]
    {:width (.-width b)
     :height (.-height b)
     :left (.-left b)
     :top (.-top b)}))

(defn scroll-to
  [el]
  (.scrollIntoView el true))

(defn scroll-top [& [el]]
  (let [el (or el js/document)]
    (if (or (= js/window el) (= js/document el))
      (or (aget js/window "pageYOffset")
          (aget (.-documentElement js/document) "scrollTop")
          (aget (.-body js/document) "scrollTop"))
      (aget el "scrollTop"))))

(def transition-prop
  (let [styles (.-style (.createElement js/document "a"))
        props ["webkitTransition" "MozTransition" "OTransition" "msTransition"]]
    (or (first (filter #(= "" (aget styles %)) props))
        "Transition")))

(def trans-end-prop
  (condp = transition-prop
    "webkitTransition" "webkitTransitionEnd" ; webkit
    "OTransition" "oTransitionEnd" ; opera
    "transitionend"))

(defn trans*
  [el {:keys [done dur ease]
       :or   {done #() dur "1s" ease "ease"}
       :as   opts}]
  (let [st (dissoc opts :done :dur :ease)
        t  (str "all " (name dur) " " (name ease))]
    (when-not (= t (aget (.-style el) transition-prop))
      (aset (.-style el) transition-prop t))
    (style el st)
    (listen el
            trans-end-prop
            (util/run-once
             (fn []
               #_(aset (aget el "style") transition-prop "")
               (done))))
    el))

(defn trans [els & os]
  (doseq [el (ensure-coll els)]
    (let [os (loop [os os out []]
               (let [fo (first os)
                     so (second os)]
                 (if-not so
                   (conj out fo)
                   (recur (rest os)
                          (conj out (assoc fo
                                      :done (fn []
                                              ((or (:done fo) #()))
                                              (trans* el so))))))))]
      (trans* el (first os))
      el)))

(defn brect [$el]
  (let [br (-> $el
               ensure-el
               (.getBoundingClientRect))]
    {:bottom (.-bottom br)
     :height (.-height br)
     :left (.-left br)
     :right (.-right br)
     :top (.-top br)
     :width (.-width br)}))

(defn fire
  "Fire a custom DOM event, opts takes :bubble? and :preventable? as
   bool config options on whether or not to bubble the event, and
   allow preventing of the event, respectively."
  [$el event payload & opts]
  (let [opts (apply hash-map opts)
        bubble? (or (:bubble? opts) true)
        preventable? (or (:preventable? opts) true)
        ev (.createEvent js/document "Event")]
    (.initEvent ev (name event) true true)
    (aset ev "nsfw_payload" payload)
    (.dispatchEvent $el ev))
  $el)

(defn apply-transform [$el transforms]
  (when-not (node-list? transforms)
    (doseq [{:keys [style text add-class rem-class selector val] :as xf}
            (if (or (list? transforms)
                    (vector? transforms))
              transforms
              [transforms])]
      (let [$el (if selector
                  (query $el selector)
                  $el)]
        (when style
          (nsfw.dom/style $el style))
        (when text
          (nsfw.dom/text $el text))
        (when add-class
          (doseq [cls (if (coll? add-class)
                        add-class
                        [add-class])]
            (nsfw.dom/add-class $el cls)))
        (when rem-class
          (doseq [cls (if (coll? rem-class)
                        rem-class
                        [rem-class])]
            (nsfw.dom/rem-class $el cls)))
        (when val
          (nsfw.dom/val $el val))))))

(defn calc-transform
  "Loop over msg handlers, match types (and typeless handlers), and call
   actions. Returns a list of resulting actions."
  [o msg]
  (let [[msg-type action] msg]
    (loop [hs (->> o :msg-handlers (filter identity))
           ress []]
      (if (empty? hs)
        ress
        (recur (rest hs)
               (let [msg-handler (first hs)
                     match? (= msg-type (:msg-type msg-handler))
                     res (cond
                          (nil? (:msg-type msg-handler))
                          ((:action msg-handler) msg o)

                          match?
                          (apply (:action msg-handler) (concat (rest msg) [o]))

                          :else nil)]
                 (conj ress res)))))))

(defn send [o msg]
  (let [transforms (calc-transform o msg)]
    (doseq [xf transforms]
      (apply-transform (:$el o) xf))
    transforms))

(defn bind [atom f]
  (add-watch
   atom
   (gensym)
   (fn [key identity old-value new-value]
     (f identity old-value new-value)))
  atom)

(defn on-change [atom f]
  (bind
   atom
   (fn [id old new]
     (when-not (= old new)
       (f id old new)))))

(defn build [{:keys [html
                     init
                     events
                     msg-handlers
                     data-bindings
                     !state
                     bus] :as opts}]
  ;; gen html
  (let [$root (init opts)
        opts (assoc opts :$el $root)
        bus (or bus (ps/mk-bus))]

    ;; add subs
    (doseq [{:keys [msg-type action]}
            (->> msg-handlers (filter identity))]
      (ps/sub bus msg-type
              (fn [msg]
                (if msg-type
                  (apply action (concat (clojure.core/drop 1 msg) [opts]))
                  (action msg opts)))))

    ;; bind events
    (doseq [{:keys [selector event transform]} events]
      (let [$el (if selector
                  (first (query $root selector))
                  $root)]
        (listen $el event (fn [e]
                            (when-let [msg (transform e $el opts)]
                              (ps/pub bus msg))))))

    (doseq [{:keys [query-fn handler]} data-bindings]
      (on-change
       !state
       (fn [id old new]
         (let [qold (query-fn old)
               qnew (query-fn new)]
           (when (not= qold qnew)
             (handler qnew qold opts)))))
      (handler (query-fn @!state) nil opts))
    (:$el opts)))

(defn parse-sel-ev [sel-ev]
  (let [event (->> sel-ev
                   name
                   reverse
                   (take-while #(not= "." %))
                   reverse
                   (apply str))
        sel (->> sel-ev
                 name
                 reverse
                 (drop-while #(not= "." %))
                 (clojure.core/drop 1)
                 reverse
                 (apply str))
        sel (if (empty? sel)
              nil
              sel)]
    [sel event]))

(defn validate-ajax-args [{:keys [method]}]
  (let [valid-http-methods #{:get
                             :post
                             :put
                             :patch
                             :delete
                             :options
                             :head
                             :trace
                             :connect}]
    (when-not (get valid-http-methods method)
      (throw (str "nsfw.dom/ajax: "
                  method
                  " is not a valid ajax method ("
                  (->> valid-http-methods
                       (map pr-str)
                       (interpose ", ")
                       (apply str))
                  ")")))))

(defn safe-name [o]
  (when o
    (name o)))

(defn safe-upper-case [s]
  (when s
    (str/upper-case s)))

(def ajax-defaults
  {:path "/"
   :method "GET"
   :data {}
   :success (fn []
              (throw "nsfw.dom/ajax: Unhandled :success callback from AJAX call."))
   :error (fn []
            (throw "nsfw.dom/ajax: Unhandled :error callback from AJAX call."))})

(defn parse-headers [s]
  (when s
    (->> (str/split s #"\r\n")
         (mapcat (fn [header]
                   (->> (str/split header ":")
                        (map str/trim))))
         (apply hash-map))))

(defn req->resp [req]
  {:headers (parse-headers (.getAllResponseHeaders req))
   :status (.getStatus req)
   :body (.getResponseText req)
   :success (.isSuccess req)})

(defn format-body [{:keys [headers body] :as r}]
  (let [content-type (or (-> headers
                             (get "content-type"))
                         (-> headers
                             (get "Content-Type"))
                         "")
        body (condp #(re-find % content-type) content-type
               #"application/json" (-> body
                                       JSON/parse
                                       (js->clj :keywordize-keys true))
               #"application/edn" (reader/read-string body)
               body)]
    (assoc r :body body)))

(defn ajax [opts]
  (let [opts (merge ajax-defaults opts)
        opts (if-not (:headers opts)
               (assoc opts
                 :headers (condp = (:data-type opts)
                            :json {"content-type" "application/json"}
                            :edn {"content-type" "application/edn"}
                            {"content-type" "application/edn"}))
               opts)
        opts (cond
               (= :json (:data-type opts))
               (assoc opts :data (-> (:data opts)
                                     clj->js
                                     JSON/stringify))

               (= :edn (:data-type opts))
               (assoc opts :data (pr-str (:data opts)))

               :else opts)
        {:keys [path method data headers success error data-type]} opts]
    (validate-ajax-args opts)
    (goog.net.XhrIo/send
      path
      (fn [e]
        (try
          (let [req (.-target e)
                resp (-> req
                         req->resp
                         format-body)]
            (if (:success resp)
              (success resp)
              (error resp)))
          (catch js/Object e
            (.error js/console (.-stack e))
            (throw e))))
      (-> method
          name
          safe-upper-case)
      data
      (clj->js headers))))

(defn form-values [$el]
  (let [$els (->> ["input" "select" "textarea"]
                  (map #(query $el %))
                  (reduce concat))]
    (->> $els
         (map (fn [$el]
                (let [data-type (attr $el :data-type)
                      v (val $el)
                      v (if data-type
                          (try
                            (condp = data-type
                              "number" (js/parseFloat v)
                              v)
                            (catch js/Error v))
                          v)]
                  [(attr $el :name) v])))
         (clojure.core/remove #(nil? (first %)))
         (map #(vector (keyword (first %))
                 (second %)))
         (into {}))))

(defn parent [el]
  (dom/getParentElement el))
