# Jenkins + ECR + EKS — order-service

Este repositório tem uma Pipeline independente. O Jenkins busca o código no GitHub, executa os testes, constrói a imagem, publica no ECR e, com `DEPLOY=true`, aplica o serviço ao EKS e aguarda o rollout. Não é necessário alterar `platform` ou adicionar submódulos. O GitHub Actions existente continua fazendo somente CI.

## 1. Preparar o agente Jenkins

Use um agente Linux dedicado aos repositórios confiáveis, com label **`eks-deploy`**. Instale Git, Bash, Python 3.9+, AWS CLI v2, Docker CLI com BuildKit e um daemon/builder acessível, e `kubectl` compatível com a versão do cluster (mesma versão minor ou uma acima/abaixo). Para este serviço, instale JDK 25 e Maven 3.9+ no agente. A etapa de vulnerabilidades usa Trivy instalado ou baixa a imagem fixada pelo script `scripts/scan.sh`; as bases CVE/Java exigem conectividade externa.

Plugins: Pipeline (incluindo Declarative), Git, Credentials Binding e JUnit. GitHub é necessário somente para o webhook descrito adiante. AWS Credentials é necessário somente se usar `AWS_CREDENTIALS_ID`.

O agente precisa alcançar GitHub, os repositórios de dependências, ECR e o endpoint Kubernetes do EKS. Para um endpoint privado, execute o agente na VPC ou numa rede conectada a ela, com DNS, rotas e Security Groups adequados. Os nós também precisam alcançar ECR e os serviços de aplicação. Jenkins dentro de um Pod não recebe automaticamente um daemon Docker: configure um agente/builder apropriado antes de executar esta Pipeline.

Selecione `linux/amd64` ou `linux/arm64` conforme os nós. O script também configura o `nodeSelector` correspondente. Prefira um agente/builder da mesma arquitetura; compilação cruzada requer suporte de emulação no builder. Em cluster misto, deve existir capacidade na arquitetura escolhida.

## 2. Identidade AWS do Jenkins

Preferencialmente, associe uma role IAM ao **agente**: instance profile se estiver numa EC2, ou Pod Identity/IRSA se estiver num Pod EKS. A AWS CLI utiliza sua cadeia normal de credenciais. Confira no agente:

```bash
aws sts get-caller-identity
```

O resultado identifica a conta e a role que realmente executam a Pipeline. A identidade do controller Jenkins pode ser diferente da identidade do agente. Não use a role do control plane do EKS para o Jenkins.

Anexe à role uma política baseada em [`jenkins-iam-policy.example.json`](jenkins-iam-policy.example.json). Substitua `REGION`, `ACCOUNT_ID`, `CLUSTER_NAME` e o nome do repositório ECR se necessário. A política permite publicar apenas nesse ECR e consultar apenas esse cluster. Ela não cria infraestrutura ou concede acesso Kubernetes sozinha. Se um único agente/role atende aos três jobs, combine as permissões dos três repositórios ECR na política.

Alternativa para um Jenkins que já usa credenciais AWS: em **Manage Jenkins → Credentials**, cadastre uma credencial do tipo **AWS Credentials**, instale o plugin correspondente e informe seu ID em `AWS_CREDENTIALS_ID`. A Pipeline usa `withCredentials` apenas na etapa de publicação/deploy. Deixe esse parâmetro vazio quando usar a role do agente. Não são necessários um Secret de kubeconfig ou credenciais AWS no GitHub.

## 3. Preparação inicial na AWS e no cluster

Execute esta seção com a identidade de um administrador da infraestrutura. Ela não faz parte da publicação cotidiana. Valores abaixo são exemplos e devem ser substituídos:

```bash
export AWS_REGION='us-east-1'
export AWS_ACCOUNT_ID='123456789012'
export EKS_CLUSTER_NAME='SEU_CLUSTER'
export JENKINS_ROLE_ARN='arn:aws:iam::123456789012:role/SUA_ROLE_DO_AGENTE'
export K8S_NAMESPACE='store'
export ECR_REPOSITORY='order-service'
```

Crie o repositório ECR, caso ainda não exista:

