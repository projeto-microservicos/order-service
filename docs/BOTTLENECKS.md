# Gargalos: implementação e validação

Referência: [exercício Gargalos — Insper 2026.2](https://insper.github.io/platform/pt/2026.2/exercises/bottlenecks/).

| Área | Situação anterior | Implementação neste serviço |
|---|---|---|
| Cache | Toda leitura de detalhe consultava PostgreSQL | Redis armazena snapshots imutáveis em USD com TTL e chave por conta/pedido; falhas usam o banco |
| Observabilidade | Actuator e endpoint Prometheus já existentes | Coleta Prometheus, datasource e dashboard Grafana provisionados; métricas de cache e entrega de eventos |
| Mensageria | Nenhum evento assíncrono | `OrderCreated` gravado atomicamente com o pedido e publicado pelo relay no RabbitMQ |
| Balanceamento | Service Kubernetes com uma réplica | Service pronto para múltiplas réplicas e HPA opcional com 2–6; cluster atual permanece com uma réplica |
| Vulnerabilidades | CI sem bloqueio explícito por CVE | Trivy inspeciona dependências do JAR; HIGH/CRITICAL interrompem o GitHub Actions CI/CD |
| Pagamentos | Ausente | Stripe PaymentIntent com total calculado no servidor, idempotência persistida, consulta de status e tratamento de falhas |
| Autenticação/autorização | Header confiável do gateway e consultas por proprietário já existentes | Modo OAuth2 JWT opcional com validação de assinatura, emissor, expiração, audiência e scopes |

Também foram encurtadas as transações: produto, câmbio, cache, RabbitMQ e Stripe são acessados fora das transações do banco. A listagem usa projeção de resumo, e o pool tem limite e tempo de espera configuráveis.

## Executar localmente

Requisitos: JDK 25, Maven 3.9+, Docker Compose, rede externa `store-dev` e serviços de produto/câmbio nessa rede ou URLs equivalentes.

```bash
python scripts/init-env.py --update
# Somente se ainda não existir:
docker network create store-dev
docker compose --profile monitoring up --build -d --wait --wait-timeout 300
```

O script gera senhas distintas para PostgreSQL, RabbitMQ e Grafana. `--update` acrescenta configurações ausentes em um `.env` existente sem alterar valores anteriores. Os volumes preservam os dados; alterar uma senha no `.env` não altera automaticamente credenciais de um volume já inicializado.

API: `http://localhost:8082`; Prometheus: `http://localhost:9090`; Grafana: `http://localhost:3000`, usuário `admin`, senha `GRAFANA_PASSWORD`. O dashboard **Order service** mostra vazão, p95, erros 5xx, espera por conexão do banco, cache e eventos. Sem `--profile monitoring`, apenas aplicação, PostgreSQL, Redis e RabbitMQ são iniciados.

## Cache

`CACHE_ENABLED=true` habilita snapshots de `GET /orders/{id}`. `CACHE_TTL=5m` limita a permanência. Configure `REDIS_HOST`, `REDIS_PORT` e, quando necessário, `REDIS_PASSWORD` fora do Compose. Timeout de leitura/conexão: 500 ms.

A chave inclui a identidade validada e o ID do pedido. Um resultado de outra conta nunca serve como hit. Listagens e erros não são cacheados; a criação já popula o snapshot do proprietário. A conversão usa a cotação da requisição, e valores convertidos não substituem o snapshot USD. A política `allkeys-lru` limita a memória do Redis no Compose.

Desligar o Redis deve manter as leituras funcionando pelo PostgreSQL e aumentar `orders_cache_errors_total`. Requisições repetidas ao mesmo detalhe aumentam `orders_cache_requests_total{result="hit"}`. Os pedidos atualmente são imutáveis; futuras operações de edição/cancelamento devem invalidar ou versionar o snapshot.

## Mensageria

`MESSAGING_ENABLED=true` habilita a outbox. Configure `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USERNAME` e `RABBITMQ_PASSWORD`. Pedidos criados com a opção desligada não geram eventos retroativos.

A transação grava pedido, itens e evento em `orders.order_outbox`. Um relay busca lotes de até 20 eventos e usa leases de 30 segundos para coordenar réplicas. A chamada ao broker não mantém conexão nem lock do banco. O evento só é marcado publicado depois de confirmação positiva do broker e verificação de roteamento. Falhas usam backoff limitado a cinco minutos; eventos não publicados permanecem no banco. Eventos publicados são removidos após sete dias.

Contrato: exchange direto durável `store.orders`, routing key `created`, fila durável `orders.created`, mensagens JSON persistentes com `message_id=eventId`. O JSON contém `eventId`, `type=OrderCreated`, `orderId`, `accountId`, `createdAt`, `currency=USD` e `total`. Serviços de notificação/faturamento podem consumir essa fila sem entrar no caminho HTTP de criação de pedidos. Consumidores que precisem receber todos os eventos independentemente devem declarar sua própria fila/binding.

A entrega é **ao menos uma vez**: uma queda depois do ACK e antes da gravação no banco pode repetir o evento. Consumidores devem deduplicar por `eventId`, confirmar somente após seu trabalho concluir e rejeitar falhas definitivas sem requeue. A fila possui dead-letter routing para `orders.created.dlq`; a política de tentativas do consumidor precisa ser definida no serviço consumidor.

Para observar eventos localmente, use a interface de administração dentro do contêiner ou os comandos do RabbitMQ; não há porta de administração pública. Consulte a outbox e as métricas `orders_outbox_deliveries_total` para distinguir publicações e novas tentativas. Durante indisponibilidade do broker, novos pedidos continuam sendo persistidos e acumulam eventos para envio posterior.

## Balanceamento no Kubernetes

`k8s/k8s.yaml` inicia uma réplica por restrição de capacidade, com cache e mensageria explicitamente desligados. O rollout usa `maxSurge=0` e `maxUnavailable=1`, evitando um Pod extra e aceitando indisponibilidade breve. Mantenha o HPA ausente nesta fase. Quando houver capacidade aprovada, aumente as réplicas e revise a estratégia de atualização. O Service `order` distribui conexões entre Pods prontos, e probes retiram Pods indisponíveis. O balanceamento é por conexão; uma única conexão HTTP persistente pode continuar no mesmo Pod. Dimensione o banco considerando `DATABASE_POOL_SIZE` por réplica (padrão 10).

O Compose mantém uma instância da aplicação e permite validar Redis/RabbitMQ localmente. Para autoscaling futuro no cluster, após ampliar a capacidade, instale metrics-server e aplique separadamente:

```bash
kubectl -n store apply -f k8s/hpa.yaml
kubectl -n store get hpa order
kubectl -n store get endpointslice -l kubernetes.io/service-name=order
```

O HPA busca CPU média de 70%, com 2–6 réplicas e estabilização de redução de cinco minutos. Quando o HPA administrar a aplicação, remova `spec.replicas` do Deployment para evitar interferência entre configuração declarativa e autoscaling. O GitHub Actions publica apenas Deployment/Services; HPA e dependências são administrados separadamente. Cache/mensageria no cluster dependem também da remoção dos overrides do Deployment; Redis/RabbitMQ serão executados no próprio cluster, sem ElastiCache/Amazon MQ.

## Varredura de vulnerabilidades

```bash
mvn -B -ntp verify
bash scripts/scan.sh
```

O script usa Trivy instalado no agente ou a imagem fixada `aquasec/trivy:0.76.2`. A primeira execução exige internet para baixar a ferramenta e as bases CVE/Java. A varredura inspeciona os JARs de runtime empacotados, incluindo bibliotecas aninhadas do Spring Boot. O relatório fica em `artifacts/vulnerabilities.json`. HIGH/CRITICAL ou falha de execução bloqueiam a pipeline; nenhuma CVE é suprimida automaticamente.

O modo `rootfs` ativa a análise de arquivos Java empacotados. O script exige um inventário de bibliotecas no relatório, impedindo aprovação silenciosa por uma análise vazia. As bases ficam no cache ignorado `artifacts/.trivy-cache`.

O GitHub Actions executa a varredura antes de publicar no ECR e arquiva o relatório mesmo em falha. Isso verifica dependências da aplicação. A configuração de scanning do ECR pode complementar a análise com a imagem publicada; alinhe sua ativação e custo com o responsável pela conta. Veja [GITHUB-ACTIONS-EKS.md](GITHUB-ACTIONS-EKS.md) para configurar a pipeline. Atualize dependências e repita o scan quando surgirem vulnerabilidades; a aprovação de uma execução não garante ausência futura de CVEs.

## JWT e autorização

O modo padrão continua confiando no header `id-account` definido pelo gateway em uma rede privada. Esse header deve ser um UUID válido. Para validar tokens também neste serviço:

```dotenv
JWT_ENABLED=true
JWT_ISSUER_URI=https://SEU_PROVEDOR/realms/store
JWT_AUDIENCE=order-service
```

O emissor deve expor os metadados OAuth2/OIDC e JWKS. A aplicação verifica assinatura, emissor, validade temporal e audiência. `sub` deve ser o UUID da conta. O token precisa conter `orders:read` para GET e `orders:write` para POST, em `scope` ou `scp`. Envie `Authorization: Bearer ...`. Headers de identidade enviados pelo cliente são ignorados nesse modo; consultas continuam filtradas por conta. Sem token ou com token inválido: 401; sem scope: 403; pedido de outra conta: 404. Os tokens precisam ser aceitos também pelas dependências conforme a política do gateway; este serviço encaminha a identidade da conta para produto/câmbio na rede interna.

`GET /orders/health-check` fica aberto mesmo com JWT (probes de startup/liveness e healthcheck do Compose). Readiness e Prometheus continuam na porta privada 9091. O serviço não cria usuários, emite tokens nem altera o gateway existente.

## Pagamentos Stripe

Configure primeiro uma conta Stripe em modo de teste:

```dotenv
PAYMENTS_ENABLED=true
STRIPE_SECRET_KEY=sk_test_SUBSTITUA_LOCALMENTE
```

Nunca versione a chave; no Kubernetes ela pertence a `order-secrets`. A aplicação usa a API Stripe `2026-10-28.endive`. Os endpoints exigem a mesma identidade/autorização dos pedidos:

| Operação | Comportamento |
|---|---|
| `POST /orders/{id}/payment` | Cria ou recupera o PaymentIntent do pedido; 200 com `id`, `clientSecret`, `status`, `amount` e `currency` |
| `GET /orders/{id}/payment` | Consulta o estado atual diretamente na Stripe; 404 se ainda não iniciado |

O corpo de criação não aceita valor ou moeda. O total salvo em USD é convertido exatamente para centavos e validado entre US$ 0,50 e US$ 999.999,99. O frontend usa `clientSecret` com Stripe.js/Elements para coletar e confirmar o cartão diretamente na Stripe. Este backend não recebe dados de cartão. Respostas de pagamento usam `Cache-Control: no-store`; não registre o `clientSecret` em logs ou URLs.

Uma reserva persistente por pedido fornece a chave idempotente antes da chamada externa. Chamadas concorrentes usam a mesma reserva. O ID do PaymentIntent é salvo e reutilizado nas requisições futuras, inclusive depois da janela de idempotência da Stripe. Se houver timeout/erro, repita o POST: ele conserva a chave e retorna 503 enquanto o provedor estiver indisponível. Como a Stripe pode descartar chaves após 24 horas, tentativas sem ID persistido são bloqueadas com 409 depois de 23 horas e exigem reconciliação no provedor. Nenhuma nova tentativa é criada automaticamente nessa situação.

Um PaymentIntent criado não significa pagamento concluído. Somente o status `succeeded` consultado na Stripe indica sucesso; `requires_payment_method`, `requires_action`, `processing` e outros estados devem ser tratados pelo frontend. O pedido não é marcado como pago por um status enviado pelo cliente. O projeto não executa expedição nem implementa consumidor de webhook; a integração entrega criação/confirmação no frontend e consulta autenticada de status.

## Verificação automatizada

`mvn -B -ntp verify` executa testes HTTP originais e cenários de isolamento do cache, TTL, cache indisponível/corrompido, rollback pedido/evento, leases entre réplicas, ACK/roteamento, repetição após falha do broker, identidade JWT, scopes, pagamento idempotente, falhas do provedor e proteção de propriedade. H2, HTTP stubs e mocks mantêm o build independente de credenciais ou infraestrutura externa. Os testes do script de release permanecem em `tests_ci`.

Para testar Redis/RabbitMQ reais com infraestrutura descartável, execute `bash scripts/test-infrastructure.sh`. O script cria um projeto Compose com nome único, portas aleatórias no loopback e senha temporária, executa `OrderInfrastructureTest` e remove seus contêineres/volumes ao terminar. O teste usa banco H2 isolado; a execução Maven padrão pula esse cenário.

Verificação realizada em 15/11/2026: build limpo com 15 testes Java aprovados; 22 testes dos scripts aprovados e um teste de renderização dependente de kubectl pulado no contêiner Linux; teste adicional com Redis/RabbitMQ reais aprovado. A atualização para Spring Boot 4.0.9 e RabbitMQ Java client 5.34.0 eliminou as 62 ocorrências HIGH/CRITICAL encontradas inicialmente: o scan final analisou 146 bibliotecas e encontrou zero nessas severidades. Relatórios locais ficam em `artifacts/` e não são versionados. Esse resultado cobre dependências Java, não a imagem de sistema operacional nem uma integração real com a Stripe ou com seu emissor OAuth2.

Os testes funcionais não medem ganho de desempenho. Para demonstrá-lo, execute a mesma carga antes/depois com cache habilitado/desabilitado e diferentes quantidades de réplicas, registrando vazão, p95/p99, erros e conexões pendentes no dashboard. Use contas/produtos de teste e preserve os resultados brutos.

## Referências técnicas

- [Spring Boot: RabbitMQ](https://docs.spring.io/spring-boot/reference/messaging/amqp.html)
- [Spring Data Redis: templates](https://docs.spring.io/spring-data/redis/reference/redis/template.html)
- [Spring Security: JWT resource server](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)
- [Stripe: idempotência e sua janela de retenção](https://docs.stripe.com/api/idempotent_requests)
- [Stripe: consultar PaymentIntent](https://docs.stripe.com/api/payment_intents/retrieve)
- [Trivy: varredura de rootfs](https://trivy.dev/docs/latest/target/rootfs/)
