package com.autoheal.fixer;

import com.autoheal.finder.HealResult;
import com.autoheal.reporter.HealRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SourceFixer {

    public static class FixResult {
        public final String sourceFile;
        public final int sourceLine;
        public final String oldSelector;
        public final String newSelector;
        public final boolean applied;
        public final String message;

        public FixResult(String sourceFile, int sourceLine, String oldSelector,
                         String newSelector, boolean applied, String message) {
            this.sourceFile = sourceFile;
            this.sourceLine = sourceLine;
            this.oldSelector = oldSelector;
            this.newSelector = newSelector;
            this.applied = applied;
            this.message = message;
        }
    }

    public List<FixResult> applyFixes(List<HealRecord> records) {
        List<FixResult> results = new ArrayList<>();

        for (HealRecord record : records) {
            if (record.getStrategy() != HealResult.Strategy.DOM_HEALED) continue;
            if (record.getStatus() != HealRecord.Status.SUCCESS) continue;
            if (record.getSourceFile() == null || record.getSourceLine() < 1) continue;

            FixResult result = fixRecord(record);
            results.add(result);
        }

        return results;
    }

    private FixResult fixRecord(HealRecord record) {
        Path path = Paths.get(record.getSourceFile());

        if (!Files.exists(path)) {
            return new FixResult(record.getSourceFile(), record.getSourceLine(),
                    record.getOriginalSelector(), record.getActualSelector(),
                    false, "Source file not found: " + record.getSourceFile());
        }

        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            int lineIndex = record.getSourceLine() - 1;

            if (lineIndex < 0 || lineIndex >= lines.size()) {
                return new FixResult(record.getSourceFile(), record.getSourceLine(),
                        record.getOriginalSelector(), record.getActualSelector(),
                        false, "Line number out of range: " + record.getSourceLine());
            }

            String line = lines.get(lineIndex);
            String oldSelector = extractSelectorFromOriginal(record.getOriginalSelector());
            String newSelector = record.getActualSelector();

            // Try direct replacement first (works for page.locator("...") style)
            if (oldSelector != null && line.contains(oldSelector)) {
                String newLine = line.replace(oldSelector, newSelector);
                lines.set(lineIndex, newLine);
                Files.write(path, lines, StandardCharsets.UTF_8);
                return new FixResult(record.getSourceFile(), record.getSourceLine(),
                        oldSelector, newSelector, true,
                        "Fixed at line " + record.getSourceLine());
            }

            // Handle Playwright getByTestId/byTestId pattern:
            // original: "internal:attr=[data-testid="header"]" -> testId = "header"
            // source:   byTestId("header") or getByTestId("header")
            // new:      "[data-testid="app-header"]" -> newTestId = "app-header"
            String oldTestId = extractTestId(record.getOriginalSelector());
            if (oldTestId != null && line.contains(oldTestId)) {
                String newTestId = extractTestId(newSelector);
                if (newTestId != null) {
                    // Replace just the test-id value inside the existing byTestId() call
                    String newLine = line.replace(oldTestId, newTestId);
                    lines.set(lineIndex, newLine);
                    Files.write(path, lines, StandardCharsets.UTF_8);
                    return new FixResult(record.getSourceFile(), record.getSourceLine(),
                            oldTestId, newTestId, true,
                            "Fixed test-id at line " + record.getSourceLine());
                }
                // New selector is not a test-id pattern — rewrite the whole method call
                String newLine = rewriteTestIdCall(line, oldTestId, newSelector);
                if (newLine != null) {
                    lines.set(lineIndex, newLine);
                    Files.write(path, lines, StandardCharsets.UTF_8);
                    return new FixResult(record.getSourceFile(), record.getSourceLine(),
                            oldTestId, newSelector, true,
                            "Rewrote test-id call to locator at line " + record.getSourceLine());
                }
            }

            return new FixResult(record.getSourceFile(), record.getSourceLine(),
                    record.getOriginalSelector(), record.getActualSelector(),
                    false, "Could not find original selector in source line");

        } catch (IOException e) {
            return new FixResult(record.getSourceFile(), record.getSourceLine(),
                    record.getOriginalSelector(), record.getActualSelector(),
                    false, "IO error: " + e.getMessage());
        }
    }

    /**
     * Extracts the raw selector string from locator toString representations.
     * E.g., "Locator@//div[@class='foo']" -> "//div[@class='foo']"
     */
    private String extractSelectorFromOriginal(String original) {
        if (original == null) return null;
        // Playwright Locator.toString() format: "Locator@selector"
        int atIndex = original.indexOf('@');
        if (atIndex >= 0) {
            return original.substring(atIndex + 1);
        }
        // Selenium By.toString() format: "By.xpath: //div" or "By.cssSelector: .foo"
        int colonIndex = original.indexOf(':');
        if (colonIndex >= 0) {
            return original.substring(colonIndex + 1).trim();
        }
        return original;
    }

    // Pattern for data-testid in various formats:
    //   Playwright internal: internal:attr=[data-testid="value"]
    //   CSS selector:        [data-testid="value"]
    private static final Pattern TEST_ID_PATTERN = Pattern.compile(
            "data-testid=[\"']([^\"']+)[\"']");

    /**
     * Extracts the test-id value from Playwright's internal format or CSS selector.
     * E.g., "internal:attr=[data-testid=\"header\"]" -> "header"
     *        "[data-testid=\"app-header\"]"          -> "app-header"
     */
    private String extractTestId(String selector) {
        if (selector == null) return null;
        Matcher m = TEST_ID_PATTERN.matcher(selector);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Rewrites a byTestId("old") or getByTestId("old") call to page.locator("newSelector").
     * Returns the new line, or null if the pattern wasn't found.
     */
    private String rewriteTestIdCall(String line, String oldTestId, String newSelector) {
        // Match byTestId("old") or getByTestId("old")
        Pattern p = Pattern.compile("((?:get)?[Bb]yTestId\\s*\\()\\s*[\"']" +
                Pattern.quote(oldTestId) + "[\"']\\s*\\)");
        Matcher m = p.matcher(line);
        if (m.find()) {
            return line.substring(0, m.start()) +
                    "page.locator(\"" + newSelector.replace("\"", "\\\"") + "\")" +
                    line.substring(m.end());
        }
        return null;
    }
}
