(ns consultas.main
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [consultas.core :as core]))

(defn formatar-valor [v]
  (cond
    (float? v) (String/format java.util.Locale/ROOT "%.2f" (to-array [v]))
    :else (str v)))

(defn processar-comando [comando esquema registros]
  (if (str/starts-with? comando "QUERY")
    (let [texto-consulta (str/trim (subs comando 5))
          ast (if (str/blank? texto-consulta)
                (core/consulta)
                (core/analisar texto-consulta))
          resultado (core/executar ast {:esquema esquema :registros registros})]
      
      (cond
        (:erros resultado) (println "ERRO:" (first (:erros resultado)))
        
        ;; Verifica se é um número (terminais como contar, soma, media) ANTES de testar empty?
        (number? resultado)
        (println (formatar-valor resultado))
        
        (empty? resultado) (println "(vazio)")
        
        ;; Caso do 'agrupar por'
        (and (seq? resultado) (vector? (first resultado)) (not (keyword? (first (first resultado)))))
        (doseq [[chave valor] resultado]
          (println (str (formatar-valor chave) "; " (formatar-valor valor))))
        
        ;; Caso normal: lista de registros projetados
        (seq? resultado)
        (doseq [linha resultado]
          (println (str/join "; " (map formatar-valor linha))))
        
        :else (println resultado)))
    (println "ERRO: comando desconhecido")))

(defn -main [& args]
  (if (empty? args)
    (println "Uso: clojure -M:run <arquivo.csv>")
    (let [caminho (first args)]
      (if-not (.exists (io/file caminho))
        (println "ERRO: arquivo nao encontrado:" caminho)
        (do
          (println "Carregando dados de:" caminho)
          (let [texto-csv (slurp caminho)
                dados (core/ler-dados texto-csv)]
            
            ;; O Laço do Terminal (responsável por I/O interativo)[cite: 6]
            (loop []
              (print "> ")
              (flush)
              (let [linha (read-line)]
                (when (and linha (not= (str/trim linha) "EXIT"))
                  (let [comando (str/trim linha)]
                    (if (str/blank? comando)
                      (println "ERRO: linha em branco")
                      (try
                        (processar-comando comando (:esquema dados) (:registros dados))
                        (catch clojure.lang.ExceptionInfo e
                          (println "ERRO:" (ex-message e)))
                        (catch Exception e
                          (println "ERRO:" (.getMessage e))))))
                  (recur))))))))))