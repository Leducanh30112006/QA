# v2.0 architecture

```text
React dashboard
   |
Spring Boot control plane ---- PostgreSQL (projects, scans, findings)
   |
Async scan worker
   +-- SourceAuditService: Git checkout / mounted source -> module, config, secret heuristics
   +-- RuntimeProbeService: safe GET, TLS/headers/cookies/CORS/latency
   +-- DiscoveryService: Playwright bounded same-origin/path discovery
   +-- Scanner adapters (separate opt-in workers): ZAP / Nuclei / k6
   +-- Gemini analysis (optional): explain + prioritize existing evidence; never authorize actions
   |
Evidence & JSON report
```

Control plane and scanners must run in isolated containers. For production use add OIDC/JWT, role-based access, project ownership, audit trail, secrets manager, egress allowlist at network layer, DNS/IP pinning, per-target concurrency limits, worker timeouts and retention policies. AI output is untrusted and must pass deterministic policy validation before execution.
