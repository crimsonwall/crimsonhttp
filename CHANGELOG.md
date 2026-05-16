# Changelog

All notable changes to CrimsonView will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.3-beta] - 2026-05-16

### Added
- Manual redaction: click-to-redact individual occurrences of selected text

## [1.0.2-beta] - 2026-05-16

### Added
- Pencil annotation mode: underline and color selected text
- Highlighter annotation mode: apply background highlight to selected text
- Annotation color picker button on the toolbar
- Reset annotations button to clear all annotations for the current message
- Annotations are stored per message and restored when navigating back
- Annotations are included in screenshots, copy current view, and markdown export
- Copy current view: captures exactly what is visible in both panes (including scroll position) using screenshot rendering settings (light mode support)
- Configurable pencil annotation color for screenshots (default: lime green)
- Configurable highlight annotation color for screenshots (default: yellow)
- Screenshot annotation color settings in plugin options dialog
- Annotation toolbar color persisted across sessions
- Dark themed toolbar and status bar with styled section labels
- Additional regular expressions for redaction rules (enhanced sensitive data detection)
- Enhanced redaction feature with configurable rules and patterns for preparing document-ready screenshots

### Fixed
- Text wrapping issues in message display
- Copy current view now uses screenshot renderer (respects light mode setting) instead of painting dark live panes
- Markdown export now produces pandoc-compatible output (removed HTML inside code fences)

## [1.0.1] - 2026-05-09

### Added
- Configurable screenshot max width (200–4096 px, default 1000 px), replacing the previous hardcoded 1200 px limit
- Option to truncate long lines in screenshots instead of wrapping them

## [1.0.0-beta] - 2026-04-24

### Added
- Initial release of CrimsonView for ZAP
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

[1.0.0-beta]: https://github.com/crimsonwall/crimsonview/releases/tag/v1.0.0-beta
[1.0.1]: https://github.com/crimsonwall/crimsonview/releases/tag/v1.0.1
[1.0.2-beta]: https://github.com/crimsonwall/crimsonview/releases/tag/v1.0.2-beta
[1.0.3-beta]: https://github.com/crimsonwall/crimsonview/releases/tag/v1.0.3-beta
