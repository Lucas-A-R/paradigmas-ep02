(ns consultas.core
  (:require [clojure.string :as str]
            [consultas.sintaxe :as sintaxe]
            [consultas.compilador :as compilador]))

;; ---- O BUILDER ----
(defn consulta [] {:estagios [] :terminal nil})
(defn onde [c expr] (update c :estagios conj [:onde expr]))
(defn selecionar [c campos] (update c :estagios conj [:selecionar campos]))
(defn ordenar-por [c campo ordem] (update c :estagios conj [:ordenar-por campo ordem]))
(defn limitar [c n] (update c :estagios conj [:limitar n]))
(defn analisar [texto]
  ;; Valida se há algum estágio vazio provocado por pipes isolados ou sobrando (ex: "QUERY |")
  (when (str/includes? texto "|")
    (let [partes (str/split texto #"\|")]
      (when (some #(str/blank? (str/trim %)) partes)
        (throw (ex-info "estagio vazio" {:tipo :sintaxe})))))
  
  (let [tokens (sintaxe/tokenize texto)
        grupos (sintaxe/split-by-pipe tokens)]
    (sintaxe/classificar-e-validar (mapv sintaxe/parse-estagio grupos))))

;; ---- O EXECUTAR (Roda a pipeline) ----
(defn executar [c dados]
  (try
    (let [compilado (compilador/compilar-consulta (:esquema dados) c)
          resultados ((:pipe compilado) (:registros dados))]
      (if (:terminal compilado)
        ((:terminal compilado) resultados)
        (let [campos (or (:campos-selecionados compilado) (map first (:esquema dados)))]
          ;; Retorna uma lista de sequencias com os valores dos registros na ordem certa
          (map (fn [reg] (map #(get reg %) campos)) resultados))))
    (catch clojure.lang.ExceptionInfo e
      {:erros [(ex-message e)]})))

;; ---- A LEITURA DO CSV ----
(defn parse-valor [tipo valor-str]
  (case tipo :inteiro (Long/parseLong valor-str) :decimal (Double/parseDouble valor-str) :texto valor-str))

(defn parse-linha [esquema linha-str]
  (let [valores (str/split linha-str #";")]
    (reduce (fn [mapa [[campo tipo] v-str]] (assoc mapa campo (parse-valor tipo v-str))) {} (map vector esquema valores))))

(defn ler-dados [texto-csv]
  (let [linhas (str/split-lines texto-csv)
        esquema (mapv (fn [col] (let [[n t] (str/split col #":")] [(keyword n) (keyword t)])) (str/split (first linhas) #";"))]
    {:esquema esquema :registros (map #(parse-linha esquema %) (rest linhas))}))