# QuickShop QA test matrix

This is the service-aware validation plan. Checks marked **fixture required** need staging test data and service-specific adapters; they are not executed by the current passive baseline.

| Service | Core checks | Evidence | Mode |
|---|---|---|---|
| Nginx / Gateway | HTTPS redirect, HSTS, CSP, CORS, route allowlist, rate-limit behavior, actuator exposure | response headers, status, gateway logs | passive now; rate-limit test staging only |
| Identity | JWT signature/expiry/issuer/audience, refresh rotation, logout blacklist, Buyer/Seller/Admin matrix, IDOR | token claims, API status, audit events | fixture required |
| Catalog | validation, SKU uniqueness, stock race, cache invalidation, seller ownership | DB before/after, API trace | fixture required |
| Order | cart TTL, multi-seller split, Saga forward/compensation, duplicate submit, DLQ retry | order state transitions, message IDs, DB invariants | fixture required |
| Payment | VNPay sandbox signature, replay/duplicate callback, escrow lifecycle, ledger debit=credit | sandbox callback, ledger rows, reconciliation | sandbox only |
| Shipping | GHN/GHTK signature, duplicate webhook, invalid state transition | webhook request ID, shipment state | simulator/staging only |
| Notification AI | prompt injection, data boundary, unsafe HTML/URL, hallucinated product/price, PII exposure | prompt/response pair, policy decision | staging with synthetic data |
| Frontend | buyer/seller/admin routes, form validation, console errors, keyboard, mobile layout | screenshots, console, accessibility tree | safe browser checks |
| Data/infrastructure | MySQL least privilege, Redis auth/network, RabbitMQ ACL/DLQ, MinIO bucket policy, Grafana/Prometheus/Zipkin exposure | config and network evidence | source/config review |

## Required invariants

1. Double-entry ledger: sum(debits) = sum(credits) per currency and transaction.
2. Escrow: seller payout cannot exceed captured/settled funds; refund and payout are mutually consistent.
3. Idempotency: repeating the same client request/webhook does not duplicate orders, ledger entries, shipments or notifications.
4. Saga: each failed step has a compensating transition; no permanently inconsistent intermediate state.
5. Stock: concurrent checkout cannot oversell beyond the defined reservation policy.
6. Authorization: every object-level endpoint verifies tenant/shop/user ownership, not merely authentication.
7. AI: model output is untrusted; no direct SQL, tool execution, price mutation or cross-user context leakage.

## Test data required

Use staging-only buyer, seller and admin accounts; synthetic products and stock; VNPay sandbox credentials; GHN/GHTK test credentials or simulator; disposable orders; deterministic RabbitMQ test queues; isolated database snapshots. Never use real customer PII or real-money payment methods.
