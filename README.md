# Pix Simulado

[![CI](https://github.com/gabrielgmln-ops/pix-simulado/actions/workflows/ci.yml/badge.svg)](https://github.com/gabrielgmln-ops/pix-simulado/actions/workflows/ci.yml)

Serviço de pagamentos instantâneos **simulado**, inspirado no arranjo Pix, que usa a
**Ledger API** como motor de saldo. Projeto de estudo: dados sintéticos, sem
cadastro próprio de usuários (a autenticação é a da Ledger) e sem integração real com o Banco Central.

## Stack

Kotlin · Spring Boot 3 · Java 21 · PostgreSQL 16 · Flyway · springdoc-openapi ·
JUnit 5 · Testcontainers · Docker Compose · GitHub Actions

## Roadmap

| Etapa | Escopo | Status |
|------:|--------|--------|
| 0 | Setup do projeto, infraestrutura e convenções | ✅ |
| 1 | Cadastro e validação de chaves Pix (CPF, e-mail, aleatória) | ✅ |
| 2 | Geração e leitura de QR Code (payload EMV simplificado) | ✅ |
| 3 | Transferência instantânea via Ledger com idempotência por EndToEndId | ✅ |
| 4 | Devolução (lançamento inverso, original preservado) | ✅ |
| 5 | Swagger completo, testes, cobertura e CI | ✅ |

## Decisões de arquitetura (resumo para entrevista)

| Problema | Decisão |
|----------|---------|
| Cliente reenvia o pedido após um timeout | Idempotência pelo EndToEndId: mesma resposta, nenhum novo débito |
| Duas requisições iguais no mesmo milissegundo | `INSERT ... ON CONFLICT DO NOTHING` na PK + `SELECT ... FOR UPDATE` |
| A Ledger não respondeu: debitou ou não? | Estado `INCERTO` + reenvio com a **mesma** `Idempotency-Key`; nunca "falha" |
| Devolver sem apagar o histórico | Lançamento inverso; `valorDevolvido` = soma das devoluções, como o saldo derivado |
| Devoluções simultâneas furando o valor pago | Lock no pagamento antes de somar; INCERTO conta no limite |
| Alguém debitar a conta de outro (IDOR) | Titularidade conferida na Ledger com a credencial do próprio cliente |
| Sequestro de chave (apagar e recadastrar na própria conta) | Escrita e listagem de chaves só pelo dono da conta |
| Dinheiro | `BigDecimal` / `NUMERIC(19,2)`; fração de centavo é recusada, nunca arredondada |

## Como demonstrar em 5 minutos

Com a Ledger API na porta 8081, um usuário OPERADOR para o Pix e dois clientes
com contas e saldo (a loja dona da chave):

```bash
export CLIENTE_USUARIO=... CLIENTE_SENHA=... LOJA_USUARIO=... LOJA_SENHA=...
B=localhost:8082/api/v1

# 1. A loja cadastra a chave e gera um QR Code de R$ 25,90
curl -s -u "$LOJA_USUARIO:$LOJA_SENHA" -X POST $B/chaves -H 'Content-Type: application/json' -d '{"tipo":"EMAIL","valor":"loja@exemplo.com",
  "contaId":"<conta-da-loja>","titular":{"nome":"Loja Sintetica","cpf":"123.456.789-09"}}'
QR=$(curl -s -X POST $B/qrcodes -H 'Content-Type: application/json' \
  -d '{"chave":"loja@exemplo.com","valor":25.90}' | jq -r .payload)

# 2. O cliente paga o QR; repetir o mesmo comando devolve Idempotent-Replayed: true
E2E=$(curl -s -X POST $B/pagamentos/end-to-end-id | jq -r .endToEndId)
curl -i -u "$CLIENTE_USUARIO:$CLIENTE_SENHA" -X POST $B/pagamentos -H 'Content-Type: application/json' \
  -d "{\"endToEndId\":\"$E2E\",\"contaOrigemId\":\"<conta-do-cliente>\",\"qrPayload\":\"$QR\"}"

# 3. A loja devolve R$ 5,00; o pagamento original continua intacto
ID=$(curl -s -X POST $B/pagamentos/$E2E/devolucoes/id | jq -r .idDevolucao)
curl -s -u "$LOJA_USUARIO:$LOJA_SENHA" -X POST $B/pagamentos/$E2E/devolucoes -H 'Content-Type: application/json' \
  -d "{\"idDevolucao\":\"$ID\",\"valor\":5.00,\"motivo\":\"MD06\"}"
curl -s -u "$CLIENTE_USUARIO:$CLIENTE_SENHA" $B/pagamentos/$E2E | jq '{estado, valor, valorDevolvido}'
```

### Teste de ponta a ponta contra a Ledger real

`scripts/e2e-ledger-real.sh` executa o roteiro acima com contas novas e **16 verificações**:
anti-IDOR e anti-sequestro de chave, pagamento por QR, idempotência devolvendo a mesma
transação da Ledger, saldo insuficiente, devolução parcial e os saldos conferidos na
própria Ledger a cada passo. Validado em 29/10/2026: 16 de 16.

## Como executar

Pré-requisitos: JDK 21 e Docker.

```bash
cp .env.example .env            # ajuste a senha do banco e o usuário/senha da Ledger

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
  "tipo": "EMAIL", "valor": "fulano.sintetico@exemplo.com", "contaId": "3f2a1b4c-5d6e-4f70-8a9b-0c1d2e3f4a5b",
  "titular": { "nome": "Fulano Sintético", "cpf": "123.456.789-09" }
}'
```

Regras aplicadas:

0. `contaId` é o **UUID da conta na Ledger API** (migração V3).
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

## API - Pagamentos (Etapa 3)

| Método | Rota | Descrição |
|--------|------|-----------|
| `POST` | `/api/v1/pagamentos/end-to-end-id` | Gera o EndToEndId de um novo pagamento |
| `POST` | `/api/v1/pagamentos` | Paga por `chave` **ou** `qrPayload` (idempotente pelo EndToEndId) |
| `GET` | `/api/v1/pagamentos/{endToEndId}` | Consulta o pagamento em qualquer estado |

```bash
E2E=$(curl -s -X POST localhost:8082/api/v1/pagamentos/end-to-end-id | jq -r .endToEndId)
curl -i -u "$CLIENTE_USUARIO:$CLIENTE_SENHA" -X POST localhost:8082/api/v1/pagamentos -H 'Content-Type: application/json' -d "{
  \"endToEndId\": \"$E2E\", \"contaOrigemId\": \"7d1e2f3a-4b5c-4d6e-8f70-9a1b2c3d4e5f\",
  \"chave\": \"loja.sintetica@exemplo.com\", \"valor\": 25.90, \"descricao\": \"Pedido 123\"
}"
```

**EndToEndId** no formato do Bacen, com 32 caracteres: `E` + ISPB (8) + `yyyyMMddHHmm` UTC + 11 alfanuméricos de um `SecureRandom`. O Pix gera o identificador, que vale por 24 h (a mesma janela de idempotência da Ledger), e o envia à Ledger como `Idempotency-Key`.

**Estados**

| Estado | Significado | HTTP |
|--------|-------------|------|
| `LIQUIDADO` | A Ledger gravou a transferência; a resposta traz o `ledgerTransacaoId` | `201` |
| `REJEITADO` | A Ledger recusou com certeza (saldo, conta inexistente); nada foi debitado | `422` com `codigo` |
| `INCERTO` | Houve timeout, 5xx ou 401 da Ledger: não se sabe se debitou | `202` |

**Idempotência**

- **Mesmo pedido repetido:** devolve o mesmo resultado, com o cabeçalho `Idempotent-Replayed: true` e sem nova movimentação.
- **Mesmo EndToEndId com dados diferentes:** `422 END_TO_END_ID_JA_UTILIZADO`.
- **Concorrência:** a garantia é `INSERT ... ON CONFLICT DO NOTHING` na chave primária mais `SELECT ... FOR UPDATE`. Com 20 requisições simultâneas, a Ledger recebe exatamente uma chamada.

**Resultado incerto.** Um pagamento INCERTO nunca é tratado como falha. A reconciliação (`@Scheduled`, a cada `LEDGER_RECONCILIACAO_INTERVALO`) e a repetição do próprio cliente reenviam o pedido com a **mesma** `Idempotency-Key`. A Ledger então devolve a transação já gravada ou executa pela primeira vez, mas nunca debita duas vezes. Depois de `LEDGER_RECONCILIACAO_MAX_TENTATIVAS`, o pagamento fica INCERTO à espera de análise manual.

**QR Code.** O payload é lido com a mesma rotina da Etapa 2, que confere o CRC. Se o QR traz valor, esse valor prevalece: informar outro dá `422 VALOR_QR_DIVERGENTE`. Se o QR não tem valor, o campo `valor` é obrigatório.

**Chaves (anti-sequestro).** Cadastrar, excluir e listar chaves exigem HTTP Basic do **dono da conta**, conferido na Ledger: sem isso, um atacante apagaria a chave da loja e a recadastraria na própria conta, desviando os Pix. A consulta de uma chave segue pública, como no DICT.

**Controle de acesso (anti-IDOR).** Pagar e consultar exigem **HTTP Basic do titular**, que é o mesmo usuário dele na Ledger.
- **Pagamento:** antes de qualquer efeito, inclusive numa repetição, o Pix pergunta à Ledger, com a credencial do cliente, se ele enxerga a conta de origem (`GET /api/v1/contas/{id}`). A Ledger só mostra a um CLIENTE as próprias contas. As respostas possíveis são:
  - sem credencial ou credencial recusada: `401 NAO_AUTENTICADO`;
  - conta alheia: `403 ACESSO_NEGADO`;
  - Ledger fora do ar: `503 LEDGER_INDISPONIVEL`, sem nada gravado.
- **Consulta:** só o pagador ou o recebedor vê o pagamento. Para terceiros, a resposta é `404`, como se ele não existisse.
- **Débito:** a transferência em si usa o usuário técnico de papel OPERADOR do Pix (`LEDGER_USUARIO`, `LEDGER_SENHA`). É isso que permite reconciliar um INCERTO mais tarde, sem a credencial do cliente; a titularidade já foi conferida antes da reserva.
- **Por que assim:** o Pix não guarda senhas nem um cadastro paralelo de donos, porque a regra continua em um lugar só, a Ledger.

Códigos estáveis da etapa: `END_TO_END_ID_INVALIDO`, `END_TO_END_ID_DE_OUTRA_INSTITUICAO`, `END_TO_END_ID_EXPIRADO`, `END_TO_END_ID_JA_UTILIZADO`, `DESTINO_INVALIDO`, `VALOR_OBRIGATORIO`, `VALOR_QR_DIVERGENTE`, `MESMA_CONTA`, `NAO_AUTENTICADO`, `ACESSO_NEGADO`, `LEDGER_INDISPONIVEL`, `SALDO_INSUFICIENTE`, `CONTA_NAO_ENCONTRADA`, `LEDGER_RECUSOU`, `PAGAMENTO_NAO_ENCONTRADO`. Devolução: `ID_DEVOLUCAO_INVALIDO`, `ID_DEVOLUCAO_JA_UTILIZADO`, `ID_DEVOLUCAO_EXPIRADO`, `PAGAMENTO_NAO_LIQUIDADO`, `PRAZO_DEVOLUCAO_EXPIRADO`, `VALOR_DEVOLUCAO_EXCEDIDO`.

## API - Devoluções (Etapa 4)

| Método | Rota | Descrição |
|--------|------|-----------|
| `POST` | `/api/v1/pagamentos/{e2e}/devolucoes/id` | Gera o identificador da devolução (prefixo `D`) |
| `POST` | `/api/v1/pagamentos/{e2e}/devolucoes` | Devolve, total ou parcialmente (idempotente pelo id) |
| `GET` | `/api/v1/pagamentos/{e2e}/devolucoes` | Lista as devoluções do pagamento |

```bash
ID=$(curl -s -X POST localhost:8082/api/v1/pagamentos/$E2E/devolucoes/id | jq -r .idDevolucao)
curl -i -u "$LOJA_USUARIO:$LOJA_SENHA" -X POST localhost:8082/api/v1/pagamentos/$E2E/devolucoes \
  -H 'Content-Type: application/json' -d "{\"idDevolucao\": \"$ID\", \"valor\": 10.00, \"motivo\": \"MD06\"}"
```

- **Lançamento inverso:** a devolução é uma **nova transferência na Ledger**, do recebedor para o pagador.
  - O pagamento original nunca é alterado nem apagado.
  - O `valorDevolvido` do pagamento é sempre a soma das devoluções liquidadas, como o saldo da Ledger é a soma dos lançamentos.
- **Identificador:** mesmo formato do E2E, mas com prefixo `D` (`D` + ISPB + `yyyyMMddHHmm` + 11 alfanuméricos). Ele é o `Idempotency-Key` na Ledger, então nunca colide com o pagamento.
- **Limite:** pode haver várias devoluções parciais, mas a soma **comprometida** nunca passa do valor pago (`422 VALOR_DEVOLUCAO_EXCEDIDO`).
  - "Comprometida" inclui as devoluções INCERTAS: se não se sabe se devolveu, conta-se que devolveu.
  - O pagamento é travado (`SELECT ... FOR UPDATE`) antes da soma. Com 20 devoluções simultâneas de R$ 10 num Pix de R$ 100, liquidam exatamente 10.
- **Prazo:** 90 dias contados da liquidação (`PIX_DEVOLUCAO_PRAZO`). Depois disso, `422 PRAZO_DEVOLUCAO_EXPIRADO`.
- **Só pagamento LIQUIDADO:** qualquer outro estado dá `422 PAGAMENTO_NAO_LIQUIDADO`.
- **Quem pode:** só o **recebedor** devolve. O pagador recebe `403`, e terceiros recebem `404`. Pagador e recebedor podem listar.
- **Motivos:** `MD06` (pedido do cliente), `BE08` (erro operacional) e `FR01` (fraude), inspirados no ISO 20022.
- **Recusa e incerteza:** recebedor sem saldo dá `422 SALDO_INSUFICIENTE`; a devolução fica REJEITADA e não conta no limite. Um timeout deixa a devolução INCERTA, e a mesma reconciliação dos pagamentos resolve.

## Testes

```bash
./gradlew check   # testes (PostgreSQL real via Testcontainers, Ledger via WireMock) + meta de cobertura
```

Sem Docker disponível, os testes de integração são **ignorados** (não falham).
Relatório de cobertura: `build/reports/jacoco/test/html/index.html`.

Estado em 29/10/2026: **201 testes verdes**, **96,4% de linhas** e **83,9% de ramos** cobertos.
O `check` **falha** abaixo de 90% de linhas ou 80% de ramos (Jacoco).
A Ledger é simulada por um WireMock de verdade, o que permite provocar timeouts reais.

## Integração contínua

O workflow `.github/workflows/ci.yml` roda a cada push e pull request na `main`, com token de permissão só de leitura:

| Job | O que garante |
|-----|---------------|
| **Testes e cobertura** | `./gradlew check` no Ubuntu com JDK 21. O PostgreSQL real sobe pelo Testcontainers e a meta de cobertura é aplicada. Os relatórios ficam como artefato, e o resumo do job mostra a cobertura. |
| **Imagem Docker** | O `Dockerfile` multi-estágio continua construindo. Nada é publicado. |
| **Varredura de segredos** | Gitleaks no histórico inteiro. Os dois falsos positivos antigos (exemplos `curl -u` com usuários sintéticos) estão listados, com justificativa, no `.gitleaksignore`. |

## Configuração (variáveis de ambiente)

| Variável | Obrigatória | Padrão | Descrição |
|----------|:-----------:|--------|-----------|
| `DB_PASSWORD` | sim | — | Senha do PostgreSQL |
| `LEDGER_USUARIO` / `LEDGER_SENHA` | sim | — | Usuário técnico (OPERADOR) da Ledger, por HTTP Basic |
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` | não | `localhost` / `5432` / `pix` / `pix` | Conexão com o banco |
| `LEDGER_BASE_URL` | não | `http://localhost:8081` | URL da Ledger |
| `LEDGER_CONNECT_TIMEOUT` / `LEDGER_READ_TIMEOUT` | não | `2s` / `5s` | Timeouts da Ledger (estourou = `INCERTO`) |
| `LEDGER_RECONCILIACAO_HABILITADA` / `_INTERVALO` / `_MAX_TENTATIVAS` | não | `true` / `30s` / `10` | Reenvio dos pagamentos incertos |
| `PIX_ISPB` | não | `99999999` | ISPB sintético da instituição (8 dígitos) |
| `PIX_NOME_INSTITUICAO` / `PIX_CIDADE` | não | `PIX SIMULADO SA` / `SAO PAULO` | Dados do recebedor no QR Code |
| `PIX_LIMITE_CHAVES_POR_CONTA` | não | `5` | Máximo de chaves ativas por conta |
| `PIX_DEVOLUCAO_PRAZO` | não | `90d` | Prazo para devolver, contado da liquidação |
| `SERVER_PORT` | não | `8080` | Porta HTTP (no PC do autor: `8082`) |

Segredos não têm valor padrão: se faltarem, a aplicação falha na inicialização.

## Convenções

- **Dinheiro**: sempre `BigDecimal` no código e `NUMERIC(19,2)` no banco. Nunca `Double`/`Float`.
- **Tempo**: `TIMESTAMPTZ` em UTC; o código obtém a hora por um `Clock` injetável.
- **Imutabilidade financeira**: transações nunca são apagadas ou editadas; devolução gera lançamento inverso.
- **Schema**: só o Flyway altera o banco (`ddl-auto: validate`); migrações aplicadas são imutáveis.
- **Erros**: respostas no formato Problem Details (RFC 9457), documentadas no Swagger com o schema `ErroResponse`.
- **Swagger**: o `basicAuth` é exigido só nas rotas de pagamento e devolução. Um teste (`OpenApiContratoTest`) trava o contrato publicado.

## Estrutura

```
src/main/kotlin/br/com/pixsimulado/
├── PixSimuladoApplication.kt
├── config/          # propriedades, OpenAPI, Clock
├── chave/           # Etapa 1: domínio, serviço e API de chaves Pix
├── qrcode/          # Etapa 2: BR Code (EMV/TLV, CRC16) e imagem PNG (ZXing)
├── pagamento/       # Etapas 3 e 4: EndToEndId, pagamento e devolução idempotentes, reconciliação
├── ledger/          # Etapa 3: cliente HTTP da Ledger API
└── shared/
    ├── domain/      # exceções de negócio (sealed)
    └── web/         # tratamento global de erros
src/main/resources/
├── application.yml
└── db/migration/    # scripts Flyway V<n>__descricao.sql
```
