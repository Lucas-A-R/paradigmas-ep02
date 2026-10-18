# Query Builder Funcional em Clojure

Este projeto implementa um motor de consultas funcional em Clojure capaz de ler dados de um arquivo CSV, transformar uma linguagem de consulta baseada em texto em código como dado (AST) e compilar essa estrutura em funções puras compostas.

## Nome Integrante
* Lucas Aguilhar Ribeiro


## Como Executar o Programa

O projeto utiliza o `deps.edn` para gerenciar a execução sem necessidade de dependências externas além da própria biblioteca padrão do Clojure.

Para iniciar o terminal interativo e carregar um arquivo CSV, utilize o comando abaixo na raiz do projeto:

```bash
clojure -M:run dados/filmes.csv
```

Após o programa ser carregado, o prompt `> ` será exibido, permitindo a digitação das consultas (iniciadas pela palavra `QUERY`) ou o encerramento do programa (digitando `EXIT`).

## A Forma da AST (Abstract Syntax Tree)

Seguindo o paradigma de que **código é dado**, a consulta de texto não gera funções imediatas, mas sim uma árvore sintática construída puramente com estruturas de dados imutáveis do Clojure: **mapas, vetores e palavras-chave (keywords)**.

A estrutura base é um mapa contendo duas chaves principais: `:estagios` (um vetor de vetores, onde cada elemento representa uma etapa do pipeline) e `:terminal` (um vetor representando a operação final de agregação, ou `nil` caso não exista).

**Exemplo:**
Para a consulta em texto:
`QUERY onde (ano >= 2000 ou genero = "drama") e nota > 8.0 | ordenar por nota desc | selecionar titulo, nota`

A AST gerada pelo nosso analisador sintático tem exatamente esta forma:

```clojure
{:estagios [[:onde [:e [:ou [:>= :ano 2000] 
                            [:= :genero "drama"]] 
                       [:> :nota 8.0]]]
            [:ordenar-por :nota :desc]
            [:selecionar [:titulo :nota]]]
 :terminal nil}
```

Nenhuma função (fn) habita essa estrutura, permitindo que ela seja impressa, manipulada ou comparada através da função `=` nativa do Clojure.

## Organização dos Namespaces

O projeto foi rigorosamente modularizado respeitando as cláusulas de pureza estipuladas. Abaixo, o papel de cada *namespace*:

* **`consultas.main`**: É o único local onde ocorrem efeitos colaterais (I/O). Gerencia a leitura do arquivo CSV do disco, a interação com o usuário via terminal e utiliza um laço funcional com `loop`/`recur` para manter o programa ativo sem derrubar o processo em caso de erros. Também cuida da formatação correta de saídas (ex: decimais com ponto).
* **`consultas.core`**: Atua como o núcleo do *Query Builder*. Contém as funções responsáveis por transformar o texto do CSV em uma estrutura de dados indexada (mapas). Também expõe o contrato principal do *builder* (`consulta`, `onde`, `selecionar`, `analisar`, `executar`), unindo a etapa de parsing à etapa de compilação sem corromper a imutabilidade da AST base.
* **`consultas.sintaxe`**: Responsável pela análise léxica e sintática (*Tokenization* e *Parsing* recursivo). Lê a string fornecida pelo usuário e a converte na AST descrita acima. Toda a lógica aqui é *stateless*, não utilizando variáveis de controle de posição; apenas consome tokens recursivamente e sinaliza falhas de sintaxe via `ex-info`.
* **`consultas.compilador`**: Transforma a AST em funções compostas. Avalia os campos da consulta contra o esquema do arquivo e usa um despacho rigoroso através de blocos `case` sem ramo padrão. Transforma os nós (ex: `[:limitar 3]`) em funções com aplicação parcial, garantindo que a consulta final seja um *pipeline* (`reduce` com `comp`) preparado para receber a massa de dados de uma só vez.