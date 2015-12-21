(ns nsfw.ops
  "Provides message-based dispatching and context sharing. This helps
  with decoupling disparate parts of an app while sharing a common
  context (e.g. app state, windows, connections) between those parts."
  (:require [nsfw.util :as util]
            [cljs.core.async :as async
             :refer [<! >! chan close! sliding-buffer put! take!
                     alts! timeout pipe mult tap]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(defprotocol Dispatcher
  (send [this op] [this op data])
  (bind! [this kw->f])
  (unbind! [this kws])
  (set-ctx! [this ctx])
  (set-debug! [this id f])
  (clear-debug! [this id]))

(defn bus [context handlers]
  (let [!handlers (atom handlers)
        !ctx (atom context)
        !debug-fns (atom {})
        bus (reify
              Dispatcher
              (send [this op]
                (send this op nil))
              (send [this op data]
                (when-let [msg {::op op ::data data}]
                  (let [op (or (::op msg) (:op msg))]
                    (if-let [f (get @!handlers op)]
                      (do
                        (f
                          (merge {:bus this}
                                 @!ctx)
                          (::data msg)))
                      (println "[nsfw.ops] No handler for op" msg))
                    (when-not (empty? @!debug-fns)
                      (doseq [f (vals @!debug-fns)]
                        (f op))))))
              (bind! [_ kw->f]
                (swap! !handlers merge kw->f))
              (unbind! [_ kws]
                (swap! !handlers
                  #(apply dissoc % kws)))
              (set-ctx! [_ ctx]
                (reset! !ctx ctx))
              (set-debug! [_ id f]
                (swap! !debug-fns assoc id f))
              (clear-debug! [_ id]
                (swap! !debug-fns dissoc id)))]
    bus))

(defn data [op]
  (::data op))

(defn op [{:keys [op op-id data on-ack on-error auth]}]
  {::op op
   ::data data
   ::op-id (or op-id (util/uuid))
   ::on-ack on-ack
   ::auth auth
   ::on-error on-error})

(defn wrap-with-state
  ([with-state]
   (fn [h]
     (wrap-with-state h with-state)))
  ([h with-state & args]
   (fn [state params ctx]
     (let [res (h state params ctx)]
       (cond
         (map? res) (do (with-state res) res)
         (vector? res) (let [[state ch] res]
                         (with-state state)
                         [state
                          (async/map
                            (fn [state]
                              (with-state state)
                              state)
                            [ch])]))))))


(defn apply-state [!state state params post]
  (let [state' (cond
                 (fn? state) (state @!state)
                 :else state)]
    (post (reset! !state state') params)))


(defn kit [!state ctx handlers & [side-effect-fns]]
  (let [run-ses (fn [state params]
                  (doseq [f side-effect-fns]
                    (f state params))
                  state)]
    (bus
      (assoc ctx :!state !state)
      (->> handlers
           (map (fn [[k v]]
                  [k (fn [{:keys [!state] :as ctx} params]
                       (let [res (v @!state params ctx)]
                         (cond
                           (map? res) (apply-state
                                        !state
                                        res
                                        params
                                        run-ses)
                           (vector? res) (let [[state ch] res]
                                           (go-loop [ch ch]
                                             (when ch
                                               (let [[state ch] (<! ch)]
                                                 (when state
                                                   (apply-state
                                                     !state
                                                     state
                                                     params
                                                     run-ses))
                                                 (when ch
                                                   (recur ch)))))
                                           (when state
                                             (apply-state
                                               !state
                                               state
                                               params
                                               run-ses)))
                           (fn? res) (apply-state
                                       !state
                                       res
                                       params
                                       run-ses))))]))
           (into {})))))
