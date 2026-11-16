# GitHub Actions + ECR + EKS — order-service

O workflow [ci-cd.yml](../.github/workflows/ci-cd.yml) substitui o Jenkins. Pull requests executam testes Java, testes dos scripts, Trivy, build Docker e validação do Compose. Depois dessas verificações, uma execução em `main` pode publicar no ECR e aplicar o serviço ao EKS com credenciais temporárias OIDC. Não é necessário cadastrar chaves AWS ou kubeconfig no GitHub.

## Ativação gradual

1. Faça push desta alteração: somente CI roda enquanto `RELEASE_ENABLED` estiver ausente ou diferente de `true`.
2. O responsável pela AWS prepara ECR e role OIDC e configura as variáveis abaixo. Em **Actions → Order CI/CD → Run workflow**, selecione `main` e mantenha `deploy` desmarcado para publicar sem acessar o cluster.
3. `RELEASE_ENABLED=true` passa a publicar a cada push em `main`. Mantenha `DEPLOY_ENABLED=false` enquanto PostgreSQL, ConfigMap, Secret ou acesso ao cluster estiverem pendentes.
4. Com essas dependências prontas, execute manualmente com `deploy` marcado. `DEPLOY_ENABLED=true` habilita também o deploy automático nos próximos pushes em `main`.

A execução manual usa somente sua opção `deploy`; ela não herda `DEPLOY_ENABLED`. Outras branches e pull requests nunca executam o job de release. As execuções de `main` são serializadas desde a verificação, evitando releases de builds antigos depois de novos; um rollout em andamento não é cancelado. Reexecuções recebem tags diferentes (`commit-run_id-run_attempt`), compatíveis com ECR de tags imutáveis. O deploy utiliza o digest retornado pelo ECR.

## Variáveis do repositório

Configure em **Settings → Secrets and variables → Actions → Variables**:

| Variável | Valor |
|---|---|
| `AWS_REGION` | Região do ECR e EKS, por exemplo `us-east-1` |
| `AWS_ACCOUNT_ID` | Conta AWS esperada, com 12 dígitos |
| `AWS_ROLE_ARN` | ARN da role IAM dedicada ao workflow |
| `ECR_REPOSITORY` | Repositório ECR existente; padrão `order-service` |
| `EKS_CLUSTER_NAME` | Cluster existente; obrigatório para deploy |
| `K8S_NAMESPACE` | Namespace existente; padrão `store` |
| `KUBECTL_VERSION` | Versão explícita compatível com o cluster, no formato `vX.Y.Z`; obrigatória para deploy |
| `IMAGE_PLATFORM` | `linux/amd64` (padrão) ou `linux/arm64`; deve corresponder aos nós |
| `RELEASE_ENABLED` | `true` habilita publicação automática em pushes de `main`; padrão desligado |
| `DEPLOY_ENABLED` | `true` habilita deploy automático nesses pushes; padrão desligado |

O runner usa Ubuntu e instala emulação quando a imagem é ARM64. O manifesto renderizado seleciona nós Linux da arquitetura escolhida. Use `kubectl` da mesma versão minor do cluster ou dentro da compatibilidade suportada.

## Identidade AWS e permissões

O administrador configura o provedor IAM OIDC `https://token.actions.githubusercontent.com`, audience `sts.amazonaws.com`, e uma role com:

- Trust policy baseada em [github-actions-trust-policy.example.json](github-actions-trust-policy.example.json), substituindo `ACCOUNT_ID`. O exemplo restringe a identidade a `projeto-microservicos/order-service`, branch `main`.
- Permissões baseadas em [github-actions-iam-policy.example.json](github-actions-iam-policy.example.json), substituindo `REGION`, `ACCOUNT_ID` e `CLUSTER_NAME`. O nome do ECR também deve corresponder ao configurado no GitHub.

Confira o formato do claim `sub` emitido pelo seu repositório: se houver personalização ou identificação imutável com IDs da organização/repositório, ajuste a condição exata da trust policy. Este workflow não usa GitHub Environments; adicionar um altera o `sub` esperado. A documentação oficial explica essas variações em [OIDC com AWS](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-in-aws).

A role publica somente no ECR indicado e consulta o cluster indicado. Ela não cria infraestrutura ou concede acesso Kubernetes. As roles dos nós EKS precisam, separadamente, de permissão de pull do ECR. Prefira tags imutáveis e configure a retenção de imagens no ECR conforme o orçamento da equipe.

O runner precisa alcançar o endpoint Kubernetes. Se o EKS tiver endpoint privado, use um runner com conectividade à VPC e ajuste `runs-on` conforme sua infraestrutura; OIDC resolve autenticação, mas não cria uma rota de rede.

## Acesso ao EKS

