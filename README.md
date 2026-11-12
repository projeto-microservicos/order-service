# Order service

Independent Java 25 / Spring Boot microservice for orders. Owns the `orders` schema in PostgreSQL. Product and exchange are runtime HTTP dependencies, never build dependencies or copied source libraries.

## Run

With JDK 25 and Maven 3.9+:

```bash
mvn -B -ntp verify
```

Tests start HTTP stubs for product and exchange, so neither service needs to be running for the build.

```bash
docker build -t store/order-service:dev .
python scripts/init-env.py
docker network create store-dev  # Once; skip if already created
# Start product-service and exchange-service Compose stacks on this same network.
docker compose up --build -d --wait --wait-timeout 300
```

The API is at `http://localhost:8082`. Compose starts its private PostgreSQL instance. Defaults are `PRODUCT_URL=http://product:8080` and `EXCHANGE_URL=http://exchange:8080`, resolved through the shared `store-dev` network. Both URLs can point to independently deployed services.

## HTTP contract

| Method/path | Result |
|---|---|
| `POST /orders` | 201; calculates price snapshots/totals; missing product 400 |
| `GET /orders` | 200; summaries belonging to the current account |
| `GET /orders/{id}` | 200; USD details; another account's order 404 |
| `GET /orders/{id}?currency=BRL` | 200; converted totals; unsupported currency 422 |

Supply the trusted `id-account` header. Example body:

```json
{"items":[{"idProduct":"REPLACE_WITH_EXISTING_PRODUCT_ID","quantity":2}]}
```

All monetary values are stored in USD using `BigDecimal`/`NUMERIC`. Product prices are resolved before any order is persisted. The service never trusts client totals. Order lookups constrain both order ID and account ID. Conversion uses one sell/ask quote per response, rounds each line to the requested currency's fraction digits, and sums the displayed lines; stored amounts remain unchanged. Dependency failure returns 503.

Configuration: database settings as in `.env.example`, `DATABASE_SSLMODE`, `PRODUCT_URL`, `EXCHANGE_URL`, `SERVER_PORT`, `MANAGEMENT_PORT`. `/swagger-ui/index.html` documents the API. Actuator metrics use private port 9091.

Two HTTP integration tests cover persistence, ownership, totals/conversion, invalid quantities and missing products with no partial order. They use H2 and HTTP provider stubs. Authentication is the gateway's responsibility; this internal service trusts its verified header.

## Repository boundaries

This is an independent Git repository with its own source, tests, Dockerfile, Jenkinsfile, Kubernetes manifest and GitHub Actions workflow. Build commands run from this repository's root. No parent checkout, sibling source tree or local Maven artifact from another store service is required.

This delivery does not modify `platform` or register this service as a submodule. In a deployed system, an authentication gateway must validate the user and set the `id-account` header. Direct ports in the development Compose file are bound to loopback for local testing. Do not expose an internal service directly to the internet or trust caller-supplied identities at a public endpoint.

## CI and releases

Deploy to EKS with this repository's `Jenkinsfile`. It tests the service, publishes its container to a private ECR repository, and optionally applies the Deployment/Services and waits for rollout. Images are deployed by digest. The AWS identity belongs to the Jenkins agent; a stored kubeconfig is not required.

See [the Jenkins + EKS setup guide](docs/JENKINS-EKS.md) for agent dependencies, IAM, EKS access, initial environment setup, GitHub checkout/webhooks, parameters and troubleshooting. `DEPLOY=false` publishes only; enable deployment after preparing the namespace and dependencies.

`k8s/k8s.yaml` contains only the application Deployment and Services. Copy `k8s/config.example.yaml` to `k8s/config.local.yaml` and configure your environment once. Secrets are created separately; deployments never overwrite them. The application remains private (ClusterIP). Existing GitHub Actions continues to run CI only.

## Clone the repository

```bash
git clone https://github.com/projeto-microservicos/order-service.git
cd order-service
```

Configure your Git name/email before committing contributions. Push changes to this repository to run the GitHub Actions CI workflow.

## Attribution and assistance

This service was developed for the [Insper 2026.2 exercises](https://insper.github.io/platform/pt/2026.2/), following the architectural conventions of the supplied [platform](https://github.com/adventurescumm-studies/platform) and its account/auth/gateway repositories. AI assistance covered the initial implementation, tests, configuration and this repository separation. Review and adapt the code, record your actual contributions, and follow the course disclosure requirements.
