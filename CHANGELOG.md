# Changelog

All notable changes to Crimson HTTP will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0-beta] - 2026-04-24

### Added
- Initial release of Crimson HTTP for Zap
- Beautiful syntax-highlighted HTTP request/response viewer
- Color-coded headers and status codes (2xx/3xx/4xx/5xx)
- Automatic JSON and XML pretty-printing
- Horizontal and vertical layout modes with resizable divider
- Persistent layout and divider position preferences
- 18 built-in redaction rules for sensitive data patterns:
  - Authorization headers (Bearer, Basic, Digest, Negotiate, NTLM)
  - Cookie and Set-Cookie headers
  - WWW-Authenticate and Proxy-Authorization headers
  - X-API-Key headers
  - Bearer tokens and JWT tokens
  - AWS access/secret keys
  - GCP API keys
  - GitHub/GitLab personal access tokens
  - Slack tokens
  - Stripe secret keys
  - Private key blocks
  - Generic password/secret assignments
- Custom regex pattern support for redaction rules
- Redaction rules management UI in ZAP options
- Copy as Markdown (formatted for pentest reports)
- Copy as cURL command (with redaction support)
- Screenshot capture (single pane or both panes)
- Screenshot light/dark mode option
- Screenshot space optimization option
- Screenshot redaction toggle
- Screenshot save to file with directory persistence
- Screenshot copy to clipboard
- Integration with ZAP's Request Editor for resend functionality
- ZAP 2.17.0+ compatibility

### Fixed
- Initial release - no prior issues

[1.0.0-beta]: https://github.com/crimsonwall/crimsonhttp/releases/tag/v1.0.0-beta
