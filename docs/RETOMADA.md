# Pix Simulado - nota de retomada

Estado em 2026-10-27: **Etapas 0, 1 e 2 concluídas**; 85 testes verdes (unitários + integração com Testcontainers).

## Restaurar o projeto

```bash
git clone pix-simulado.bundle pix-simulado
cd pix-simulado
git remote remove origin            # o bundle aparece como "origin"
git remote add origin git@github.com:<usuario>/pix-simulado.git
git push -u origin main
./gradlew test                      # requer JDK 21 e Docker
```

## Etapas

| Etapa | Status | Commit |
|---|---|---|
| 0 Setup | ✅ | `69fa00d` |
| 1 Chaves Pix | ✅ | `5d1617d` |
| 2 QR Code (BR Code + PNG) | ✅ | `c453c4e` |
| 3 Transferência via Ledger + idempotência por EndToEndId | ⏳ próxima |
| 4 Devolução (lançamento inverso) | ⏳ |
| 5 Swagger, cobertura, CI (GitHub Actions) | ⏳ |

## Pendente para a Etapa 3: contrato da Ledger API

1. Endpoints de lançamento (débito/crédito ou transferência atômica) + formato de request/response.
2. Cabeçalho de autenticação (onde vai `LEDGER_API_KEY`).
3. A Ledger aceita chave de idempotência? (enviaremos o EndToEndId)
4. Respostas para saldo insuficiente e conta inexistente.

Alternativa: contrato mínimo proposto, atrás de uma interface `LedgerClient`.

## Decisões já tomadas

- Kotlin 2.1 + Spring Boot 3.5.6 + Java 21; Testcontainers fixado em 1.21.4 (Docker 29).
- Dinheiro: BigDecimal / NUMERIC(19,2); mais de 2 casas é recusado, nunca arredondado.
- Exclusão lógica de chaves; índice único parcial só entre ATIVAS.
- Limite de 5 chaves por conta, protegido por advisory lock do PostgreSQL.
- Erros RFC 9457 com `codigo` estável; exceções de negócio em hierarquia sealed.
- QR Code estático não é persistido; leitura confere CRC16 e não restringe o tipo da chave.
- A cidade no QR é a da instituição (`PIX_CIDADE`).
