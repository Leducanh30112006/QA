# Scanner integration contract

The default worker does not invoke active scanners. Keep these integrations in separate, disposable workers with network egress restricted to the approved target.

- OWASP ZAP: baseline/passive scan first; active scan only against staging after approval.
- Nuclei: pinned template version, reviewed template allowlist, rate limits, no destructive templates.
- k6: staging only, explicit virtual-user/duration/threshold budget; never start from the normal safe-scan button.
- Semgrep/Trivy: run in CI against source/image artifacts; do not grant the browser worker Docker socket access.
- Gemini: send minimal sanitized evidence, never secrets, cookies, access tokens or customer data. Treat responses as advisory.

Every adapter must return normalized findings: category, severity, title, description, target, evidence, confidence, scanner, timestamp and remediation reference.