```bash
aws ecr create-repository --region "$AWS_REGION" \
  --repository-name "$ECR_REPOSITORY" --image-tag-mutability IMMUTABLE \
  --image-scanning-configuration scanOnPush=true
```

O job espera o ECR e o EKS existentes, na mesma conta/região. Configure a role dos **nós EKS** com permissões de pull do ECR; em Fargate, configure a **pod execution role**. Essas permissões são distintas das permissões de push do Jenkins. A identidade da aplicação não é usada para baixar sua imagem.

### Acesso Kubernetes

Confira o modo de autenticação do cluster:

```bash
aws eks describe-cluster --region "$AWS_REGION" --name "$EKS_CLUSTER_NAME" \
  --query 'cluster.accessConfig.authenticationMode' --output text
```

Para os comandos de access entry abaixo, o cluster precisa de `API` ou `API_AND_CONFIG_MAP`. Se estiver em `CONFIG_MAP`, o administrador pode migrar para `API_AND_CONFIG_MAP`, preservando os mapeamentos existentes. Habilitar access entries é uma decisão de configuração do cluster e não pode ser simplesmente desfeito para o modo original:

```bash
# Somente se o cluster ainda usa CONFIG_MAP:
aws eks update-cluster-config --region "$AWS_REGION" --name "$EKS_CLUSTER_NAME" \
  --access-config authenticationMode=API_AND_CONFIG_MAP
aws eks wait cluster-active --region "$AWS_REGION" --name "$EKS_CLUSTER_NAME"
```

Crie a access entry para a role IAM do agente, usando o ARN da **role**, não o ARN de uma sessão `assumed-role`:

```bash
# Role dedicada a este serviço:
aws eks create-access-entry --region "$AWS_REGION" --cluster-name "$EKS_CLUSTER_NAME" \
  --principal-arn "$JENKINS_ROLE_ARN" --type STANDARD \
  --kubernetes-groups 'jenkins-order'
```

Se **a mesma role** atende aos três serviços, crie uma única access entry com os três grupos: `--kubernetes-groups jenkins-product jenkins-order jenkins-exchange`. Se a entry já existir, use `update-access-entry` para configurar os grupos necessários, preservando outros grupos que ela já tenha. Não é preciso associar uma EKS access policy administrativa: as permissões abaixo são concedidas via RBAC.

Ainda como administrador:

```bash
aws eks update-kubeconfig --region "$AWS_REGION" --name "$EKS_CLUSTER_NAME"
# Crie uma única vez; pule se o namespace já existir.
kubectl create namespace "$K8S_NAMESPACE"
kubectl -n "$K8S_NAMESPACE" apply -f k8s/jenkins-rbac.yaml
```

O RBAC permite gerenciar Deployments e Services nesse namespace, listar Pods e consultar apenas o ConfigMap/Secret deste serviço. Não permite administrar o cluster, criar roles ou modificar Secrets. A permissão de criar Deployments é abrangente dentro do namespace; para isolamento forte entre equipes, use namespaces e agentes distintos. Aplique o RBAC de cada repositório ao configurar seus três jobs.

### Configuração da aplicação

Prepare PostgreSQL e sua conectividade antes do primeiro deploy. Se habilitar cache ou mensageria, prepare também Redis e RabbitMQ e configure os endpoints/credenciais. Esta Pipeline não cria bancos, caches, brokers, VPCs ou o cluster. Pedidos usa PostgreSQL e chama produtos/câmbio por HTTP. No mesmo namespace, os padrões `http://product:8080` e `http://exchange:8080` resolvem pelos Services. Se usar namespaces diferentes, configure os endereços completos. Veja [BOTTLENECKS.md](BOTTLENECKS.md) para JWT, Stripe e as novas variáveis.

```bash
cp k8s/config.example.yaml k8s/config.local.yaml
# Edite config.local.yaml com os endpoints reais, banco e parâmetros da aplicação.
kubectl -n "$K8S_NAMESPACE" apply -f k8s/config.local.yaml
```

Crie o Secret com valores reais, sem gravá-los no repositório ou digitá-los em argumentos visíveis do processo. Exemplo em Bash interativo:

