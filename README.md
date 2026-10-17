# 🛒 E-Com

A full-stack e-commerce platform with an integrated ERP back office, built as
three independent services:

| Service | What it is | Stack |
|---|---|---|
| 🧩 [`backend/`](backend) | REST API — storefront + ERP domain logic | Java 21, Spring Boot 3.3, PostgreSQL 16, Flyway |
| 🖥️ [`frontend/`](frontend) | Storefront + role-gated back office SPA | React 18, TypeScript (strict), Vite |
| 🤖 [`mcp-server/`](mcp-server) | Exposes the backend as tools for AI agents | Python 3.11+, FastMCP, uv |

Each service is self-contained with its own build, tests, CI pipeline and
in-depth docs (`SPEC.md` for design/contract, `NOTES.md` for decisions and
known gaps, `REVIEW.md` for review history) — this file is the map, not a
replacement for those.

## 💡 Why this exists

Most "e-commerce demo" projects stop at a product catalog and a checkout
button. This one goes further by also modelling the ERP machinery that a real
store needs behind the scenes: inventory costing, purchasing, double-entry
accounting, returns, and role-based access control — plus a layer that lets
an AI agent interact with the store safely, through scoped permissions and
human-in-the-loop guardrails rather than blanket API access.

## 🏗️ Architecture

```
                 ┌─────────────┐
   Shopper /     │  frontend    │   React SPA — storefront + back office
   Staff browser │  (Vite/TS)   │
                 └──────┬───────┘
                        │ REST (JSON), same-origin in prod via nginx proxy
                 ┌──────▼───────┐
                 │   backend    │   Spring Boot modular monolith
                 │ (Java/Spring)│   catalog → inventory → purchasing
                 │              │   catalog/cart/inventory/tax → order →
                 │              │   finance → returns
                 └──────┬───────┘
                        │ JDBC
                 ┌──────▼───────┐
                 │  PostgreSQL  │   Flyway-managed schema (V1–V9)
                 └──────────────┘

                 ┌──────────────┐        scoped API key, narrowed tool surface
   AI agent  ───▶│  mcp-server  │───────▶ backend REST API (same endpoints
   (MCP client)  │ (Python/uv)  │         the frontend uses — no back door)
                 └──────────────┘
```

The backend is a single deployable, organized internally as modules with a
one-way dependency graph (no module reaches "backwards"):

```
catalog ← inventory ← purchasing
catalog, cart, inventory, tax ← order ← finance ← returns
```

## ✨ Features

**🛍️ Storefront**
- Product catalog with on-hand / reserved / available quantities and photos
- Server-side cart, tax quoting, and a quote-then-confirm checkout flow with
  idempotency keys (no duplicate orders from a retried request)
- Order lifecycle: `PENDING_PAYMENT → PAID → SHIPPED → DELIVERED`, plus
  cancellation and automatic expiry of unpaid orders
- Returns / RMA: request → approve → receive → refund, within a 30-day window

**🏢 Back office (ERP)**
- 📦 **Inventory** — immutable stock-movement ledger, moving-average and
  exact-value costing, reorder point suggestions
- 🚚 **Purchasing** — suppliers, purchase orders
  (`DRAFT → SUBMITTED → APPROVED → PARTIALLY_RECEIVED → RECEIVED`), goods
  receipts
- 💰 **Finance** — payments, invoices, refunds and credit notes, an
  auto-posted double-entry general ledger, tax rates, trial balance and sales
  reports
- 🛡️ **Admin** — user management, append-only audit log (protected at the
  database level, not just in application code)

**🔐 Identity & access**
- JWT access tokens (short-lived) + opaque refresh tokens
- Scoped API keys for programmatic/agent access, separate from human login
- Role-based access control: `CUSTOMER`, `CUSTOMER_SERVICE`,
  `INVENTORY_MANAGER`, `PURCHASING_MANAGER`, `FINANCE_MANAGER`, `ADMIN`
- Separation-of-duties enforcement (configurable strict / small-team mode)

