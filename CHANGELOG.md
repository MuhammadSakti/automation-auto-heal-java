# Changelog

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