```bash
secret_file=$(mktemp)
chmod 600 "$secret_file"
read -r -p 'Usuário PostgreSQL: ' db_user
read -r -s -p 'Senha PostgreSQL: ' db_password
printf '\n'
printf 'DATABASE_USERNAME=%s\nDATABASE_PASSWORD=%s\n' "$db_user" "$db_password" > "$secret_file"
unset db_user db_password
kubectl -n "$K8S_NAMESPACE" create secret generic order-secrets \
  --from-env-file="$secret_file" --dry-run=client -o yaml | kubectl apply -f -
rm -f "$secret_file"
```

Se sua infraestrutura já gerencia o Secret, mantenha esse mecanismo e use o nome `order-secrets`. As migrações Flyway criam as tabelas/schema da aplicação; a conexão precisa de um banco existente e de permissões adequadas.

O arquivo `k8s/k8s.yaml` agora contém somente Deployment e Services. As configurações são preparadas separadamente para que um deploy não substitua senhas ou endpoints. Alterações posteriores no ConfigMap/Secret exigem reiniciar os Pods, por exemplo `kubectl -n "$K8S_NAMESPACE" rollout restart deployment/order`, e acompanhar o rollout.

Os serviços permanecem `ClusterIP`, com duas réplicas da aplicação. Configure o gateway/Ingress da sua infraestrutura existente para acesso externo autenticado. Por padrão, a aplicação usa o header `id-account` definido pelo gateway; com `JWT_ENABLED=true`, valida o token e obtém a conta de `sub`. O deploy não cria ou altera rotas públicas.

## 4. Conectar cada repositório GitHub ao Jenkins

1. Publique este repositório independente no seu GitHub. Se ele já existe, copie as alterações, faça commit e push nele. Se estiver começando, o pacote contém um Git bundle; veja o README para clonar e definir o remote.
2. Em Jenkins, **New Item → Pipeline**, nome `order-service`.
3. Em **Pipeline → Definition**, escolha **Pipeline script from SCM**; SCM **Git**; Repository URL = URL deste repositório; Branch Specifier **`*/main`**; Script Path **`Jenkinsfile`**.
4. Para repositório privado, selecione uma credencial Git de leitura cadastrada no Jenkins (PAT apropriado ou SSH). Essa credencial é separada da identidade AWS.
5. Execute uma primeira vez para carregar os parâmetros. Ela pode parar por parâmetros AWS vazios; depois use **Build with Parameters**. A Pipeline publica somente o checkout configurado no job; o antigo parâmetro `REVISION` foi removido.

| Parâmetro | Valor |
|---|---|
| `AWS_REGION` | Região do ECR e do cluster |
| `AWS_ACCOUNT_ID` | Conta AWS com 12 dígitos; verificada antes de publicar |
| `ECR_REPOSITORY` | `order-service`, ou seu nome real |
| `EKS_CLUSTER_NAME` | Nome real do cluster; obrigatório para deploy |
| `K8S_NAMESPACE` | `store`, ou namespace já preparado |
| `IMAGE_PLATFORM` | `linux/amd64` ou `linux/arm64` |
| `AWS_CREDENTIALS_ID` | Vazio para role do agente, ou ID da credencial AWS do Jenkins |
| `DEPLOY` | `false`: testes e publicação; `true`: também aplica e acompanha o rollout |

Depois do bootstrap, execute com `DEPLOY=true`. Os valores padrão de conta, região e cluster estão vazios para serem preenchidos com seus dados reais. Para builds disparados automaticamente, altere os `defaultValue` no Jenkinsfile e faça commit; valores usados numa execução manual não constituem configuração persistente garantida para webhooks.

### Disparo por push

No job, habilite **GitHub hook trigger for GITScm polling** (plugin GitHub). No repositório GitHub, em **Settings → Webhooks → Add webhook**, configure `https://SEU_JENKINS/github-webhook/`, Content type `application/json` e evento de push. Configure o mesmo segredo de webhook no GitHub e no Jenkins conforme sua instalação. O endereço precisa ser alcançável pelo GitHub.

Após preparar o ambiente, mude o `defaultValue` de `DEPLOY` para `true` se quiser que cada push em `main` também publique no EKS. Se Jenkins for privado e não receber webhooks, use **Poll SCM** com uma frequência como `H/5 * * * *`, ou execução manual. Use este job de release apenas para a branch confiável; não execute código de forks/PRs com a role de deploy. Mantenha um job de CI sem credenciais de publicação para contribuições externas.

