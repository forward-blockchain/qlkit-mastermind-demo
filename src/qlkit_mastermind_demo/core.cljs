(ns qlkit-mastermind-demo.core
  (:require
   [qlkit.core :as ql]
   [qlkit-renderer.core :as qlr :refer-macros [defcomponent]]
   [qlkit-material-ui.core :as mat-ui]
   [goog.object :as gobj]))

(enable-console-print!)
(mat-ui/enable-material-ui!)

(defn mat-color [col]
  (gobj/get js/MaterialUIStyles.colors (name col)))

(def colors '[blue500 green500 red500 yellow500 purple500 orange500])

(def rand-colors (map colors (repeatedly #(rand-int (count colors)))))

(def total-rows 10)

(defn init-state []
  {:master/secret-row (take 4 rand-colors)
   :master/current-row (vec (repeat 4 nil))
   :master/rows []})

(defonce app-state (atom (init-state)))

(defn remove-first
  "Revmove the first element that the predicate returns true for."
  [pred coll]
  (let [[begin end] (split-with (complement pred) coll)]
    (concat begin (rest end))))

(defn hints
  "Return the a list mastermind hints :correct-color-position or :correct-color"
  [row secret-row]
  (let [num-same-colors
        (- (count secret-row)
           (count (reduce (fn [sr c]
                            (if (some #{c} sr)
                              (remove-first #{c} sr)
                              sr))
                          secret-row
                          row)))
        num-same-position
        (count (filter identity (map #(= %1 %2) row secret-row)))]
    (concat (repeat num-same-position :correct-color-position)
            (repeat (- num-same-colors num-same-position) :correct-color))))

;; -----------------------------------------
;; Read parser
;; -----------------------------------------

(defmulti reader (fn [qterm & _] (first qterm)))

(defmethod reader :default [query-term env state]
  (get state (first query-term)))

(defmethod reader :master/rows [query-term env state]
  (let [rows (get state :master/rows)]
    (mapv
     #(ql/parse-children query-term (assoc env :row-id %))
     (range (count rows)))))

(defmethod reader :row/colors [query-term env state]
  (get-in state [:master/rows (:row-id env) :row/colors]))

(defmethod reader :row/hints [query-term env {:keys [:master/rows :master/secret-row]}]
  (let [colors (:row/colors (get rows (:row-id env)))]
    (hints colors secret-row)))

;; -----------------------------------------
;; Mutate parser
;; -----------------------------------------

(defmulti mutate (fn [qterm & _] (first qterm)))

(defn handle-win-state [{:keys [:master/rows :master/secret-row] :as state}]
  (if ((set (map :row/colors rows)) secret-row)
    (assoc state :master/matched true)
    state))

(defn update-current-row-and-advance [state index color]
  (let [current-row (-> (:master/current-row state)
                        (assoc index color))]
    (if (empty? (filter nil? current-row))
      (-> state
          (assoc :master/current-row (vec (repeat 4 nil)))
          (update :master/rows conj {:row/colors current-row})
          handle-win-state)
      (assoc state :master/current-row current-row))))

(defmethod mutate :master/set-current-row-cell! [[_ {:keys [:cell/color :cell/index]}] opts state-atom]
  (when (and color index)
    (swap! state-atom update-current-row-and-advance index color)))

(defmethod mutate :master/reset! [_ _ state-atom]
  (reset! state-atom (init-state)))

;; -----------------------------------------
;; Rendering
;; -----------------------------------------

(defn board-piece
  ([color] (board-piece color nil))
  ([color options]
   [:paper (merge {:circle true
                  :style {:backgroundColor (mat-color color)
                          :width  (str 24 "px")
                          :height (str 24 "px")
                          :margin "12px"}}
                  options)]))

(defn row [{:keys [background-color peice-colors on-click]}]
  [:grid-list {:cols 4
               :cellHeight 47
               :style {:backgroundColor (or background-color "#fff")}}
   (map-indexed
    #(do [:grid-tile
          (when on-click
            {:cursor :pointer
             :on-click (fn [e] (on-click e %1))})
          (board-piece %2)])
    peice-colors)])

(defn hidden-row []
  (row {:background-color "#aaa"
        :peice-colors (repeat 4 "grey700")}))

(defn empty-row []
  (row {:peice-colors (repeat 4 "grey100")}))

(def hint-style
  {:correct-color-position
   {:backgroundColor (mat-color "grey600")
    :border (str "1px solid " (mat-color "grey600"))
    :width  (str 9 "px")
    :height (str 9 "px")
    :margin "2px"}
   :correct-color
   {:backgroundColor "#fff"
    :border (str "1px solid " (mat-color "grey500"))
    :width  (str 9 "px")
    :height (str 9 "px")
    :margin "2px"}})

(defn hint-grid [hints]
  [:div {:width "30px" :position "absolute" :right "-45px" :top "11px"}
   [:grid-list {:cols 2 :cellHeight 13}
    (map #(do [:grid-tile [:paper {:circle true
                                   :zDepth 0
                                   :style (hint-style %)}]])
         hints)]])

(defn hinted-row [colors hints]
  [:div {:position "relative"}
   (hint-grid hints)
   (row {:peice-colors colors})])

(defcomponent CurrentRow
  (query [[:master/current-row]])
  (render [{:keys [:master/current-row]} {:keys [popover] :as state}]
          [:div
           (row {:peice-colors (map #(if (nil? %) "#fff" %) current-row)
                 :on-click
                 (fn [e i]
                   (qlr/update-state!
                    assoc
                    :popover
                    {:pos i
                     :element (.-currentTarget e)}))})
           [:popover {:open (boolean (:pos popover))
                      :anchor-origin {:horizontal :middle :vertical :top}
                      :target-origin {:horizontal :middle :vertical :bottom}
                      :anchorEl (:element popover)
                      :onRequestClose
                      #(qlr/update-state! dissoc :popover)}
            [:menu (map
                   #(do [:div
                         {:display         :flex
                          :justify-content :center
                          :cursor          :pointer
                          :on-click
                          (fn [_]
                            (qlr/update-state! dissoc :popover)
                            (qlr/transact!
                             [:master/set-current-row-cell!
                              {:cell/index (:pos popover)
                               :cell/color %}]))}
                         (board-piece %)])
                   colors)]]]))

(defcomponent HintedRow
  (query [[:row/colors] [:row/hints]])
  (render [{:keys [:row/colors :row/hints]} state]
          (hinted-row colors hints)))

(defcomponent GuessingRows
  (query [[:master/matched] [:master/rows (ql/get-query HintedRow)] (ql/get-query CurrentRow)])
  (render [{:keys [:master/rows :master/matched] :as s} state]
          [:div
           (concat
            (repeat (- total-rows (+ (if matched 0 1) (count rows))) (empty-row))
            (when (and (not matched)
                       (< (count rows) total-rows))
                [[CurrentRow s]])
            (map
             #(do [HintedRow %])
             (reverse rows)))]))

;; --------------------------------------
;; Winning Star Display
;; --------------------------------------

(def rand-star-colors (repeatedly
                       #(rand-nth '[blue200 green200 red200 yellow200 purple200 orange200])))

(def rand-star-positions (repeatedly #(vector (rand-int 500) (rand-int 400))))

(defcomponent Winning
  (render [_ _]
          [:div {:position "absolute"}
           (take 200
                 (map-indexed
                  (fn [idx [color [y x]]]
                    (vector
                     :div
                     {:style {:position "absolute"
                              :top y
                              :left (rand-nth [(- -30 x) (+ 200 x)])}}
                     [:toggle-star {:color (mat-color (name color))
                                    :style {:width "200px"}}]))
                  (map vector
                       rand-star-colors
                       rand-star-positions)))]))

(defcomponent MasterMind
  (query [[:master/matched] [:master/secret-row] [:master/rows] (ql/get-query GuessingRows)])
  (render [{:keys [:master/matched :master/secret-row :master/rows] :as res} state]
          [:div {:style {:width "200px"
                         :margin "auto"
                         :marginTop "1em"
                         :position "relative"}}
           (when matched
             [Winning])
           [:paper
            (cond
              matched
              (row {:background-color (mat-color "green100")
                    :peice-colors secret-row})
              (>= (count rows) total-rows)
              (row {:background-color (mat-color "red100")
                    :peice-colors secret-row})
              :else (hidden-row))
            [GuessingRows res]]
           [:raised-button {:on-click (fn [_] (qlr/transact! [:master/reset!]))
                            :full-width true
                            :style {:marginTop "1em"}} "RESET"]]))

#_(prn :secret (:master/secret-row @app-state))

(ql/mount
 {:component MasterMind
  :dom-element (.getElementById js/document "app")
  :state app-state
  :parsers {:read reader
            :mutate mutate}})

(defn on-js-reload [])
