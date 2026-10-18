(ns consultas.compilador)

(defn erro! [msg] (throw (ex-info msg {:tipo :esquema})))

;; ----- VALIDACOES DE ESQUEMA -----
(defn validar-campo! [esquema campo]
  (when-not (some #(= campo (first %)) esquema)
    (erro! (str "campo inexistente: " (name campo)))))

(defn validar-num! [esquema campo]
  (validar-campo! esquema campo)
  (let [tipo (second (first (filter #(= campo (first %)) esquema)))]
    (when-not (#{:inteiro :decimal} tipo)
      (erro! (str "soma/media exige numero: cita " (name campo))))))

(defn comparar [v1 v2]
  (if (and (number? v1) (number? v2))
    (compare (double v1) (double v2)) ; Iguala tipos numericos para poder comparar 8 com 8.0
    (compare v1 v2)))

;; ----- COMPILADOR RECURSIVO -----
(defn compilar-expr [esquema expr]
  (let [[tipo arg1 arg2] expr]
    (case tipo
      :nao (let [f (compilar-expr esquema arg1)] (complement f))
      :e   (let [f1 (compilar-expr esquema arg1) f2 (compilar-expr esquema arg2)] (every-pred f1 f2))
      :ou  (let [f1 (compilar-expr esquema arg1) f2 (compilar-expr esquema arg2)] (some-fn f1 f2))
      
      ;; Comparacoes (ex: [:= :nota 8.0])
      (:= :!= :< :<= :> :>=)
      (do
        (validar-campo! esquema arg1)
        (let [tipo-campo (second (first (filter #(= arg1 (first %)) esquema)))
              tipo-lit (cond (string? arg2) :texto (float? arg2) :decimal :else :inteiro)]
          
          ;; Checa compatibilidade (inteiro e decimal casam, texto so casa com texto)
          (when (or (and (= tipo-campo :texto) (not= tipo-lit :texto))
                    (and (not= tipo-campo :texto) (= tipo-lit :texto)))
            (erro! (str "comparacao entre tipos incompativeis no campo " (name arg1))))
          
          (fn [registro]
            (let [val (get registro arg1)
                  v1 (if (number? val) (double val) val)
                  v2 (if (number? arg2) (double arg2) arg2)]
              (case tipo
                :=  (= v1 v2)
                :!= (not= v1 v2)
                :<  (neg? (comparar v1 v2))
                :<= (not (pos? (comparar v1 v2)))
                :>  (pos? (comparar v1 v2))
                :>= (not (neg? (comparar v1 v2)))))))))))

(defn compilar-estagio [esquema estagio]
  (let [[tipo arg1 arg2] estagio]
    (case tipo ; OBRIGATORIO: Case sem expressao padrao para estourar excecao no teste "voar"
      :onde        (partial filter (compilar-expr esquema arg1))
      :limitar     (partial take arg1)
      :ordenar-por (do
                     (validar-campo! esquema arg1)
                     (let [comp-fn (if (= arg2 :desc) #(compare %2 %1) compare)]
                       (partial sort-by arg1 comp-fn)))
      :selecionar  (do
                     (doseq [c arg1] (validar-campo! esquema c))
                     (partial map #(select-keys % arg1))))))

(defn compilar-terminal [esquema terminal]
  (if-not terminal
    nil
    (let [[tipo arg1 arg2] terminal]
      (case tipo
        :contar      #(reduce (fn [acc _] (inc acc)) 0 %)
        :soma        (do
                       (validar-num! esquema arg1)
                       #(reduce (fn [acc reg] (+ acc (get reg arg1))) 0 %))
        :media       (do
                       (validar-num! esquema arg1)
                       #(let [[soma n] (reduce (fn [[s n] reg] [(+ s (get reg arg1)) (inc n)]) [0 0] %)]
                          (if (zero? n) 0.0 (double (/ soma n)))))
        :agrupar-por (do
                       (validar-campo! esquema arg1)
                       (let [agreg-fn (compilar-terminal esquema arg2)]
                         #(let [grupos (group-by arg1 %)]
                            (->> grupos
                                 (map (fn [[k v]] [k (agreg-fn v)]))
                                 (sort-by first)))))))))

(defn compilar-consulta [esquema ast]
  (let [estagios-fns (map #(compilar-estagio esquema %) (:estagios ast))
        ;; comp le da direita pra esquerda, o reduce inverte a ordem para ficar pipeline
        pipe-fn (if (empty? estagios-fns)
                  identity
                  (reduce (fn [f g] (comp g f)) identity estagios-fns))
        term-fn (compilar-terminal esquema (:terminal ast))]
    {:pipe pipe-fn
     :terminal term-fn
     :campos-selecionados (when-let [sel (last (:estagios ast))]
                            (when (= (first sel) :selecionar)
                              (second sel)))}))