O responsável pelo cluster cria uma access entry para o ARN da role IAM, associada ao grupo `github-actions-order`. Exemplo para um cluster que já usa autenticação `API` ou `API_AND_CONFIG_MAP`:

```bash
aws eks create-access-entry --region "$AWS_REGION" \
  --cluster-name "$EKS_CLUSTER_NAME" --principal-arn "$AWS_ROLE_ARN" \
  --type STANDARD --kubernetes-groups github-actions-order
kubectl -n "$K8S_NAMESPACE" apply -f k8s/github-actions-rbac.yaml
```

Se a access entry já existir, atualize seus grupos preservando outros acessos. Em cluster com autenticação legada, o administrador deve configurar o mecanismo de acesso existente ou avaliar a migração antes de usar esse comando. O workflow não altera o modo de autenticação do cluster.

O RBAC permite gerenciar Deployments/Services no namespace, consultar Pods e ler somente `order-config`/`order-secrets`. A criação de Deployments permite executar workloads nesse namespace: dedique uma role e, quando necessário, um namespace por equipe. O release não altera RBAC, ConfigMaps, Secrets ou HPA.

## Banco e configuração da aplicação

PostgreSQL é obrigatório. Enquanto o RDS aguarda aprovação de custo e não houver outro PostgreSQL autorizado, use apenas publicação no ECR; a aplicação completa não sobe sem banco. Este pipeline não cria RDS, Redis, RabbitMQ, ElastiCache ou Amazon MQ.

Prepare uma vez, com a identidade responsável pela infraestrutura:

```bash
cp k8s/config.example.yaml k8s/config.local.yaml
# Ajuste o host/banco reais e o SSL conforme o PostgreSQL autorizado.
kubectl -n "$K8S_NAMESPACE" apply -f k8s/config.local.yaml
# Arquivo local protegido, ignorado pelo Git, contendo DATABASE_USERNAME e DATABASE_PASSWORD:
kubectl -n "$K8S_NAMESPACE" create secret generic order-secrets \
  --from-env-file=order.secret.env
```

Se o Secret já for gerenciado pela infraestrutura, preserve esse mecanismo. Flyway cria o schema/tabelas dentro de um banco existente com permissões adequadas. Alterações posteriores no ConfigMap/Secret precisam de reinício dos Pods.

O contrato interno permanece:

- `PRODUCT_URL=http://product:8080`, com `GET /products/{id}` e header `id-account`.
- `EXCHANGE_URL=http://exchange:8080`, com `GET /exchanges/USD/{currency}` e o mesmo header.
- Gateway valida o token e sobrescreve `id-account` com o UUID da conta; `JWT_ENABLED=false` mantém essa responsabilidade no gateway.

Os nomes curtos resolvem no mesmo namespace. Se os serviços estiverem em namespaces diferentes, configure os nomes DNS completos.

## Capacidade atual do cluster

`k8s/k8s.yaml` inicia **uma réplica**, com `maxSurge=0` e `maxUnavailable=1`: a atualização libera o Pod antigo antes de iniciar o novo e pode causar indisponibilidade breve. Essa escolha evita exigir capacidade para uma réplica extra durante o rollout.

O Deployment fixa `CACHE_ENABLED=false` e `MESSAGING_ENABLED=false`, prevalecendo inclusive sobre ConfigMaps antigos. Nenhum cache ou broker é aplicado pelo workflow. Quando houver capacidade, Redis/RabbitMQ deverão rodar no próprio cluster; remova esses overrides somente ao habilitar as dependências e seus endpoints/credenciais.

Não aplique `k8s/hpa.yaml` neste momento: seu mínimo é duas réplicas. Se já houver um HPA de `order`, o responsável pelo cluster precisa removê-lo ou ajustá-lo, pois ele pode sobrescrever a quantidade de réplicas. Duas réplicas e HPA ficam para uma etapa com capacidade aprovada. O Compose local continua permitindo exercitar Redis/RabbitMQ e os gargalos.

## Resultado e diagnóstico

O workflow arquiva resultados de testes, relatório Trivy e, quando produzidos, `release.json` e `deployment.yaml`. O primeiro registra commit, tag, digest e resultado; o segundo contém Deployment/Services renderizados sem valores de Secrets. Docker config e kubeconfig temporários são removidos ao encerrar o script.

O script verifica conta AWS, ECR, ConfigMap/Secret e faz validação no servidor antes de aplicar o manifesto. Ele aguarda o rollout por cinco minutos e retorna falha se a aplicação não ficar pronta. Em falha, examine o workload e o artefato antes de repetir ou reverter para o digest anterior.

```bash
python3 -m unittest discover -s tests_ci -v
mvn -B -ntp verify
```

Os testes dos scripts simulam AWS/Docker/kubectl e cobrem falhas de publicação/deploy. Eles não substituem a validação real de OIDC, permissões, rede e rollout na conta da equipe.
