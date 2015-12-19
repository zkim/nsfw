(ns nsfw.page
  (:require [nsfw.util :as util]
            [nsfw.ops :as ops]
            [reagent.core :as rea]
            [bidi.bidi :as bidi]
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

(defn reloader [gen-handlers]
  (let [!app (atom (start-app (gen-handlers)))]
    (fn []
      (when @!app
        (@!app))
      (reset! !app (start-app (gen-handlers))))))

(defn push-path [& parts]
  (let [new-path (apply str parts)
        cur-path (.-pathname js/window.location)]
    (when-not (= new-path cur-path)
      (.pushState js/window.history nil nil new-path))))

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

(defn views->routes [views]
  ["" (->> views
           (mapcat (fn [[k {:keys [route routes]}]]
                     (->> (concat [route] routes)
                          (remove nil?)
                          (map (fn [route]
                                 {route k})))))
           (#(do (prn %) %))
           (reduce merge))])

(defn views->handlers [views]
  (->> views
       (map second)
       (map :handlers)
       (apply merge)))

(defn path-for [routes handler & [params]]
  (apply
    bidi/path-for
    routes
    handler
    (mapcat identity params)))

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

(defn dispatch-route [routes on-path]
  (let [{:keys [route-params handler] :as match}
        (bidi/match-route routes (pathname))]
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

(defn start-popstate-handler [views routes !app bus]
  (let [on-pop (fn [_]
                 (dispatch-view routes views !app bus))]
    (aset js/window "onpopstate" on-pop)
    (fn []
      (aset js/window "onpopstate" nil))))

(defn stop-popstate-handler [f]
  (f))
