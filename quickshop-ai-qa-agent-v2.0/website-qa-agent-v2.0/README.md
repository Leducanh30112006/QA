# QuickShop AI QA Agent v2.0

A source-aware + runtime QA orchestration platform tailored to the QuickShop marketplace repository. It is a deployable engineering starter with executable baseline checks, not a claim of complete vulnerability coverage.

## QuickShop profile

The target profile models the repository's documented services: Nginx, Spring Cloud Gateway, Identity, Catalog, Order, Payment, Shipping, Notification AI, React/Vite frontend, MySQL, Redis, RabbitMQ, MinIO, Prometheus, Grafana and Zipkin. The profile is editable in `config/quickshop-profile.yml`. It is based on the public repository README/SRS structure; verify service names, routes and ports against the current checkout before enabling deeper tests.

## Capabilities in this bundle

- Java 21 / Spring Boot API and React 18 + TypeScript dashboard.
- Project registration with a strict URL + path scope.
- Runtime baseline: bounded Playwright discovery, page response checks, browser console errors, forms/links inventory, security headers, TLS/HTTPS checks, common OpenAPI endpoint discovery and safe HTTP probes.
- Source audit: clone a public Git repository (or inspect a mounted local checkout) and inspect Gradle modules, Spring controllers/routes, security config, tests, Docker/Compose, CI workflows, secret-like patterns and dependency manifests.
- QuickShop service-aware checklist and SRS control mapping.
- Evidence-backed findings with severity, confidence, file/line or URL evidence.
- Gemini analysis is optional; deterministic local analysis remains available without an API key.
- Active scanner execution (ZAP/Nuclei/k6) is not wired into the default worker; the isolation and adapter contract is documented for a separately approved staging implementation.
- JSON report export.

## Run locally

Requirements: Docker Compose v2. Copy `.env.example` to `.env`, set strong local credentials, then run:

```bash
docker compose up --build
```

Dashboard: http://localhost:3000  | API health: http://localhost:8080/api/health

Keep the dashboard bound to localhost. This bundle does not include production-grade user authentication or multi-tenant authorization. Do not expose it publicly without adding OIDC/JWT, RBAC, audit logging, rate limits and secret management.

## Register QuickShop

Create a project with:

- Name: `QuickShop`
- Base URL: `https://quickshop.io.vn`
- Allowed path prefix: `/` (narrow this if testing a specific application area)
- Source repository: `https://github.com/Leducanh30112006/HN_KS24_CNTT2_OJT.git`

For a private repository, prefer mounting a read-only checkout into `/workspace/source`, setting `LOCAL_SOURCE_MOUNT=/absolute/path/to/checkout` and `SOURCE_REPO_PATH=/workspace/source`. Do not paste GitHub tokens into chat or store them in the project record. If using `GITHUB_TOKEN`, inject it as a runtime secret.

## Scan modes

1. **Safe baseline** (default): read-only GET requests, bounded page discovery, source static heuristics, passive security headers and metadata.
2. **Staging active** (not enabled in the UI/default compose): ZAP/Nuclei active checks, authenticated workflows, destructive/business-flow tests and k6 load tests. Require a staging target, written scope, rate limits, test accounts and explicit approval.
3. **Regression**: rerun saved baseline checks after deployment/commit; CI integration should call the API only from a protected runner.

## QuickShop-specific test packs

- Identity: JWT validation, cookie flags, role matrix Buyer/Seller/Admin, refresh/revocation.
- Catalog: product/SKU validation, stock concurrency, cache consistency.
- Order: cart TTL, multi-vendor checkout, Saga compensation, idempotency, DLQ.
- Payment: VNPay sandbox callback verification, duplicate callback handling, escrow and double-entry ledger invariants. Never run real-money payment probes from this agent.
- Shipping: GHN/GHTK webhook signature/idempotency and duplicate event handling.
- Notification AI: prompt injection, tenant/data isolation and output handling.
- Platform: Gateway routing/CORS/rate-limit, Redis, RabbitMQ, MinIO object access, actuator exposure and trace/log leakage.

The current executable implementation performs source heuristics, passive runtime checks, bounded Playwright smoke checks and optional Gemini evidence summarization. Deep business invariants require dedicated test fixtures, test credentials, service contracts and a staging environment; they are documented in `docs/QUICKSHOP_TEST_MATRIX.md` and are not fabricated as passing tests.

## API

- `GET /api/health`
- `GET /api/projects`
- `POST /api/projects`
- `POST /api/projects/{id}/scans`
- `GET /api/scans/{id}`
- `GET /api/scans/{id}/findings`
- `GET /api/scans/{id}/report`

Project payload:

```json
{
  "name": "QuickShop",
  "baseUrl": "https://quickshop.io.vn",
  "allowedPathPrefix": "/",
  "maxPages": 40,
  "sourceRepoUrl": "https://github.com/Leducanh30112006/HN_KS24_CNTT2_OJT.git"
}
```

## Important limitations

- The public repository was inspected through its README and visible SRS/repository metadata. This environment could not clone the repository or run its build, so the profile is based on documented architecture, not a verified checkout of every source file.
- The generated bundle has not been compiled with Maven or exercised with Docker in this environment because those build tools are unavailable in the authoring runtime. Run the verification steps below before relying on it.
- No scan has been run against QuickShop by creating this bundle.
- A clean scan is not proof of security. False positives/negatives are expected.

## Verify before use

```bash
docker compose config
docker compose build
docker compose up -d
curl http://localhost:8080/api/health
```

For backend tests, run `./mvnw test` if a Maven wrapper is added, or `mvn test` with Maven 3.9+. For frontend, run `npm install && npm run build`.
