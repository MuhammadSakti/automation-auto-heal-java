# Changelog

## [1.4.2] - 2026-04-16

### Added
- **SourceFixer handles full Playwright locator builder API**: `getByRole` (with `setName()` and other options), `getByText`, `getByLabel`, `getByPlaceholder`, `getByAltText`, `getByTitle`, and `.locator(...).filter(...)` chains are now auto-rewritten to `obj.locator("newSelector")` when the AI returns a non-builder selector. The object variable name (`page`, `pageInventory`, etc.) is preserved. Trailing `.filter(...)` calls chained onto any `getByX` locator are also consumed so the replacement doesn't leave orphaned filter calls.
- **SourceFixer rewrites Selenium By calls with type switching**: the rewriter now parses `By.<type>: value` from `By.toString()` and rewrites the specific `By.<type>("value")` call, inferring the correct new `By` type (xpath vs. cssSelector) from the healed selector. Fixes a pre-existing bug where healing `By.id("login")` with an XPath produced `By.id("//button[@data-qa='login']")` at the source level. All 8 standard Selenium By types are supported (id, name, xpath, cssSelector, className, tagName, linkText, partialLinkText).
- **Unit tests for `SourceFixer`**: 26 tests covering every supported Playwright & Selenium locator format plus regression cases for variable-name corruption and mismatched keys.

### Changed
- **`SourceFixer.rewriteGetByCall` uses balanced-paren scanning**: previously the rewriter used a flat regex that broke on nested method calls like `new Page.GetByRoleOptions().setName(...)`. It now walks string-aware paren depth to find the end of the call.

## [1.4.1] - 2026-04-16

### Changed
- **Renamed `AutoHealConfig.fromEnv()` → `AutoHealConfig.load()`**: the old name implied `.env`-only loading, which hasn't been accurate since v1.4.0. `fromEnv()` remains as a `@Deprecated` delegate for backward compatibility.

## [1.4.0] - 2026-04-16

### Changed
- **Replace dotenv-java with autoheal.properties**: configuration is now loaded from a standard Java properties file (`autoheal.properties` on classpath) instead of `.env` via `dotenv-java`. The `dotenv-java` dependency has been removed. Environment variables still take highest precedence. New `-D` system property support added (e.g. `-Dautoheal.ai.provider=openai`). Both idiomatic property keys (`autoheal.ai.provider`) and env-style keys (`AUTOHEAL_AI_PROVIDER`) are accepted in the properties file.

### Removed
- `dotenv-java` dependency — no longer needed; config uses `java.util.Properties` (built-in JDK)

### Migration
- Rename `.env` to `src/test/resources/autoheal.properties` and convert `KEY=value` lines to `key.name=value` format (env-style keys also work as-is in the properties file)

## [1.3.5] - 2026-04-15

### Fixed
- **SourceFixer preserves page variable name**: `rewriteTestIdCall` now captures the object reference (e.g. `page`, `page1`, `pageInventory`) instead of hardcoding `page.locator(...)`
- **SourceFixer no longer corrupts variable names**: test-id replacement now targets only the string literal inside `byTestId()`/`getByTestId()` calls via regex, preventing `this.header` from becoming `this.app-header`

### Changed
- **Refactored SourceFixer to remove arrow antipattern**: extracted `tryReplaceLine()` for replacement logic and `skip()` helper, flattening nested if/try blocks in `fixRecord()`

## [1.3.4] - 2026-04-14

### Fixed
- **SourceFixer handles Playwright getByTestId/byTestId locators**: `extractSelectorFromOriginal` now extracts the test-id value from Playwright's `internal:attr=[data-testid="..."]` format and matches it against `byTestId("...")` calls in source. When the healed selector is also a test-id, only the value is replaced; otherwise the call is rewritten to `page.locator("...")`

## [1.3.3] - 2026-04-14

### Changed
- **Dashboard splits Passed vs Healed**: the single "Successful" count is now two cards — **Passed** (original locator worked) and **Healed** (AI-fixed or cached). Table columns and run summaries updated accordingly with a new "Heal %" metric
- **Stack-trace source resolution**: `find(locator, description)` without a page object now resolves the caller's source file and line from the stack trace, enabling `SourceFixer` autofix for raw locator usage

## [1.3.2] - 2026-04-10

