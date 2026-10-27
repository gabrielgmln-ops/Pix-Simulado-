# Pix Simulado

Serviço de pagamentos instantâneos **simulado**, inspirado no arranjo Pix, que usa a
**Ledger API** como motor de saldo. Projeto de estudo: dados sintéticos, sem
autenticação e sem integração real com o Banco Central.

## Stack

Kotlin · Spring Boot 3 · Java 21 · PostgreSQL 16 · Flyway · springdoc-openapi ·
JUnit 5 · Testcontainers · Docker Compose · GitHub Actions

## Roadmap

| Etapa | Escopo | Status |
|------:|--------|--------|
| 0 | Setup do projeto, infraestrutura e convenções | ✅ |
| 1 | Cadastro e validação de chaves Pix (CPF, e-mail, aleatória) | ⏳ |
| 2 | Geração e leitura de QR Code (payload EMV simplificado) | ⏳ |
| 3 | Transferência instantânea via Ledger com idempotência por EndToEndId | ⏳ |
| 4 | Devolução (lançamento inverso, original preservado) | ⏳ |
| 5 | Swagger completo, testes, cobertura e CI | ⏳ |

## Como executar

Pré-requisitos: JDK 21 e Docker.

```bash
cp .env.example .env            # ajuste senha do banco e chave da Ledger

# Opção A - tudo em contêiner
docker compose up -d --build

# Opção B - banco em contêiner, aplicação local
docker compose up -d db
set -a && source .env && set +a
./gradlew bootRun
```

| Recurso | URL |
|---------|-----|
| Swagger UI | http://localhost:8080/swagger-ui.html |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |
| Health | http://localhost:8080/actuator/health |

A Ledger API roda fora deste compose. Por padrão a aplicação a procura em
`http://localhost:8081` (ou `host.docker.internal:8081` dentro do contêiner);
altere com `LEDGER_BASE_URL`.

## Testes

```bash
./gradlew test    # unitários + integração (PostgreSQL real via Testcontainers)
```

Sem Docker disponível, os testes de integração são **ignorados** (não falham).
Relatório de cobertura: `build/reports/jacoco/test/html/index.html`.

## Configuração (variáveis de ambiente)

| Variável | Obrigatória | Padrão | Descrição |
|----------|:-----------:|--------|-----------|
| `DB_PASSWORD` | sim | — | Senha do PostgreSQL |
| `LEDGER_API_KEY` | sim | — | Credencial da Ledger API |
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` | não | `localhost` / `5432` / `pix` / `pix` | Conexão com o banco |
| `LEDGER_BASE_URL` | não | `http://localhost:8081` | URL da Ledger |
| `LEDGER_CONNECT_TIMEOUT` / `LEDGER_READ_TIMEOUT` | não | `2s` / `5s` | Timeouts da Ledger |
| `PIX_ISPB` | não | `99999999` | ISPB sintético da instituição (8 dígitos) |
| `PIX_NOME_INSTITUICAO` / `PIX_CIDADE` | não | `PIX SIMULADO SA` / `SAO PAULO` | Dados do recebedor no QR Code |
| `SERVER_PORT` | não | `8080` | Porta HTTP |

Segredos não têm valor padrão: se faltarem, a aplicação falha na inicialização.

## Convenções

- **Dinheiro**: sempre `BigDecimal` no código e `NUMERIC(19,2)` no banco. Nunca `Double`/`Float`.
- **Tempo**: `TIMESTAMPTZ` em UTC; o código obtém a hora por um `Clock` injetável.
- **Imutabilidade financeira**: transações nunca são apagadas ou editadas; devolução gera lançamento inverso.
- **Schema**: só o Flyway altera o banco (`ddl-auto: validate`); migrações aplicadas são imutáveis.
- **Erros**: respostas no formato Problem Details (RFC 9457).

## Estrutura

```
src/main/kotlin/br/com/pixsimulado/
├── PixSimuladoApplication.kt
├── config/          # propriedades, OpenAPI, Clock
└── shared/web/      # tratamento global de erros
src/main/resources/
├── application.yml
└── db/migration/    # scripts Flyway V<n>__descricao.sql
```
