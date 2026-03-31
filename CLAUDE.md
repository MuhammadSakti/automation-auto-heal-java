# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
mvn compile          # Compile
mvn test             # Run tests (TestNG)
mvn package          # Build JAR
mvn clean package    # Clean build
```

No linter is configured. Tests use TestNG 7.10.2.

## Architecture

This is a Java 17 library (published to GitHub Packages) that auto-heals broken locators in Playwright and Selenium tests using AI. When a locator fails, the library extracts the DOM, sends it to an AI provider, and gets back a working selector.

### Healing Flow

```
User calls find(locator, description)
  → Try original locator (Strategy.ORIGINAL)
  → Try cached selector (Strategy.CACHED)
  → Extract DOM → Call AI provider → Try AI selector (Strategy.DOM_HEALED)
  → All fail → throw RuntimeException
```

### Entry Points (3 options)

- **`AutoHeal`** — Universal, supports both frameworks via `Object` types with runtime type checking
- **`PlaywrightAutoHeal`** — Playwright-only (no Selenium on classpath)
- **`SeleniumAutoHeal`** — Selenium-only (no Playwright on classpath)

All use builder pattern. `AutoHealFactory` creates the appropriate `AIProvider` internally.

### AI Providers

`AIProvider` interface with three implementations: `ClaudeProvider`, `OpenAIProvider`, `GeminiProvider`. All use `java.net.http.HttpClient` (zero external HTTP deps). Each sends a standardized prompt and parses "SELECTOR: X\nREASONING: Y" structured responses.

### Key Subsystems

- **`config/AutoHealConfig`** — Config resolution: env vars → `.env` file → defaults. Provider-specific API key fallbacks (e.g., `CLAUDE_API_KEY` → `AUTOHEAL_AI_API_KEY`).
- **`cache/HealCache`** — Thread-safe `ConcurrentHashMap` + file persistence to `.autoheal-cache.json`.
- **`finder/PlaywrightHealer` & `SeleniumHealer`** — Framework-specific healing logic, return `HealResult`.
- **`reporter/ReportGenerator`** — Generates timestamped JSON + HTML reports from `HealRecord` list.
- **`fixer/SourceFixer`** — Rewrites source files replacing old selectors with healed ones.
- **`util/DomExtractor`** — Extracts clean DOM from both Playwright pages and Selenium WebDrivers.

### Dependency Design

Playwright and Selenium are `provided` scope — consumers bring their own. Jackson and dotenv-java are `compile` scope (transitive to consumers).
