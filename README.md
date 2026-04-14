# Auto-Heal

A Maven library that auto-heals broken locators in Playwright and Selenium tests using AI. When a locator breaks due to UI changes, the library captures the current page DOM, sends it to an AI provider with a human-readable description, and returns a working locator.

## Why This Exists

Broken locators remain the biggest pain point in UI test automation:

- **40-60%** of test maintenance effort is spent fixing broken selectors
- **25-40%** of automation engineers' time goes to maintenance — mostly locator fixes
- **60-70%** of CI/CD test failures come from flaky tests, with locator breakage as a leading cause

Modern frameworks have improved resilience, but the problem persists because UIs change frequently, teams lack `data-testid` discipline, and frontend frameworks generate unstable hashed class names and dynamic IDs.

## Features

- **AI-Powered Healing** — Supports Claude, OpenAI, and Gemini as AI providers
- **Playwright & Selenium** — Dedicated entry point for each framework
- **Smart Fallback** — Tries original locator → cache → AI healing
- **Caching** — Previously healed locators are cached to avoid redundant AI calls
- **Failure Analysis** — AI-powered test failure analysis with screenshot support
- **Configurable Image Quality** — Control screenshot JPEG quality (1-100) for failure analysis
- **Batch Mode** — Collect broken locators and heal them all in one AI call (Playwright & Selenium)
- **Reporting** — Generates HTML reports with summary stats, screenshots, and failure analysis
- **Report Naming** — Custom report names for better organization
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
        <version>1.3.2</version>
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

Choose the entry point for your framework:

| Entry Point | When to Use |
|-------------|-------------|
| `PlaywrightAutoHeal` | Playwright projects |
| `SeleniumAutoHeal` | Selenium projects |

### Playwright

```java
PlaywrightAutoHeal healer = PlaywrightAutoHeal.builder()
    .config(AutoHealConfig.fromEnv())
    .page(page)
    .reportName("HomePage")
    .build();

Locator element = healer.find(
    page.locator("//div[@class='old-class']"),
    "Kos name field"
);

healer.finish();
```

### Selenium

```java
SeleniumAutoHeal healer = SeleniumAutoHeal.builder()
    .config(AutoHealConfig.fromEnv())
    .driver(driver)
    .reportName("LoginPage")
    .build();

WebElement element = healer.find(
    By.xpath("//div[@class='old-class']"),
    "Search input field"
);

healer.finish();
```

### Source Auto-Fix

To enable automatic source code updates, pass source location info when calling `find()`:

```java
// Option A: explicit file + line
Locator element = healer.find(
    page.locator("//div[@class='old-class']"),
    "Kos name field",
    "src/test/java/pages/KosPage.java",
    25
);

// Option B: page object reflection (resolves source from field)
Locator element = healer.find(
    page.locator("//div[@class='old-class']"),
    "Kos name field",
    this
);
```

Set `AUTOHEAL_AUTOFIX=auto` and call `finish()` — the library will replace the old selector in your source file with the healed one.

For manual control, use `AUTOHEAL_AUTOFIX=manual` and call:

```java
List<SourceFixer.FixResult> fixes = healer.applyFixes();
```

### Failure Analysis

Analyze test failures using AI with an auto-captured screenshot:

```java
try {
    // ... test steps
} catch (Exception e) {
    // Default quality (70%)
    FailureAnalysis analysis = healer.analyzeFailure(e.getMessage());

    // Custom quality (1-100) — higher = clearer screenshot
    FailureAnalysis analysis = healer.analyzeFailure(e.getMessage(), 90);

    System.out.println(analysis.getSummary());
    System.out.println(analysis.getPossibleCauses());
    System.out.println(analysis.getSuggestions());
}
```

You can also build a `FailureContext` manually if you want full control:

```java
FailureContext context = FailureContext.builder()
    .screenshotBase64(base64Screenshot)
    .errorLog(errorLog)
    .pageUrl(currentUrl)
    .build();

FailureAnalysis analysis = healer.analyzeFailure(context);
```

Failure analysis results are included in the HTML report with expandable details and screenshots.

### Batch Mode

Collect multiple broken locators and heal them all in a single AI call.

**Playwright:**

```java
PlaywrightAutoHeal healer = PlaywrightAutoHeal.builder()
    .page(page)
    .build();

healer.captureDom();   // Capture DOM once
healer.startBatch();   // Enable batch mode

// These won't trigger AI calls yet — just collect broken locators
healer.find(page.locator("#old-1"), "Username field");
healer.find(page.locator("#old-2"), "Password field");
healer.find(page.locator("#old-3"), "Submit button");

// Heal all collected locators in one AI call
Map<String, Locator> healed = healer.flushBatch();
```

