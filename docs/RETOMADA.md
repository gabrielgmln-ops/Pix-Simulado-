# Pix Simulado - nota de retomada

Atualizado em 28/10/2026, ao concluir a Etapa 4 no PC (Windows), vault `C:\SegundoCerebro`.

## Estado atual

**Etapas 0 a 4 prontas.** 151 testes verdes (unitários + integração com PostgreSQL
real via Testcontainers e a Ledger simulada por WireMock), cobertura de linhas de 96,3%.

| Etapa | Escopo | Status | Commit |
|---|---|---|---|
| 0 | Setup: Gradle, Flyway, Docker Compose, Testcontainers | ✅ | `1365cc1` |
| 1 | Chaves Pix (CPF, e-mail, aleatória) com validação | ✅ | `57f6e31` |
| 2 | QR Code: BR Code EMV + imagem PNG (ZXing) | ✅ | `b80c7e3` |
| 3 | Transferência via Ledger, idempotência por EndToEndId | ✅ | `96c9aa2` |
| 3+ | Correção anti-IDOR (dono da conta conferido na Ledger) | ✅ | `29cef4c` |
| 4 | Devolução (lançamento inverso, original preservado) | ✅ | último do `git log` |
| 5 | Swagger completo, cobertura, CI no GitHub Actions | ⏳ próxima | |

## Restaurar o projeto

```powershell
# Atenção ao hífen no fim do nome do repositório.
git clone https://github.com/gabrielgmln-ops/Pix-Simulado-.git pix-simulado
cd pix-simulado
copy .env.example .env     # ajustar DB_PASSWORD, LEDGER_USUARIO, LEDGER_SENHA, SERVER_PORT=8082
.\gradlew.bat test         # requer JDK 21 e Docker Desktop aberto
```

## Ambiente no PC (Windows)

