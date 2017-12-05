(ns nsfw.page
  (:require [nsfw.util :as util]
            [nsfw.ops :as ops]
            [reagent.core :as rea]
            [bidi.bidi :as bidi]
            [dommy.core :as dommy :refer-macros [sel]]
            [cljs.core.async :as async
             :refer [<! >! chan close! sliding-buffer put! take! alts! timeout pipe mult tap]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(defn start-app [handlers]
  (let [entry-key (try
                    (:js-entry (util/page-data :env))
                    (catch js/Error e
                      nil))]
    (if-let [handler (get handlers entry-key)]
      (handler
        (util/page-data :env))
      (do
        (println "Couldn't find handler for js-entry" entry-key)
        (fn [])))))

(defn stop-app [app]
  (app))

(defn hook-reload-fn [f]
  (let [!app (atom (f))]
    (fn []
      (when @!app
        (@!app))
      (reset! !app (f)))))

(defn reloader [gen-handlers]
  (hook-reload-fn (fn [] (start-app (gen-handlers)))))

(defn push-path [& parts]
  (let [new-path (apply str parts)
        cur-path (.-pathname js/window.location)]
    (when-not (= new-path cur-path)
      (.pushState js/window.history nil nil new-path))))

(defn navigate-to [& parts]
  (aset (aget js/window "location") "href" (apply str parts)))

(defn reload []
  (.reload (aget js/window "location")))

(defn pathname []
  (.. js/window -location -pathname))

(defn href []
  (.. js/window -location -href))

(defn fq-url [& parts]
  (let [loc (.-location js/window)]
    (apply str
      (.-protocol loc)
      "//"
      (.-host loc)
      parts)))

(defn views->routes [views & [{:keys [root]}]]
  ["" (->> views
           (mapcat (fn [{:keys [view-key route routes]}]
                     (->> (concat [route] routes)
                          (remove nil?)
                          (map (fn [route]
                                 [(str root route) view-key])))))
           vec)])

(defn views->handlers [views]
  (->> views
       (map (fn [{:keys [view-key handler]}]
              [view-key handler]))
       (into {})))

(defn view-for [views k]
  (->> views
       (filter #(= k (:view-key %)))
       first))

(defn path-for [routes handler & [params]]
  (apply
    bidi/path-for
    routes
    handler
    (mapcat identity params)))

(defn handler-for [views k]
  (:handler (view-for views k)))

(defn render-for [views k]
  (:$render (view-for views k)))

(defn gen-nav-handler [views routes & [path-key]]
  {::nav (fn [state {:keys [view-key params]}]
           (push-path (path-for routes view-key params))
           (let [state (-> state
                           (assoc :view-key view-key))
                 handler (handler-for views view-key)]
             (if handler
               (or (handler state) state)
               (do
                 (println "[nsfw.page] No handler for key " view-key)
                 (assoc state :view-key view-key)))))})

(defn nav-to [bus view-key params]
  (ops/send bus ::nav {:view-key view-key :params params}))

(defn push-route [routes handler & [params]]
  (push-path (path-for routes handler params)))

(defn link [{:keys [title on-click class]}]
  ^{:key title}
  [:a {:href "#"
       :class class
       :on-click (fn [e]
                   (.preventDefault e)
                   (on-click e)
                   e)}
   title])

(defn $nav [{:keys [!view-key bus]} children]
  [:ul.nav
   (->> children
        (map (fn [{:keys [title view-key]}]
               ^{:key view-key}
               [:li
                (link {:title title
                       :class (str "nav-link"
                                (when (= @!view-key view-key) " active"))
                       :on-click
                       (fn [e]
                         (ops/send bus ::nav {:view-key view-key}))})]))
        doall)])

(defn nav-to-key [bus key & [route-params]]
  (ops/send bus ::nav {:view-key key
                       :route-params route-params}))

(defn scroll-to-top []
  (.scrollTo js/window 0 0))

(defn scroll-top []
  (.-scrollY js/window))

(defn set-scroll [n]
  (.scrollTo js/window 0 n))

(defn nav-handlers [{:keys [views routes]}]
  (let [routes (or routes
                   (views->routes views))]
    {::nav (fn [{:keys [!app routes view-key route-params]}]
             (let [{:keys [<state state] :as view} (get views view-key)]
               (push-route routes view-key route-params)
               (.scrollTo js/window 0 0)
               (if state
                 (swap! !app
                   #(-> %
                        (assoc-in [:view-key] view-key)
                        (assoc-in [:state] (state @!app route-params))))
                 (swap! !app
                   #(-> %
                        (assoc-in [:view-key] view-key))))
               (when <state
                 (go
                   (let [state (<! (<state @!app route-params))]
                     (swap! !app
                       #(-> %
                            (assoc-in [:view-key] view-key)
                            (assoc-in [:state] state))))))))}))

(defn dispatch-route [routes on-path & [{:keys [path]}]]
  (let [{:keys [route-params handler] :as match}
        (bidi/match-route routes (or path (pathname)))]
    (when handler
      (on-path handler route-params))))

(defn dispatch-view [views routes !app bus]
  (dispatch-route routes
    (fn [handler route-params]
      (nav-to-key bus handler route-params))))

(defn render-view [views !app bus]
  (let [render (:render (get views (:view-key @!app)))]
    (when render
      [render (rea/cursor !app [:state]) bus])))

(defn start-popstate-handler [on-pop]
  (aset js/window "onpopstate" on-pop)
  (fn []
    (aset js/window "onpopstate" nil)))

(defn stop-popstate-handler [f]
  (f))

(defn render-key [!state path views]
  (let [view-key (get-in @!state path)
        $view (get views view-key)]
    (if $view
      $view
      (fn []))))

(defn viewport []
  {:width (or (.. js/document -documentElement -clientWidth)
              (.-innerWidth js/window)
              0)
   :height (or (.. js/document -documentElement -clientHeight)
               (.-innerHeight js/window)
               0)})

(defn aspect-ratio []
  (let [{:keys [width height]}
        (viewport)]
    (/ width height)))

(defn on-resize [f]
  (dommy/listen! js/window :resize f)
  (fn []
    (dommy/unlisten! js/window :resize f)))

(defn on-scroll [f]
  (dommy/listen! js/window :scroll f)
  (fn []
    (dommy/unlisten! js/window :scroll f)))

(defn throttle [f delta]
  (let [last (atom nil)
        to (atom nil)]
    (fn [& args]
      (cond
        (not @last) (do
                      (reset! last (util/now))
                      (reset! to nil)
                      (apply f args))
        (> @last 0) (let [now (util/now)]
                      (if (> (- now @last) delta)
                        (do
                          (reset! last now)
                          (apply f args))
                        (do
                          (js/clearTimeout @to)
                          (reset! to
                            (js/setTimeout
                              (fn []
                                (reset! last (+ delta @last))
                                (apply f args))
                              (- delta (- now @last)))))))))))

(defn debounce [f delay]
  (let [last (atom nil)
        to (atom nil)]
    (fn [& args]
      (when @to
        (js/clearTimeout @to))
      (reset! to
        (js/setTimeout
          (fn []
            (reset! to nil)
            (apply f args))
          delay)))))

(defn throttle-debounce [f {throttle-ms :throttle
                            debounce-ms :debounce}]
  (let [f (if (and throttle-ms (> throttle-ms 0))
            (throttle f throttle-ms)
            f)
        f (if (and debounce-ms (> debounce-ms 0))
            (debounce f debounce-ms)
            f)]
    f))

(defn scroll-source [bus {:keys [op-key] :as opts}]
  (let [f (throttle-debounce
            (fn [e]
              (ops/send bus (or op-key ::scroll)
                (.. js/window -scrollY)))
            opts)]
    (dommy/listen! js/window :scroll f)
    (fn []
      (dommy/unlisten! js/window :scroll f))))

(defn resize-source [bus & [{:keys [op-key] :as opts}]]
  (let [f (throttle-debounce
            (fn [e]
              (ops/send bus (or op-key ::resize)
                {:width (aget js/window "innerWidth")
                 :height (aget js/window "innerHeight")}))
            opts)]
    (dommy/listen! js/window :resize f)
    (fn []
      (dommy/unlisten! js/window :resize f))))

(defn high-density-screen? []
  (and (.-matchMedia js/window)
       (or
         (.-matches
           (.matchMedia js/window
             "only screen and (min-resolution: 124dpi), only screen and (min-resolution: 1.3dppx), only screen and (min-resolution: 48.8dpcm)"))
         (.-matches
           (.matchMedia js/window
             "only screen and (-webkit-min-device-pixel-ratio: 1.3), only screen and (-o-min-device-pixel-ratio: 2.6/2), only screen and (min--moz-device-pixel-ratio: 1.3), only screen and (min-device-pixel-ratio: 1.3)")))))

(defn attach-fastclick [& [$el]]
  (let [$el (or $el (aget js/document "body"))]
    (.attach js/FastClick $el)))

(defn on-el [& pairs]
  (->> (partition 2 pairs)
       (mapcat (fn [[selector f]]
                 (->> (sel selector)
                      (map (fn [$el]
                             [f $el])))))
       (remove #(nil? (second %)))
       (map (fn [[f $el]]
              (f $el)))
       doall))

(defn not-empty? [o]
  (not (empty? o)))

(defn hook-dispatch [{:keys [routes
                             views
                             on-view
                             on-handler]}]
  (when (and (not routes)
             (not views))
    (throw (js/Error "`views` and/or `routes` not defined")))
  (when (and routes (not-empty? routes))
    (dispatch-route routes
      (fn [route-key route-params]
        (on-handler route-key route-params)
        (on-view route-key route-params))))
  (let [unlisten (let [f (fn [e]
                           (when (and routes (not-empty? routes))
                             (dispatch-route routes
                               (fn [route-key route-params]
                                 (on-handler route-key route-params)
                                 (on-view route-key route-params)))))]
                   (dommy/listen! js/window :popstate f)
                   (fn []
                     (dommy/unlisten! js/window :popstate f)))]
    unlisten))

(defn init [{:keys [init-state
                    context
                    views
                    routes
                    handlers
                    root-class
                    !state]}]
  (let [!state (or !state (rea/atom init-state))
        !current-view (rea/atom nil)
        bus (ops/kit
              !state
              context
              handlers)

        routes (when (and routes (not-empty? routes))
                 ["" routes])]

    (let [unload (hook-dispatch
                   {:views views
                    :routes routes
                    :on-view (fn [route-key rp]
                               (when-let [view (get views route-key)]
                                 (reset! !current-view view)))

                    :on-handler (fn [route-key rp]
                                  (ops/send bus route-key rp))})]

      (rea/render-component
        [(or @!current-view
             (fn []
               [:div "no view"]))
         !state bus]
        (.getElementById js/document
          (or root-class "page-container")))

      (fn []
        (prn "Unloading")
        (unload)))))
