# Auto-Heal

A Maven library that auto-heals broken locators in Playwright and Selenium tests using AI. When a locator breaks due to UI changes, the library captures the current page DOM, sends it to an AI provider with a human-readable description, and returns a working locator.

## Features

- **AI-Powered Healing** — Supports Claude, OpenAI, and Gemini as AI providers
- **Playwright & Selenium** — Works with both frameworks
- **Smart Fallback** — Tries original locator → cache → AI healing
- **Caching** — Previously healed locators are cached to avoid redundant AI calls
- **Reporting** — Generates JSON and HTML reports with summary stats
- **Source Auto-Fix** — Optionally updates your page object source files with healed selectors

## Installation

### Maven (GitHub Packages)

Add the repository and dependency to your `pom.xml`:

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/MuhammadSakti/automation-auto-heal-java</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>com.autoheal</groupId>
        <artifactId>auto-heal</artifactId>
        <version>1.0.0</version>
    </dependency>
</dependencies>
```

> GitHub Packages requires authentication. Add your GitHub token to `~/.m2/settings.xml`:
> ```xml
> <servers>
>     <server>
>         <id>github</id>
>         <username>YOUR_GITHUB_USERNAME</username>
>         <password>YOUR_GITHUB_TOKEN</password>
>     </server>
> </servers>
> ```

## Configuration

Create a `.env` file in your project root or set environment variables:

```env
AUTOHEAL_AI_PROVIDER=claude          # claude / openai / gemini
AUTOHEAL_AI_API_KEY=your-api-key
AUTOHEAL_AI_MODEL=claude-sonnet-4-6  # or gpt-4o, gemini-2.0-flash
AUTOHEAL_REPORT_PATH=./autoheal-reports/
AUTOHEAL_AUTOFIX=off                 # off / auto / manual
AUTOHEAL_CACHE_ENABLED=true
```

Or configure programmatically:

```java
AutoHealConfig config = AutoHealConfig.builder()
    .aiProvider(AutoHealConfig.AiProvider.CLAUDE)
    .aiApiKey("your-api-key")
    .aiModel("claude-sonnet-4-6")
    .reportPath("./autoheal-reports/")
    .autoFix(AutoHealConfig.AutoFixMode.OFF)
    .cacheEnabled(true)
    .build();
```

## Usage

### Playwright

```java
AutoHeal healer = AutoHeal.builder()
    .config(AutoHealConfig.fromEnv())
    .playwrightPage(page)
    .build();

// The healer tries the original locator first.
// If it fails, it checks cache, then asks AI to find the element.
Locator element = healer.find(
    page.locator("//div[@class='old-class']"),
    "Kos name field showing 'Kos Haji Noval Bekasi'"
);

// With source file info (for auto-fix)
Locator element = healer.find(
    page.locator("//div[@class='old-class']"),
    "Kos name field",
    "src/test/java/pages/KosPage.java",
    25
);

// After test run — generates report and applies fixes if autofix=auto
healer.finish();
```

### Selenium

```java
AutoHeal healer = AutoHeal.builder()
    .config(AutoHealConfig.fromEnv())
    .seleniumDriver(driver)
    .build();

WebElement element = healer.find(
    By.xpath("//div[@class='old-class']"),
    "Search input field"
);

healer.finish();
```

## Healing Flow

1. **Original** — Try the original locator. If found and visible, return it.
2. **Cached** — Check if this locator was healed before. Try the cached selector.
3. **DOM Heal** — Extract a clean DOM snapshot, send it to the AI provider with the element description, and get back a new selector.
4. If all strategies fail, throw an exception with details.

## Reports

After calling `healer.finish()` or `healer.generateReport()`, reports are generated at the configured path:

- `AutoHeal_{timestamp}.json` — Machine-readable report
- `AutoHeal_{timestamp}.html` — Visual report with summary cards and detail table

Each record includes: original selector, actual selector, strategy used, status, time, tokens consumed, AI reasoning, and source location.

## Source Auto-Fix

When `AUTOHEAL_AUTOFIX=auto`, the library automatically updates your page object files after the test run, replacing broken selectors with healed ones at the exact source line.

For manual control, use `AUTOHEAL_AUTOFIX=manual` and call:

```java
List<SourceFixer.FixResult> fixes = healer.applyFixes();
```

## Project Structure

```
src/main/java/com/autoheal/
├── AutoHeal.java              # Main entry point with builder
├── config/
│   └── AutoHealConfig.java    # Configuration (env / .env / builder)
├── ai/
│   ├── AIProvider.java        # Provider interface
│   ├── AIResponse.java        # Response DTO
│   ├── ClaudeProvider.java    # Anthropic Claude API
│   ├── OpenAIProvider.java    # OpenAI API
│   └── GeminiProvider.java    # Google Gemini API
├── finder/
│   ├── HealResult.java        # Result with strategy/timing/tokens
│   ├── PlaywrightHealer.java  # Playwright healing logic
│   └── SeleniumHealer.java    # Selenium healing logic
├── cache/
│   └── HealCache.java         # In-memory + file-persisted cache
├── reporter/
│   ├── HealRecord.java        # Record for reporting
│   └── ReportGenerator.java   # JSON + HTML report generation
├── fixer/
│   └── SourceFixer.java       # Auto-fix source files
└── util/
    └── DomExtractor.java      # Clean DOM extraction
```

## Requirements

- Java 17+
- Playwright or Selenium (provided scope — bring your own)
- An API key for at least one AI provider

## License

MIT
