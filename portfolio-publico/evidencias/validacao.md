# Evidências de validação

## Checagens estáticas registradas

| Item | Método | Resultado |
| --- | --- | --- |
| Scripts Python | python3 -m compileall -q scripts | Passou. |
| Wrapper e setup shell | sh -n mvnw scripts/dev-setup.sh | Passou. |
| POM | Parse XML com xml.etree.ElementTree | Passou. |
| Compose, CI, configuração Spring e OpenAPI | Parse YAML com PyYAML | Passou. |
| PDFs sintéticos | pdfinfo e pdftotext | Contrato com 2 páginas; aditivo e documento do tenant B com 1 página cada; trechos de 30, 90, 60 e 7 dias conferidos. |

## Verificações pendentes

| Item | Estado |
| --- | --- |
| Build e testes JUnit/Testcontainers | Ainda não executados nesta entrega. |
| Integração com ElasticMQ pelo Compose | Ainda não executada nesta entrega. |
| Consultas da demonstração, Ollama e métricas | Ainda não executadas nesta entrega. |

Não há latências, cobertura ou resultados de respostas apresentados como medidos. Após executar localmente, consulte o README; o script da demo grava um relatório sintético em portfolio-publico/resultados/ultima-execucao.json.
