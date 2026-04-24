# Crimson HTTP

**A beautiful, syntax-highlighted HTTP viewer for OWASP ZAP with automatic redaction and pentest-ready export.**

![Crimson HTTP](https://img.shields.io/badge/version-1.0.0--beta-red)
![ZAP](https://img.shields.io/badge/ZAP-2.17.0%2B-blue)
![License](https://img.shields.io/badge/license-Apache%202.0-green)

---

## What is Crimson HTTP?

Crimson HTTP is an OWASP ZAP add-on that transforms how you view and document HTTP traffic. It replaces ZAP's default plain text viewers with a rich, syntax-highlighted display that makes requests and responses instantly readable.

**Gone are the days of squinting at monochromatic HTTP dumps.** Crimson HTTP color-codes headers, syntax-highlights JSON/XML bodies, and formats everything perfectly—right inside ZAP.

---

## Why You Need It

### You're a penetration tester who documents findings

You've been there: hours into an assessment, you've found an interesting vulnerability, and now you need to document it for the client. You're copying raw HTTP from ZAP, pasting it into your report, and then spending valuable time manually:

- Formatting the mess into something readable
- Redacting API keys, tokens, and credentials by hand
- Converting request/response pairs into Markdown or screenshots
- Hunting through logs to reconstruct that one curl command

**Crimson HTTP eliminates all of this friction.**

### You work with APIs daily

JSON responses come back minified and unreadable. XML is a wall of text. Headers run together in a jumbled mess. You need to see structure, spot anomalies, and understand what's actually happening—fast.

Crimson HTTP pretty-prints JSON and XML automatically, highlights syntax, and makes patterns jump out at you.

---

## Why It's Amazing

### 🎨 Beautiful Syntax Highlighting

- **Color-coded headers**: Method, URI, version, and each header field gets distinct colors
- **JSON formatting**: Auto-detects and pretty-prints with key/value highlighting
- **XML formatting**: Pretty-prints XML with tag and attribute highlighting
- **Status code colors**: 2xx (green), 3xx (yellow), 4xx (red), 5xx (dark red)

### 🔒 Automatic Redaction (Pentest Game-Changer)

**This is where Crimson HTTP transforms your workflow.**

During penetration tests, you capture requests containing real credentials, API tokens, session cookies, and other sensitive data. Including these in your report—unredacted—is a security violation.

Crimson HTTP includes **18 built-in redaction rules** that automatically detect and mask sensitive patterns:

- Authorization headers (Bearer, Basic, Digest, etc.)
- Cookie and Set-Cookie headers
- JWT tokens
- AWS/GCP/GitHub/GitLab API keys
- Stripe secrets
- Private keys
- Generic password/secret assignments

**Toggle redaction on/off instantly.** Copy redacted content to your report with confidence. No more manual find-replace, no more accidental data leaks.

### 📋 Pentest-Ready Export

One click and you have **publication-ready content** for your report:

#### Copy as Markdown
```
## Request

```http
POST /api/v1/users HTTP/1.1
Host: example.com
Content-Type: application/json
Authorization: Bearer **REDACTED**
```

```json
{
  "username": "test@example.com",
  "password": "**REDACTED**"
}
```

## Response

```http
HTTP/1.1 201 Created
Content-Type: application/json
```

```json
{
  "id": 12345,
  "status": "created"
}
```
```

**Perfect for:**
- Technical writeups
- Bug bounty submissions
- Client deliverables
- Documentation

#### Copy as cURL
```bash
curl -X POST -H 'Content-Type: application/json' -H 'Authorization: Bearer **REDACTED**' -d '{"username":"test@example.com","password":"**REDACTED**"}' 'https://example.com/api/v1/users'
```

**Reproduce issues instantly** without exposing credentials.

#### Screenshot Capture
- **Single pane**: Just request or just response
- **Both panes**: Request and response side-by-side
- **Light or dark mode**: Match your report's aesthetic
- **Copy to clipboard or save to file**: Drag directly into your report

### 🔄 Flexible Layout
- **Horizontal**: Request left, response right
- **Vertical**: Request top, response bottom
- **Resizable divider**: Drag to your preferred split
- **Persistent**: Remembers your layout across ZAP sessions

### ⚡ Request Editor Integration
Right-click any request → "Resend in Request Editor" to modify and replay with ZAP's manual request editor.

---

## Installation

### From ZAP Marketplace (Recommended)

1. Open OWASP ZAP
2. Go to **Manage Add-ons**
3. Search for **Crimson HTTP**
4. Click **Install**

### Manual Installation

1. Download the latest `.zap` file from [Releases](https://github.com/crimsonwall/crimsonhttp/releases)
2. In ZAP, go to **Manage Add-ons** → **Install**
3. Browse to the downloaded file and install

---

## Building From Source

Want to build Crimson HTTP yourself? You'll need:

- Java 17 or higher
- Gradle 8.13+ (included via Gradle Wrapper)

```bash
# Clone the repository
git clone https://github.com/crimsonwall/crimsonhttp.git
cd crimsonhttp

# Build the add-on
./gradlew build

# Find the generated .zap file
ls build/zapAddOn/bin/
```

The build produces `crimsonhttp-release-1.0.0.zap` in `build/zapAddOn/bin/`.

### Build Options

```bash
# Clean build
./gradlew clean build

# Skip tests
./gradlew build -x test

# Build and install to local ZAP
./gradlew installZapAddOn
```

---

## Configuration

### Redaction Rules

Go to **Tools → Options → Crimson HTTP → Redaction** to:

- Enable/disable redaction globally
- Customize replacement text (default: `**REDACTED**`)
- Toggle individual rules on/off
- Add custom regex patterns

### Screenshot Preferences

- **Light mode screenshots**: For reports with white backgrounds
- **Optimize space**: Tighter cropping vs. consistent sizing
- **Redact screenshots**: Apply redaction rules to screenshots

---

## Why Crimson HTTP is Perfect for Pentest Documentation

### 1. **Safety First: Automatic Redaction**

During a penetration test, you might intercept:
```
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
Cookie: session=abc123def456; user_id=12345
```

**With Crimson HTTP redaction enabled:**
```
Authorization: Bearer **REDACTED**
Cookie: session=**REDACTED**
```

**One click.** No manual editing. No accidental credential leaks in your report.

### 2. **Copy-Paste Ready**

Your report template needs formatted HTTP. You could:

**Option A:** Spend 20 minutes manually formatting raw ZAP output.

**Option B:** Right-click → "Copy as Markdown" → Paste. Done in 2 seconds.

### 3. **Professional Screenshots**

Sometimes you need visual evidence. Screenshots from ZAP's default viewer look dated and are hard to read.

Crimson HTTP screenshots are:
- Clean and modern
- Syntax-highlighted for readability
- Available in light/dark mode
- Properly sized and formatted

Drag them straight into Word, PowerPoint, or your bug report.

### 4. **cURL for Reproducibility**

Clients love it when they can reproduce findings. Copy the request as cURL (redacted, of course), and they can run it themselves:

```bash
curl -X POST -H 'Authorization: Bearer **REDACTED**' 'https://api.example.com/v1/users'
```

### 5. **Layout for Your Workflow**

Prefer seeing request and response stacked while analyzing vertical JSON structures? Switch to vertical.

Need to compare headers side-by-side? Switch to horizontal.

Crimson HTTP adapts to how *you* work.

---

## Screenshots

*(Add screenshots here showing: dark mode syntax highlighting, redaction in action, Markdown export, screenshot dialog)*

---

## Contributing

Found a bug? Have a feature idea? We'd love to hear from you!

1. Check existing [Issues](https://github.com/crimsonwall/crimsonhttp/issues)
2. Open a new issue with details
3. Pull requests are welcome!

---

## License

Apache License 2.0 © 2026 [Crimson Wall](https://crimsonwall.com)

---

## Author

**Renico Koen** / [Crimson Wall](https://crimsonwall.com)

---

**Stop wrestling with raw HTTP. Start seeing clearly.**

Install Crimson HTTP and transform your ZAP experience today.
