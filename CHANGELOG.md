# Changelog

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
