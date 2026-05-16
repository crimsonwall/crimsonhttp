# CrimsonView

**Document-ready screenshots for penetration testers, without the pain.**

![CrimsonView](https://img.shields.io/badge/version-1.0.3--beta-red)
![ZAP](https://img.shields.io/badge/ZAP-2.17.0%2B-blue)
![License](https://img.shields.io/badge/license-Apache%202.0-green)

---

## Overview

Let's be honest: nobody got into penetration testing because they love documenting findings. Editing screenshots isn't anyone's idea of fun either. But clients expect professional reports with properly formatted, redacted evidence.

CrimsonView exists to make that part less painful. It's a ZAP add-on that prepares everything for document-ready screenshots—syntax highlighting, automatic redaction of sensitive data, annotations, and light/dark theme export—all while you work.

### Why the dark theme?

Pentesters often work through the night to meet tight deadlines. The default dark theme is easy on the eyes during those late-night sessions. But when it's time to generate documentation, screenshots export to a professional light theme that looks great in client reports.

CrimsonView replaces ZAP's default plain text viewers with a syntax-highlighted display, improving readability of requests and responses during security assessments while automatically preparing them for documentation.

---

## Use Cases

### Document-Ready Screenshots Without the Pain

Every pentester knows the drill: find a vulnerability, get excited, then remember you need to document it. The traditional workflow is painfu
- Create screenshots
- Fire up an image editor to crop and format screenshots
- Manually redact API keys, tokens, and credentials
- Hope you didn't miss anything sensitive

CrimsonView handles all of this in real-time while you work. Capture exactly what you need, pre-redacted and annotated, then export to a light theme that looks professional in client deliverables.

### API Analysis and Testing

Minified JSON responses and unformatted XML present challenges during analysis. CrimsonView automatically formats and syntax-highlights these content types, improving efficiency when working with APIs.

---

## Features

### Syntax Highlighting

- **Color-coded headers**: HTTP method, URI, version, and header fields
- **JSON formatting**: Automatic pretty-printing with key/value highlighting
- **XML formatting**: Automatic pretty-printing with tag and attribute highlighting
- **Status code colors**: Visual distinction for 2xx, 3xx, 4xx, and 5xx responses

### Automatic Redaction for Document-Ready Screenshots

CrimsonView includes 18 built-in redaction rules that detect and mask sensitive data patterns in real-time:

- Authorization headers (Bearer, Basic, Digest, Negotiate, NTLM)
- Cookie and Set-Cookie headers
- JWT tokens
- AWS/GCP/GitHub/GitLab API keys
- Generic password/secret assignments

Redaction is applied automatically when capturing screenshots, so your evidence is ready for client reports without manual editing. Toggle redaction on/off and add custom regex patterns through the configuration interface.

### Annotations

Mark up request and response text directly in the viewer:

- **Pencil mode**: Underline and color selected text for emphasis
- **Highlighter mode**: Apply background highlighting to selected text
- **Color picker**: Choose any annotation color via the toolbar
- **Persistent color**: Selected annotation color is saved across sessions
- **Reset**: Clear all annotations for the current message with one click

Annotations are stored in-memory per message and automatically restored when navigating back to a previously annotated message.

### Export Capabilities

#### cURL Export
Requests can be exported as cURL commands for issue reproduction.

#### Screenshot Capture (Document-Ready Export)
- Single-pane or dual-pane capture options
- **Copy current view**: Captures exactly what is visible in both panes (including scroll position) using screenshot rendering settings
- **Light mode for professional reports**: Export screenshots in a clean light theme that looks great in client documentation
- Clipboard or file export
- **Automatic redaction for screenshots**: Independent toggle ensures your exports are safe for client delivery
- Configurable pencil and highlight colors for screenshot annotations
- Optional line truncation for long lines
- Configurable maximum screenshot width (200–4096px)

Annotations are included in all export paths: screenshots, copy current view, and markdown.

### Interface Options

- **Layout modes**: Horizontal (side-by-side) or vertical (stacked) arrangement
- **Resizable divider**: Adjustable split position
- **Persistent preferences**: Layout, divider position, and annotation color saved across sessions
- **Dark theme**: Consistent dark UI with styled toolbar and status bar

### Integration

Requests can be opened in ZAP's Request Editor via context menu for modification and replay.

---

## Installation

### From ZAP Marketplace

1. Open ZAP
2. Navigate to **Manage Add-ons**
3. Search for **CrimsonView**
4. Click **Install**

### Manual Installation

1. Download the latest `.zap` file from [Releases](https://github.com/crimsonwall/crimsonview/releases)
2. In ZAP, navigate to **Manage Add-ons** → **Install**
3. Select the downloaded file

---

## Building From Source

### Requirements

- Java 17 or higher
- Gradle 8.13+ (included via Gradle Wrapper)

### Build Instructions

```bash
git clone https://github.com/crimsonwall/crimsonview.git
cd crimsonview
./gradlew build
```

The output file `crimsonview-release-1.0.3.zap` is located in `build/zapAddOn/bin/`.

### Additional Build Options

```bash
./gradlew clean build     # Clean build
./gradlew build -x test    # Skip tests
./gradlew installZapAddOn  # Install to local ZAP instance
```

---

## Configuration

### Redaction Rules

Access via **Tools → Options → CrimsonView → Redaction**:

- Global redaction enable/disable
- Replacement text customization (default: `**REDACTED**`)
- Individual rule toggle
- Custom regex pattern addition

### Screenshot Preferences

- Light/dark mode selection
- Space optimization options
- Screenshot redaction toggle
- Pencil annotation color (default: lime green)
- Highlight annotation color (default: yellow)
- Line truncation toggle for long lines
- Maximum screenshot width (200–4096px)

### Annotation Settings

- Annotation toolbar color is persisted across sessions via the color picker button
- Annotation color preference is saved to plugin configuration automatically

---

## Documentation Workflow for Document-Ready Screenshots

### 1. Automatic Redaction (No More Manual Editing)

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

### 3. Screenshot Documentation (Light Mode for Reports)

Capture screenshots directly in light mode for professional-looking client documentation. No more post-processing or theme switching—what you export is what appears in your report, with automatic redaction applied and annotations included.

### 4. cURL Export

Requests can be exported as cURL commands with redaction applied, enabling reproduction without exposing sensitive data.

### 5. Layout Customization

Horizontal and vertical layout options accommodate different analysis workflows and screen configurations.

---

## Screenshots

*(Screenshots to be added: syntax highlighting, redaction interface, export options)*

---

## Contributing

Bug reports and feature requests are welcome via the [issue tracker](https://github.com/crimsonwall/crimsonview/issues). Pull requests are accepted.

---

## License

Apache License 2.0 © 2026 [Crimson Wall](https://crimsonwall.com)

---

## Author

Renico Koen / [Crimson Wall](https://crimsonwall.com)
