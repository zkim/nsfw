(ns nsfw.geo
  (:use [nsfw.util :only [log]])
  (:require [nsfw.dom :as dom])
  (:refer-clojure :exclude [map]))

(def -geolocation (.-geolocation js/navigator))

(defn geoloc->map [g]
  (let [c (.-coords g)]
    {:timestamp (.-timestamp g)
     :accuracy (.-accuracy c)
     :altitude (.-altitude c)
     :altitude-accuracy (.-altitudeAccuracy c)
     :heading (.-heading c)
     :latlng [(.-latitude c) (.-longitude c)]
     :speed (.-speed c)}))

(defn pos [success & [failure]]
  (.getCurrentPosition -geolocation
                       (fn [geoloc]
                         (success (geoloc->map geoloc)))
                       (fn [err]
                         (when failure
                           (failure err)))))

(defn map
  "Map options, see http://goo.gl/IlBC8 for full list."
  [el & [opts]]
  (let [opts (assoc opts :center
                    (if (:center opts)
                      (google.maps.LatLng. (first (:center opts)) (second (:center opts)))
                      (google.maps.LatLng. 37.7750 -122.4183)))]
    (google.maps.Map. el (clj->js
                          (merge {:zoom 2
                                  :mapTypeId google.maps.MapTypeId.ROADMAP
                                  :scrollwheel false
                                  :panControl false
                                  :zoomControl true
                                  :mapTypeControl false
                                  :scaleControl false
                                  :streetViewControl false
                                  :overviewMapControl false}
                                 opts)))))

(defn center
  ([map]
     (let [center (.getCenter map)]
       [(.lat center) (.lng center)]))
  ([map [lat lng]]
     (.setCenter map (google.maps.LatLng. lat lng))))

(defn zoom [map level]
  (.setZoom map level))