### Changed
- **Dashboard moved to reportPath root**: `dashboard.html` is now written to the root of `reportPath` (e.g. `target/autoheal-reports/dashboard.html`) instead of inside a single `run_*` subfolder
- **Dashboard aggregates all runs**: scans every `run_*` subdirectory and renders them as collapsible groups (newest first, expanded by default). Previous runs remain visible unless manually cleared
- **Relative detail links**: "View Details" links use relative paths (e.g. `run_20260410_103955_732/AutoHeal_HomePage_*.html`) so they resolve correctly from the root

## [1.3.1] - 2026-04-10

### Fixed
- **Dashboard silently dropping failure-analysis reports**: `FailureAnalysis` had no Jackson creator, so any per-class JSON containing an `analyzeFailure()` record failed to deserialize and the whole class was skipped from the dashboard. Added `@JsonCreator`/`@JsonProperty` to `FailureAnalysis` with null-safe list handling
- **Dashboard misclassifying setup-failure classes**: a class whose setup errored out (only failure-analysis records, zero locator checks) was shown as `passed`. `ReportDashboardGenerator` now marks a class failed when `failed > 0 || skipped > 0` — any explicit `analyzeFailure()` call signals a class failure

## [1.3.0] - 2026-04-10

### Added
- **Cross-class dashboard**: `ReportDashboardGenerator` + `PlaywrightAutoHeal.generateReportDashboard(config)` / `SeleniumAutoHeal.generateReportDashboard(config)` — aggregate every per-class report produced during a run into one `dashboard.html` with failed-class %, locators checked, failed %, skipped count, total time, total tokens, and a per-class drill-down
- **Dated run subfolders**: per-class HTML/JSON reports are now written to `reportPath/run_<timestamp>/` so a single run's artifacts stay grouped. `HealCache` still writes `.autoheal-cache.json` to the root of `reportPath` — unchanged
- **`AUTOHEAL_RUN_ID` env var**: override the run folder name (recommended for forked Surefire JVMs so every fork shares one run folder)
- **`HealRecord.Kind` enum** (`LOCATOR` / `FAILURE_ANALYSIS`): makes the "skipped" classification explicit instead of inferring it from `failureAnalysis != null`

### Changed
- **Report layout**: per-class reports moved from `reportPath/` to `reportPath/run_<timestamp>/`. Consumers that scrape the flat layout should migrate to the new subfolder structure

## [1.2.0] - 2026-04-02

### Added
- **Batch mode for Selenium**: `captureDom()`, `startBatch()`, and `flushBatch()` on `SeleniumAutoHeal` and `SeleniumHealer` — collect broken locators and heal them all in a single AI call, matching the existing Playwright batch mode

## [1.1.2] - 2026-04-02

### Added
- **Configurable image quality**: `analyzeFailure(String errorLog, int imageQuality)` overloads on `PlaywrightAutoHeal`, `SeleniumAutoHeal`, and `AutoHeal` — accepts JPEG quality percentage (1-100) for clearer screenshots in reports
- `ScreenshotUtil.compressToBase64(byte[], int)` and `ScreenshotUtil.compressBase64(String, int)` for custom quality compression
- Javadoc comments on all public methods across `PlaywrightAutoHeal`, `SeleniumAutoHeal`, and `AutoHeal`

## [1.1.1] - 2026-04-02

### Added
- **Screenshot in reports**: Failure screenshots are now embedded in the HTML report as collapsible images — click "Show Screenshot" to view what the page looked like when the failure occurred

### Fixed
- **Time card overflow**: Total Time card no longer overflows its container; formatted time uses a smaller font-size to fit within the card

### Changed
- `FailureContext.build()` no longer requires `screenshotBase64` — error-log-only analysis (without a screenshot) is now a valid use case
- `HealRecord` has optional `screenshotBase64` field (omitted from JSON when absent)

## [1.1.0] - 2026-04-02

### Added
- **Report naming**: `.reportName(String)` on all builders — generates files like `AutoHeal_HomePage_20260402_194401.html` instead of generic timestamps
- **Human-readable time**: Report times now display as `HH:mm:ss.SSS` instead of raw milliseconds
- **Failure analysis in reports**: `analyzeFailure()` results are now recorded and appear as expandable rows in HTML reports with summary, possible causes, and suggestions
- **Failure analysis**: AI-powered test failure analysis with screenshot compression (`analyzeFailure(String errorLog)` and `analyzeFailure(FailureContext)` on all entry points)
- **Screenshot compression**: `ScreenshotUtil` reduces screenshot size before sending to AI providers

### Changed
- `ReportGenerator` constructor accepts optional `reportName` parameter
- `HealRecord` has optional `failureAnalysis` field
- Report template timestamp displays as `yyyy-MM-dd HH:mm:ss` instead of `yyyyMMdd_HHmmss`