**Selenium:**

```java
SeleniumAutoHeal healer = SeleniumAutoHeal.builder()
    .driver(driver)
    .build();

healer.captureDom();   // Capture DOM once
healer.startBatch();   // Enable batch mode

// These return null during batch mode — broken locators are collected
healer.find(By.id("old-1"), "Username field");
healer.find(By.id("old-2"), "Password field");
healer.find(By.id("old-3"), "Submit button");

// Heal all collected locators in one AI call
Map<String, WebElement> healed = healer.flushBatch();
```

### Cross-Class Dashboard

After a full suite run, aggregate every per-class report into a single dashboard showing failed-class %, locators checked, failed %, skipped (`analyzeFailure()`) count, total running time, and tokens used — with a drill-down button that opens each per-class HTML.

Since every per-class call writes into the same `reportPath/run_<timestamp>/` folder for the lifetime of the JVM, generating the dashboard is a one-liner from `@AfterSuite`:

```java
@AfterSuite
public void generateReportDashboard() {
    PlaywrightAutoHeal.generateReportDashboard(AutoHealConfig.fromEnv());
    // or: SeleniumAutoHeal.generateReportDashboard(AutoHealConfig.fromEnv());
}
```

The resulting layout looks like:

```
autoheal-reports/
├── .autoheal-cache.json                 # persistent cache, unchanged location
├── dashboard.html                       # aggregates ALL run_* folders
├── run_20260410_103045_123/
│   ├── AutoHeal_HomePage_*.html
│   ├── AutoHeal_HomePage_*.json
│   ├── AutoHeal_InventoryPage_*.html
│   └── AutoHeal_InventoryPage_*.json
└── run_20260411_150000_000/
    ├── AutoHeal_LoginPage_*.html
    └── AutoHeal_LoginPage_*.json
```

**Forked JVMs**: if you run Surefire with `forkCount > 1`, each fork is a separate JVM and will create its own `run_*` folder by default. Set `AUTOHEAL_RUN_ID` so every fork shares one run folder:

```bash
mvn test -DAUTOHEAL_RUN_ID=ci-${BUILD_NUMBER}
```

**Migration note**: prior to 1.3.0, per-class reports were written directly under `reportPath/`. They now live under `reportPath/run_<timestamp>/`. The `.autoheal-cache.json` file is unchanged — still at the root of `reportPath`.

**Known limitation**: un-flushed batch-mode pending heals are not automatically flushed on `finish()`. Call `flushBatch()` explicitly before `finish()` if you use batch mode.

## Healing Flow

1. **Original** — Try the original locator. If found and visible, return it.
2. **Cached** — Check if this locator was healed before. Try the cached selector.
3. **DOM Heal** — Extract a clean DOM snapshot, send it to the AI provider with the element description, and get back a new selector.
4. If all strategies fail, throw an exception with details.

## Reports

After calling `healer.finish()` or `healer.generateReport()`, reports are generated at the configured path:

- `AutoHeal_{ReportName}_{timestamp}.html` — Visual report with summary cards, detail table, failure analysis, and screenshots

Use `.reportName("HomePage")` on the builder for descriptive file names. Without it, only the timestamp is used.

Each record includes: original selector, actual selector, strategy used, status, time, tokens consumed, AI reasoning, and source location. Failure analysis records include summary, possible causes, suggestions, and a collapsible screenshot.

## Project Structure

```
src/main/java/com/autoheal/
├── PlaywrightAutoHeal.java    # Playwright entry point with builder
├── SeleniumAutoHeal.java      # Selenium entry point with builder
├── AutoHealFactory.java       # AI provider factory
├── config/
│   └── AutoHealConfig.java    # Configuration (env / .env / builder)
├── ai/
│   ├── AIProvider.java        # Provider interface
│   ├── AIResponse.java        # Response DTO
│   ├── FailureContext.java    # Failure analysis input
│   ├── FailureAnalysis.java   # Failure analysis result
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
│   └── ReportGenerator.java   # HTML report generation
├── fixer/
│   └── SourceFixer.java       # Auto-fix source files
└── util/
    ├── DomExtractor.java      # Clean DOM extraction
    └── ScreenshotUtil.java    # Screenshot compression and encoding
```

## Requirements

- Java 17+
- Playwright or Selenium (provided scope — bring your own)
- An API key for at least one AI provider

## License

MIT
