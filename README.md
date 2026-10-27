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
| 1 | Cadastro e validação de chaves Pix (CPF, e-mail, aleatória) | ✅ |
| 2 | Geração e leitura de QR Code (payload EMV simplificado) | ✅ |
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

## API - Chaves Pix (Etapa 1)

| Método | Rota | Descrição |
|--------|------|-----------|
| `POST` | `/api/v1/chaves` | Cadastra chave (`CPF`, `EMAIL` ou `ALEATORIA`) |
| `GET` | `/api/v1/chaves/{chave}` | Consulta chave ativa; o tipo é inferido do valor |
| `GET` | `/api/v1/chaves?contaId=` | Lista chaves ativas de uma conta |
| `DELETE` | `/api/v1/chaves/{chave}` | Exclusão lógica (o registro é preservado) |

```bash
curl -X POST localhost:8080/api/v1/chaves -H 'Content-Type: application/json' -d '{
  "tipo": "EMAIL", "valor": "fulano.sintetico@exemplo.com", "contaId": "conta-0001",
  "titular": { "nome": "Fulano Sintético", "cpf": "123.456.789-09" }
}'
```

Regras aplicadas:

1. Valor validado e **normalizado** por tipo: CPF com 11 dígitos (módulo 11, sequências repetidas rejeitadas), e-mail em minúsculas com até 77 caracteres, chave aleatória em UUID v4.
2. A chave aleatória é **gerada pelo servidor**; enviar `valor` resulta em 422.
3. Uma chave CPF precisa ser o CPF do próprio titular.
4. Cada conta pertence a um único titular.
5. No máximo **5 chaves ativas por conta** (`PIX_LIMITE_CHAVES_POR_CONTA`). O limite é protegido contra corrida por um advisory lock do PostgreSQL, travado por conta.
6. Unicidade global entre chaves ativas, garantida por índice único parcial. Uma chave excluída pode ser recadastrada.
7. O CPF do titular sai mascarado nas respostas (`***.456.789-**`). Uma chave duplicada não revela a quem pertence.

Erros seguem o RFC 9457 e trazem um `codigo` estável: `400` formato inválido · `404` não encontrada · `409` já cadastrada · `422` regra violada.

## API - QR Code (Etapa 2)

| Método | Rota | Descrição |
|--------|------|-----------|
| `POST` | `/api/v1/qrcodes` | Gera um BR Code estático para uma chave ativa e devolve o payload e o PNG em Base64 |
| `GET` | `/api/v1/qrcodes/imagem?payload=&tamanho=` | Renderiza um payload válido como `image/png` |
| `POST` | `/api/v1/qrcodes/leitura` | Lê um payload em texto (Pix Copia e Cola) |
| `POST` | `/api/v1/qrcodes/leitura/imagem` | Lê um QR Code enviado como imagem PNG ou JPEG (multipart, campo `arquivo`) |

```bash
curl -X POST localhost:8080/api/v1/qrcodes -H 'Content-Type: application/json' \
  -d '{"chave": "fulano.sintetico@exemplo.com", "valor": 25.90, "txid": "PEDIDO123"}'
```

<img src="docs/exemplo-qrcode.png" width="180" alt="QR Code de exemplo">

```
00020126620014br.gov.bcb.pix0126loja.sintetica@exemplo.com0210Pedido 123520400005303986540525.905802BR5919Loja Sintetica Ltda6009SAO PAULO62130509PEDIDO12363045AB5
```

O payload segue o padrão EMV (TLV) do BR Code estático. Os campos 00, 26 (GUI `br.gov.bcb.pix`, chave e descrição), 52, 53, 54, 58, 59, 60, 62 (txid) e 63 (CRC16-CCITT-FALSE) foram validados contra o exemplo oficial do manual do Bacen.

- **Valor**: `BigDecimal` com 2 casas. Um valor com mais casas é **recusado**, nunca arredondado.
- **Texto**: o nome e a cidade perdem os acentos (o BR Code é ASCII) e são cortados em 25 e 15 caracteres.
- **Leitura**: a leitura confere o CRC, então um QR adulterado é rejeitado com `QRCODE_CRC_INVALIDO`. Ela não restringe o tipo da chave, porque QRs de outras instituições podem trazer telefone ou CNPJ.
- **Fora do escopo**: QR dinâmico (campo 25, cobrança por URL).

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
| `PIX_LIMITE_CHAVES_POR_CONTA` | não | `5` | Máximo de chaves ativas por conta |
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
├── chave/           # Etapa 1: domínio, serviço e API de chaves Pix
├── qrcode/          # Etapa 2: BR Code (EMV/TLV, CRC16) e imagem PNG (ZXing)
└── shared/
    ├── domain/      # exceções de negócio (sealed)
    └── web/         # tratamento global de erros
src/main/resources/
├── application.yml
└── db/migration/    # scripts Flyway V<n>__descricao.sql
```
