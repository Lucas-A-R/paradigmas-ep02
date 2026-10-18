(ns consultas.sintaxe
  (:require [clojure.string :as str]))

(defn erro! [msg]
  (throw (ex-info msg {:tipo :sintaxe})))

;; Transforma a string em uma lista de tokens (palavras, numeros, operadores ou strings com aspas)
(defn tokenize [s]
  (re-seq #"\"[^\"]*\"|!=|>=|<=|[=<>|(),]|[^\s()|,]+" s))

;; Divide a consulta em "estagios" toda vez que encontra o pipe "|"
(defn split-by-pipe [tokens]
  (->> tokens
       (partition-by #(= % "|"))
       (remove #(= % '("|")))))

(defn parse-literal [tok]
  (cond
    (str/starts-with? tok "\"") (subs tok 1 (dec (count tok))) ; Tira aspas
    (str/includes? tok ".") (try (Double/parseDouble tok) (catch Exception _ (erro! "numero invalido")))
    :else (try (Long/parseLong tok) (catch Exception _ (erro! "numero invalido")))))

(declare parse-expr)

(defn parse-comparacao [tokens]
  (if (< (count tokens) 3)
    (erro! "falta o literal")
    (let [[campo op lit & resto] tokens]
      (if (#{"=" "!=" "<" "<=" ">" ">="} op)
        [[(keyword op) (keyword campo) (parse-literal lit)] resto]
        (erro! "operador desconhecido")))))

;; Analise recursiva sem estado (passando os tokens restantes para frente)
(defn parse-primario [[tok & resto :as tokens]]
  (if (nil? tok)
    (erro! "expressao incompleta")
    (cond
      (= tok "(")
      (let [[expr resto2] (parse-expr resto)]
        (if (= (first resto2) ")")
          [expr (rest resto2)]
          (erro! "parentese sem fechar")))
      
      :else
      (parse-comparacao tokens))))

(defn parse-nao [[tok & resto :as tokens]]
  (if (= tok "nao")
    (let [[expr resto2] (parse-nao resto)]
      [[:nao expr] resto2])
    (parse-primario tokens)))

(defn parse-e [tokens]
  (loop [[esq resto] (parse-nao tokens)]
    (if (= (first resto) "e")
      (let [[dir resto2] (parse-nao (rest resto))]
        (recur [[:e esq dir] resto2]))
      [esq resto])))

(defn parse-ou [tokens]
  (loop [[esq resto] (parse-e tokens)]
    (if (= (first resto) "ou")
      (let [[dir resto2] (parse-e (rest resto))]
        (recur [[:ou esq dir] resto2]))
      [esq resto])))

(defn parse-expr [tokens]
  (parse-ou tokens))

;; Analisa os blocos como `limitar 2` ou `onde x = 1`
(defn parse-estagio [tokens]
  (if (empty? tokens)
    (erro! "estagio vazio")
    (let [tok (first tokens)]
      (case tok
        "onde"
        (if (empty? (rest tokens))
          (erro! "falta expressao no onde")
          (let [[expr resto] (parse-expr (rest tokens))]
            (if (empty? resto)
              [:onde expr]
              (erro! "tokens extras apos expressao"))))
        
        "ordenar"
        (if (= (second tokens) "por")
          (let [campo (nth tokens 2 nil)
                quarto (nth tokens 3 nil)]
            (cond
              (nil? campo) (erro! "falta campo em ordenar")
              (= quarto "desc") [:ordenar-por (keyword campo) :desc]
              (nil? quarto) [:ordenar-por (keyword campo) :asc]
              :else (erro! "ordem invalida")))
          (erro! "falta \"por\""))

        "limitar"
        (let [n-str (second tokens)]
          (if n-str
            (try
              [:limitar (Long/parseLong n-str)]
              (catch Exception _ (erro! "numero esperado")))
            (erro! "numero esperado")))
        
        "selecionar"
        (let [campos (->> (rest tokens)
                          (remove #(= % ","))
                          (map keyword)
                          vec)]
          (if (empty? campos)
            (erro! "falta campos em selecionar")
            [:selecionar campos]))
        
        "contar" [:contar]

        "soma"
        (if-let [campo (second tokens)]
          [:soma (keyword campo)]
          (erro! "soma exige numero"))

        "media"
        (if-let [campo (second tokens)]
          [:media (keyword campo)]
          (erro! "media exige numero"))

        "agrupar"
        (if (= (second tokens) "por")
          (let [campo (nth tokens 2 nil)
                com-tok (nth tokens 3 nil)
                agreg-tok (nth tokens 4 nil)
                agreg-campo (nth tokens 5 nil)]
            (if (= com-tok "com")
              (case agreg-tok
                "contar" [:agrupar-por (keyword campo) [:contar]]
                "soma"   (if agreg-campo
                           [:agrupar-por (keyword campo) [:soma (keyword agreg-campo)]]
                           (erro! "falta campo na soma"))
                "media"  (if agreg-campo
                           [:agrupar-por (keyword campo) [:media (keyword agreg-campo)]]
                           (erro! "falta campo na media"))
                (erro! "agregacao invalida"))
              (erro! "falta \"com <agregacao>\"")))
          (erro! "falta \"por\""))

        (erro! "estagio desconhecido")))))

;; Valida se o usuario colocou um terminal (ex: contar) ou selecionar no meio da consulta (o que eh proibido)
(defn classificar-e-validar [estagios-ast]
  (let [finais #{:selecionar :contar :soma :media :agrupar-por}
        pos-final (first (keep-indexed #(when (finais (first %2)) %1) estagios-ast))]
    (when (and pos-final (< pos-final (dec (count estagios-ast))))
      (if (= (first (nth estagios-ast pos-final)) :selecionar)
        (erro! "selecionar tem que ser o ultimo")
        (erro! "o terminal tem que ser o ultimo")))
    
    (let [ultimo (last estagios-ast)
          e-terminal? (and ultimo (#{:contar :soma :media :agrupar-por} (first ultimo)))]
      (if e-terminal?
        {:estagios (vec (butlast estagios-ast))
         :terminal ultimo}
        {:estagios (vec estagios-ast)
         :terminal nil}))))