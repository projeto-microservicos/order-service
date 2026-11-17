# Order service

Independent Java 25 / Spring Boot microservice for orders. Owns the `orders` schema in PostgreSQL. Product and exchange are runtime HTTP dependencies, never build dependencies or copied source libraries.

## Run

With JDK 25 and Maven 3.9+:

```bash
mvn -B -ntp verify
```

Tests start HTTP provider stubs and use H2, with Redis/RabbitMQ simulated where needed. External services and payment credentials are not needed for the build.

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
| `GET /orders/health-check` | 200 while the process serves HTTP; no token, no identity, empty body. Target of the startup/liveness probes and the Compose healthcheck |

Supply the trusted `id-account` header. Example body:

```json
{"items":[{"idProduct":"REPLACE_WITH_EXISTING_PRODUCT_ID","quantity":2}]}
```

All monetary values are stored in USD using `BigDecimal`/`NUMERIC`. Product prices are resolved before any order is persisted. The service never trusts client totals. Order lookups constrain both order ID and account ID. Conversion uses one sell/ask quote per response, rounds each line to the requested currency's fraction digits, and sums the displayed lines; stored amounts remain unchanged. Dependency failure returns 503.

Configuration: database settings as in `.env.example`, `DATABASE_SSLMODE`, `PRODUCT_URL`, `EXCHANGE_URL`, `SERVER_PORT`, `MANAGEMENT_PORT`. `/swagger-ui/index.html` documents the API. Actuator metrics use private port 9091. `id-account` must contain an account UUID.

The test suite covers persistence, ownership, totals/conversion, validation, cache failures, transactional events, relay retries, JWT scopes and idempotent payments. By default, authentication is the gateway's responsibility; this internal service trusts its verified header. Optional JWT verification derives the account from the token subject and enforces `orders:read` / `orders:write` scopes.

## Bottlenecks

The seven areas in the [Insper exercise](https://insper.github.io/platform/pt/2026.2/exercises/bottlenecks/) have implementations for Redis caching, Prometheus/Grafana, RabbitMQ with a transactional outbox, Kubernetes load distribution with optional additional replicas, Trivy in CI/CD, optional Stripe PaymentIntents and optional OAuth2 JWT verification. The current EKS configuration uses one replica with cache and messaging disabled until cluster capacity is available.

See [the implementation and validation guide](docs/BOTTLENECKS.md) for the before/after mapping, configuration, payment endpoints and failure behavior. To run the monitoring stack locally:

```bash
python scripts/init-env.py --update
docker compose --profile monitoring up --build -d --wait --wait-timeout 300
```

Grafana: `http://localhost:3000` (user `admin`, password `GRAFANA_PASSWORD` from your local `.env`). Prometheus: `http://localhost:9090`. Cache and messaging are enabled by default in Compose; JWT and Stripe require explicit configuration. An existing `.env` can be upgraded with `--update`, preserving its values.

## Repository boundaries

This is an independent Git repository with its own source, tests, Dockerfile, Kubernetes manifest and GitHub Actions CI/CD workflow. Build commands run from this repository's root. No parent checkout, sibling source tree or local Maven artifact from another store service is required.

An aggregate repository can reference this repository as a submodule without changing its independent build. In a deployed system, an authentication gateway must validate the user and set the `id-account` header. Direct ports in the development Compose file are bound to loopback for local testing. Do not expose an internal service directly to the internet or trust caller-supplied identities at a public endpoint.

## CI and releases

Use [.github/workflows/ci-cd.yml](.github/workflows/ci-cd.yml) for verification, ECR publication and optional EKS deployment. AWS authentication uses GitHub OIDC with temporary credentials. Images are deployed by digest; release tags include the commit, workflow run and retry number.

See [the GitHub Actions + EKS setup guide](docs/GITHUB-ACTIONS-EKS.md) for variables, OIDC trust/IAM policies, EKS access and environment setup. Pull requests run CI only. Releases run manually from `main`, or automatically after setting `RELEASE_ENABLED=true`. Keep `DEPLOY_ENABLED=false` until PostgreSQL and cluster access are ready; manual runs have a separate `deploy` checkbox, off by default.

`k8s/k8s.yaml` contains only the application Deployment and Services. It uses one replica, disables cache/messaging explicitly, and avoids an extra Pod during updates (brief downtime is possible). Leave the optional HPA unapplied. Copy `k8s/config.example.yaml` to `k8s/config.local.yaml` and configure your environment once. Secrets are created separately; deployments never overwrite them. PostgreSQL is required; this pipeline creates no AWS infrastructure. The application remains private (ClusterIP).

## Clone the repository

```bash
git clone https://github.com/projeto-microservicos/order-service.git
cd order-service
```

Configure your Git name/email before committing contributions. Push changes to this repository to run the GitHub Actions CI workflow.

## Attribution and assistance

This service was developed for the [Insper 2026.2 exercises](https://insper.github.io/platform/pt/2026.2/), following the architectural conventions of the supplied [platform](https://github.com/adventurescumm-studies/platform) and its account/auth/gateway repositories. AI assistance covered the initial implementation, tests, configuration and this repository separation. Review and adapt the code, record your actual contributions, and follow the course disclosure requirements.