**🤖 Agent access (`mcp-server`)**
- Turns the backend's own OpenAPI contract into agent-callable tools
  (one tool per operation) instead of hand-maintaining a separate tool list
- Session/credential and role-admin endpoints are excluded outright, never
  agent-callable
- Every remaining tool is filtered against the calling API key's actual
  granted scopes before an agent ever sees it
- Orders placed by an agent carry spend limits and can require human approval
  before they go through

## 📁 Project layout

```
.
├── backend/        Spring Boot API — see backend/SPEC.md
├── frontend/        React SPA — see frontend/SPEC.md
├── mcp-server/      MCP tool server — see mcp-server/SPEC.md
└── .github/workflows/   Per-service CI (only runs on changes to that service)
```

## 🚀 Getting started

### ✅ Prerequisites

- Java 21 + Maven (backend)
- Node.js 18+ and npm (frontend)
- Python 3.11+ and [uv](https://docs.astral.sh/uv/) (mcp-server)
- Docker (for PostgreSQL, and for building container images)

### 1️⃣ Backend

```bash
cd backend
cp .env.example .env
# fill in DB_PASSWORD and JWT_SECRET (openssl rand -base64 48)

docker compose up -d postgres        # Postgres 16 on localhost:5432
set -a; source .env; set +a
mvn spring-boot:run                  # Flyway applies the schema automatically
```

The API listens on `http://localhost:8080`. Set `API_DOCS_ENABLED=true` to
get Swagger UI at `/swagger-ui.html`.

Run tests with `mvn test` (unit) or `mvn verify` (also runs Testcontainers
integration tests — needs Docker).

Everything can also run in containers: `docker compose --profile app up --build`.

### 2️⃣ Frontend

```bash
cd frontend
npm install
npm run dev          # http://localhost:3000, proxies /api to the backend
```

Other scripts: `npm run typecheck`, `npm test`, `npm run build`.

### 3️⃣ MCP server (optional — agent access only)

```bash
cd mcp-server
uv sync
cp .env.example .env
# fill in ECOM_API_KEY with a scoped key created via the backend's
# createApiKey endpoint (see mcp-server/SPEC.md for the full flow)

uv run python scripts/verify_wiring.py      # offline sanity check

# first-time setup, with the backend running and API_DOCS_ENABLED=true:
ECOM_OPENAPI_SOURCE=live uv run python scripts/refresh_openapi_cache.py openapi/snapshot.json

uv run python -m ecom_mcp.server
```

Point any MCP-compatible client at this server by running it as a local
process (`uv run python -m ecom_mcp.server` with the env vars above); the
exact wiring format depends on the client you're using.

## 🗄️ Database

PostgreSQL 16, one schema managed entirely by Flyway migrations under
`backend/src/main/resources/db/migration/` (`V1` through `V9`). The ledger
tables (stock movements, general ledger entries) and the audit log are
append-only, enforced with database triggers so the guarantee holds even if
application code has a bug.

## ⚙️ CI

Each service has its own GitHub Actions workflow
(`.github/workflows/backend-ci.yml`, `frontend-ci.yml`), path-scoped so a
change to one service doesn't trigger the other's pipeline. Backend CI runs
`mvn verify` (including integration tests) and builds the Docker image.
Frontend CI runs typecheck, tests and build, and builds the Docker image.

## 🗺️ Current status / roadmap

This is being built in phases; backend is the furthest along. Known
not-yet-built areas (see each service's `NOTES.md` for the authoritative
list): payment-provider webhooks, supplier bills, discounts, step-up
authentication for sensitive actions, and product variants/shipping options.

## 🔒 Security notes

- ⚠️ Never commit a real `.env` file — every service's `.gitignore` excludes it;
  only the `.env.example` templates are tracked.
- API keys issued for agent/programmatic access are scoped and narrower than
  a full user session — see `mcp-server/SPEC.md` for the exact model.
- Passwords and secrets are never logged or stored in plaintext.

## 📄 License

MIT — see [LICENSE](LICENSE).
