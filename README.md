# Crimson HTTP

**A syntax-highlighted HTTP viewer for OWASP ZAP with automatic redaction and export capabilities for penetration testing documentation.**

![Crimson HTTP](https://img.shields.io/badge/version-1.0.0--beta-red)
![ZAP](https://img.shields.io/badge/ZAP-2.17.0%2B-blue)
![License](https://img.shields.io/badge/license-Apache%202.0-green)

---

## Overview

Crimson HTTP is an OWASP ZAP add-on that provides enhanced viewing and documentation capabilities for HTTP traffic. It replaces ZAP's default plain text viewers with a syntax-highlighted display, improving readability of requests and responses during security assessments.

The add-on formats headers, JSON, and XML content with appropriate color coding, making it easier to identify patterns and anomalies during testing.

---

## Use Cases

### Penetration Testing Documentation

Documenting vulnerabilities for clients requires accurate, properly formatted HTTP traffic data. Traditional workflows involve manual steps to prepare data for reports:

- Redacting API keys, tokens, and credentials
- Converting request/response pairs into documentation formats
- Reconstructing cURL commands for reproduction

Crimson HTTP automates these tasks, reducing documentation overhead.

### API Analysis and Testing

Minified JSON responses and unformatted XML present challenges during analysis. Crimson HTTP automatically formats and syntax-highlights these content types, improving efficiency when working with APIs.

---

## Features

### Syntax Highlighting

- **Color-coded headers**: HTTP method, URI, version, and header fields
- **JSON formatting**: Automatic pretty-printing with key/value highlighting
- **XML formatting**: Automatic pretty-printing with tag and attribute highlighting
- **Status code colors**: Visual distinction for 2xx, 3xx, 4xx, and 5xx responses

### Automatic Redaction

Crimson HTTP includes 18 built-in redaction rules that detect and mask sensitive data patterns:

- Authorization headers (Bearer, Basic, Digest, Negotiate, NTLM)
- Cookie and Set-Cookie headers
- JWT tokens
- AWS/GCP/GitHub/GitLab API keys
- Stripe secrets
- Private key blocks
- Generic password/secret assignments

Redaction can be toggled on/off, and custom regex patterns may be added through the configuration interface.

### Export Capabilities

#### Markdown Export
Requests and responses can be copied as formatted Markdown for inclusion in technical reports, bug bounty submissions, and client deliverables.

#### cURL Export
Requests can be exported as cURL commands (with redaction applied) for issue reproduction.

#### Screenshot Capture
- Single-pane or dual-pane capture options
- Light and dark mode options
- Clipboard or file export
- Configurable redaction for screenshots

### Interface Options

- **Layout modes**: Horizontal (side-by-side) or vertical (stacked) arrangement
- **Resizable divider**: Adjustable split position
- **Persistent preferences**: Layout and position settings saved across sessions

### Integration

Requests can be opened in ZAP's Request Editor via context menu for modification and replay.

---

## Installation

### From ZAP Marketplace

1. Open OWASP ZAP
2. Navigate to **Manage Add-ons**
3. Search for **Crimson HTTP**
4. Click **Install**

### Manual Installation

1. Download the latest `.zap` file from [Releases](https://github.com/crimsonwall/crimsonhttp/releases)
2. In ZAP, navigate to **Manage Add-ons** → **Install**
3. Select the downloaded file

---

## Building From Source

### Requirements

- Java 17 or higher
- Gradle 8.13+ (included via Gradle Wrapper)

### Build Instructions

```bash
git clone https://github.com/crimsonwall/crimsonhttp.git
cd crimsonhttp
./gradlew build
```

The output file `crimsonhttp-release-1.0.0.zap` is located in `build/zapAddOn/bin/`.

### Additional Build Options

```bash
./gradlew clean build     # Clean build
./gradlew build -x test    # Skip tests
./gradlew installZapAddOn  # Install to local ZAP instance
```

---

## Configuration

### Redaction Rules

Access via **Tools → Options → Crimson HTTP → Redaction**:

- Global redaction enable/disable
- Replacement text customization (default: `**REDACTED**`)
- Individual rule toggle
- Custom regex pattern addition

### Screenshot Preferences

- Light/dark mode selection
- Space optimization options
- Screenshot redaction toggle

---

## Documentation Workflow

### 1. Automatic Redaction

Unredacted request:
```
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
Cookie: session=abc123def456; user_id=12345
```

With redaction enabled:
```
Authorization: Bearer **REDACTED**
Cookie: session=**REDACTED**
```

### 2. Markdown Export

Right-click context menu provides "Copy as Markdown" option, producing formatted output suitable for technical documentation.

### 3. Screenshot Documentation

Screenshots can be captured in light or dark mode, with optional redaction applied. Output can be saved to file or copied to clipboard for direct inclusion in reports.

### 4. cURL Export

Requests can be exported as cURL commands with redaction applied, enabling reproduction without exposing sensitive data.

### 5. Layout Customization

Horizontal and vertical layout options accommodate different analysis workflows and screen configurations.

---

## Screenshots

*(Screenshots to be added: syntax highlighting, redaction interface, export options)*

---

## Contributing

Bug reports and feature requests are welcome via the [issue tracker](https://github.com/crimsonwall/crimsonhttp/issues). Pull requests are accepted.

---

## License

Apache License 2.0 © 2026 [Crimson Wall](https://crimsonwall.com)

---

## Author

Renico Koen / [Crimson Wall](https://crimsonwall.com)