- Portas: a 8080 é do AgentService e a 8081 é da Ledger API, então o Pix roda com `SERVER_PORT=8082`.
- O PostgreSQL do Pix usa a 5432, que estava livre em 28/10. Se a Ledger ocupar essa porta, usar `DB_PORT=5433`.
- O WireMock dos testes usa a porta fixa **18089**.
- `.env` nunca vai para o git.
- Se o Gradle disser "Unable to delete directory build\test-results", algum processo prendeu a pasta (o vault é observado pelo Obsidian): rodar `.\gradlew.bat --stop`, apagar `build\` e repetir.

## Etapa 3 - o que foi decidido e feito

- **V3:** `chave_pix.conta_id` passa a ser UUID, com cast estrito. Se houver dado fora do formato, a migração falha em vez de adulterá-lo.
- **V4:** tabela `pagamento_pix`, com PK `end_to_end_id` e CHECKs de formato, estado, valor positivo, contas distintas e "LIQUIDADO tem id da Ledger".
- **EndToEndId** gerado pelo Pix em `POST /api/v1/pagamentos/end-to-end-id`. Não é preciso reservá-lo no banco: o sufixo aleatório de 62^11 combinações vem de um `SecureRandom`. Vale 24 h e precisa trazer o ISPB da casa.
- **Idempotência:**
  - o hash SHA-256 é calculado sobre o pedido normalizado, sem consultar o banco, então a repetição é reconhecida mesmo que a chave tenha sido excluída depois;
  - a concorrência é resolvida com `INSERT ... ON CONFLICT DO NOTHING` mais `SELECT ... FOR UPDATE`.
- **Decisão consciente:** a chamada HTTP à Ledger acontece dentro da transação que segura o lock do pagamento. Isso serializa as repetições do mesmo E2E, ao custo de prender uma conexão do pool por até o read-timeout.
- **Estados:** `PENDENTE`, `LIQUIDADO`, `REJEITADO` e `INCERTO`.
  - Timeout, 5xx, 401 e o "Conflito de idempotencia" da Ledger viram INCERTO.
  - Os 4xx de negócio viram REJEITADO, gravado; a repetição devolve a mesma recusa (422).
- **Reconciliação:** a classe `ReconciliacaoPagamentos` reenvia os INCERTOS com a mesma `Idempotency-Key`, com intervalo e limite de tentativas. A repetição do cliente também reenvia.
- **Ledger:** HTTP Basic com usuário técnico OPERADOR (`LEDGER_USUARIO`, `LEDGER_SENHA`), substituindo a antiga `LEDGER_API_KEY`. A Ledger não expõe `codigo` nos erros, então o cliente distingue os 422 pelo `title` e pelo `detail`.
- **Cliente HTTP** em HTTP/1.1 explícito: o padrão do JDK tenta h2c, que o Jetty do WireMock cancela.
- **Testes:** 24 de API, entre eles timeout real, reconciliação, 5xx, 401, saldo insuficiente, QR com e sem valor e QR adulterado, além de 20 threads com o mesmo E2E resultando em uma única chamada à Ledger. Mais 13 unitários do EndToEndId.

## Correção anti-IDOR (achado da revisão de segurança, 28/10)

- **Falha:** o Pix debita como OPERADOR, que enxerga todas as contas. Sem checagem, qualquer
  um podia informar a conta de outra pessoa como origem.
- **Correção:** `AutorizacaoConta` exige HTTP Basic do titular e pergunta à Ledger, com a
  credencial dele, se ele enxerga a conta (`GET /api/v1/contas/{id}`). Fica fora da
  transação e vale também para as repetições.
  - Respostas: 401 sem credencial ou com credencial recusada, 403 para conta alheia, 503 com a Ledger fora do ar.
  - O GET de pagamento só responde a pagador ou recebedor; para terceiros, 404.
- **Débito:** continua com o OPERADOR, para a reconciliação funcionar sem a senha do cliente.
- **Testes:** 7 novos, de 401, credencial não-Basic, senha errada, conta alheia, repetição
  por terceiro, Ledger fora e consulta por pagador, recebedor e terceiro.

## Etapa 4 - devolução

- **V5:** tabela `devolucao_pix`, com FK para o pagamento original e os mesmos CHECKs da V4,
  mais o motivo (MD06, BE08 ou FR01). O original nunca muda, e `valorDevolvido` é a soma das
  devoluções LIQUIDADAS.
- **Identificador:** o `EndToEndId` ganhou o prefixo `D` para as devoluções, e o código de erro varia com o prefixo.
- **Base comum:** a nova `OperacaoLedger` (`@MappedSuperclass`) reúne estados e transições, e o
  `ExecutorLedger` envia e classifica a chamada. Pagamento e devolução usam os dois, então a regra
  "timeout é INCERTO" está escrita em um só lugar.
- **Limite:** a soma comprometida (tudo menos REJEITADO, incluindo INCERTO) não passa do valor pago.
  - A ordem dos locks é sempre pagamento → devolução, para não haver deadlock.
  - O id da devolução é conferido de novo depois do lock do pagamento, para tratar a corrida do mesmo id.
- **Prazo:** 90 dias desde a liquidação (`atualizado_em`, que não muda mais depois de LIQUIDADO).
- **Acesso:** só o recebedor devolve. O pagador recebe 403, e quem não é parte do pagamento recebe 404 (anti-enumeração).
- **Testes:** 18 de API em `DevolucaoPixApiTest`, entre eles 20 devoluções simultâneas de R$ 10 num
  Pix de R$ 100 (liquidam exatamente 10). Mais 2 unitários do prefixo D. A Ledger simulada e os
  helpers foram para a base `LedgerSimuladaTest`.

## Pendências

1. A Operação Cofre da Ledger (HTTP Basic, dono por conta) **continua sem commit** no
   `ledger-api`. Para testar de ponta a ponta contra a Ledger real, é preciso:
   - commitar e subir a Ledger;
   - criar na Ledger um usuário OPERADOR para o Pix e usuários CLIENTE donos das contas;
   - criar contas e depositar saldo.
2. **Etapa 5:** Swagger completo, meta de cobertura no Jacoco, CI no GitHub Actions
   (o Testcontainers funciona no runner `ubuntu-latest`).
