# Resultados da demonstração

Ainda não há um relatório de execução medido nesta pasta. Para gerar o relatório com as três perguntas sintéticas, tentativas, citações, tempos observados e métricas Micrometer:

~~~bash
cd juris-rag
./scripts/dev-setup.sh
docker compose up -d --build
python3 scripts/demo.py
~~~

O script cria ultima-execucao.json depois de validar os resultados. Publique esse arquivo somente após uma execução real; não preencha métricas ou respostas manualmente.