## 5. O que a Pipeline faz

1. Limpa o workspace do job, faz checkout, executa os testes e analisa as dependências do JAR com Trivy. CVEs HIGH/CRITICAL impedem a publicação.
2. Confere a conta AWS e a existência do ECR. Com deploy ativo, gera um kubeconfig temporário e verifica o ConfigMap e Secret do serviço.
3. Constrói e publica uma imagem com tag `COMMIT_COMPLETO-BUILD_NUMBER`; consulta seu digest no ECR.
4. Com `DEPLOY=true`, renderiza Deployment/Services com namespace, digest e arquitetura corretos usando o Kustomize do `kubectl`; valida no servidor e aplica, inclusive no primeiro deploy.
5. Aguarda `rollout status`. Falha de build, login ou push impede o deploy; falha de rollout deixa o job com erro. ConfigMaps e Secrets não são reaplicados pelo job.

Artefatos Jenkins: `vulnerabilities.json` registra a varredura; `release.json` registra commit, imagem, digest e resultado do deploy; `deployment.yaml` registra o manifesto aplicado sem os valores dos Secrets. `pending` significa que a atualização foi iniciada mas não concluiu com sucesso. O Docker config e o kubeconfig temporários são removidos no encerramento normal ou falha do script.

Não há rollback automático. Após corrigir a causa, execute uma nova release; para voltar uma versão, um operador autorizado pode usar `kubectl rollout undo deployment/order -n store` e acompanhar o rollout. Em um primeiro deploy ainda não existe revisão anterior. O script não remove imagens antigas; configure políticas de retenção no ECR e limpeza de imagens no agente conforme seu uso.

## Diagnóstico

| Sintoma | Verificação |
|---|---|
| Conta AWS diferente | Role efetiva do agente e `AWS_ACCOUNT_ID` |
| `AccessDenied` no ECR ou `DescribeCluster` | Política IAM da role usada pelo agente |
| `Unauthorized` / `Forbidden` no kubectl | Access entry, grupos RBAC e namespace; IAM isoladamente não autoriza Kubernetes |
| Timeout ao acessar Kubernetes | Endpoint privado/público, VPC, DNS, rotas e Security Groups |
| ConfigMap/Secret ausente | Execute o bootstrap no namespace informado |
| `ImagePullBackOff` | Permissão de pull da role dos nós/Fargate e conectividade com ECR |
| `Pending` / arquitetura incompatível | Nós disponíveis, recursos e `IMAGE_PLATFORM` |
| `CrashLoopBackOff` / rollout expira | Configuração do banco/Redis, conectividade e logs da aplicação |

## Verificação e referências

Testes locais do script usam comandos AWS/Docker/kubectl simulados para validar sucesso e falhas. Eles não comprovam conectividade com a sua AWS. O Jenkins e um rollout real devem ser validados na sua infraestrutura após configurar os valores acima.

- [Jenkins: credenciais em Pipelines](https://www.jenkins.io/doc/book/pipeline/jenkinsfile/#handling-credentials)
- [Jenkins: integração e webhook GitHub](https://plugins.jenkins.io/github/)
- [AWS: kubeconfig e autenticação EKS](https://docs.aws.amazon.com/eks/latest/userguide/create-kubeconfig.html)
- [AWS: EKS access entries](https://docs.aws.amazon.com/eks/latest/userguide/access-entries.html)
- [AWS: habilitar access entries](https://docs.aws.amazon.com/eks/latest/userguide/setting-up-access-entries.html)
- [AWS: endpoint do cluster](https://docs.aws.amazon.com/eks/latest/userguide/cluster-endpoint.html)
- [AWS: IAM para publicar no ECR](https://docs.aws.amazon.com/AmazonECR/latest/userguide/image-push-iam.html)
- [AWS: imagens ECR no EKS](https://docs.aws.amazon.com/AmazonECR/latest/userguide/ECR_on_EKS.html)
- [Kubernetes: Kustomize](https://kubernetes.io/docs/tasks/manage-kubernetes-objects/kustomization/)
