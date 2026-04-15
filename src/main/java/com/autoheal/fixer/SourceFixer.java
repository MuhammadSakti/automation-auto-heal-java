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
        String sourceFile = record.getSourceFile();
        int sourceLine = record.getSourceLine();
        String originalSelector = record.getOriginalSelector();
        String newSelector = record.getActualSelector();
        Path path = Paths.get(sourceFile);

        if (!Files.exists(path)) {
            return skip(sourceFile, sourceLine, originalSelector, newSelector,
                    "Source file not found: " + sourceFile);
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return skip(sourceFile, sourceLine, originalSelector, newSelector,
                    "IO error: " + e.getMessage());
        }

        int lineIndex = sourceLine - 1;
        if (lineIndex < 0 || lineIndex >= lines.size()) {
            return skip(sourceFile, sourceLine, originalSelector, newSelector,
                    "Line number out of range: " + sourceLine);
        }

        String line = lines.get(lineIndex);
        String newLine = tryReplaceLine(line, originalSelector, newSelector);

        if (newLine == null) {
            return skip(sourceFile, sourceLine, originalSelector, newSelector,
                    "Could not find original selector in source line");
        }

        try {
            lines.set(lineIndex, newLine);
            Files.write(path, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return skip(sourceFile, sourceLine, originalSelector, newSelector,
                    "IO error writing file: " + e.getMessage());
        }

        return new FixResult(sourceFile, sourceLine, originalSelector, newSelector, true,
                "Fixed at line " + sourceLine);
    }

    private String tryReplaceLine(String line, String originalSelector, String newSelector) {
        // 1. Direct replacement (works for page.locator("...") style)
        String oldSelector = extractSelectorFromOriginal(originalSelector);
        if (oldSelector != null && line.contains(oldSelector)) {
            return line.replace(oldSelector, newSelector);
        }

        // 2. Playwright getByTestId/byTestId pattern
        String oldTestId = extractTestId(originalSelector);
        if (oldTestId == null) return null;

        // 2a. Both old and new are test-ids — swap just the value
        String newTestId = extractTestId(newSelector);
        if (newTestId != null) {
            String result = replaceTestIdInCall(line, oldTestId, newTestId);
            if (result != null) return result;
        }

        // 2b. New selector is not a test-id — rewrite the whole method call
        return rewriteTestIdCall(line, oldTestId, newSelector);
    }

    private FixResult skip(String sourceFile, int sourceLine,
                           String oldSelector, String newSelector, String message) {
        return new FixResult(sourceFile, sourceLine, oldSelector, newSelector, false, message);
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
     * Replaces the test-id value inside a byTestId("old")/getByTestId("old") call,
     * only touching the string literal — not variable names or other text on the line.
     * Returns the new line, or null if the pattern wasn't found.
     */
    private String replaceTestIdInCall(String line, String oldTestId, String newTestId) {
        Pattern p = Pattern.compile("((?:get)?[Bb]yTestId\\s*\\(\\s*[\"'])" +
                Pattern.quote(oldTestId) + "([\"']\\s*\\))");
        Matcher m = p.matcher(line);
        if (m.find()) {
            return line.substring(0, m.start()) +
                    m.group(1) + newTestId + m.group(2) +
                    line.substring(m.end());
        }
        return null;
    }

    /**
     * Rewrites a byTestId("old") or getByTestId("old") call to obj.locator("newSelector"),
     * preserving the original object reference (e.g. page, page1, pageInventory).
     * Returns the new line, or null if the pattern wasn't found.
     */
    private String rewriteTestIdCall(String line, String oldTestId, String newSelector) {
        // Match obj.getByTestId("old") or obj.byTestId("old") — capture the object name
        Pattern p = Pattern.compile("(\\w+)\\s*\\.\\s*(?:get)?[Bb]yTestId\\s*\\(\\s*[\"']" +
                Pattern.quote(oldTestId) + "[\"']\\s*\\)");
        Matcher m = p.matcher(line);
        if (m.find()) {
            String objName = m.group(1);
            return line.substring(0, m.start()) +
                    objName + ".locator(\"" + newSelector.replace("\"", "\\\"") + "\")" +
                    line.substring(m.end());
        }
        // Fallback: standalone byTestId("old") call (no object prefix, e.g. helper method)
        Pattern p2 = Pattern.compile("(?:get)?[Bb]yTestId\\s*\\(\\s*[\"']" +
                Pattern.quote(oldTestId) + "[\"']\\s*\\)");
        Matcher m2 = p2.matcher(line);
        if (m2.find()) {
            return line.substring(0, m2.start()) +
                    "locator(\"" + newSelector.replace("\"", "\\\"") + "\")" +
                    line.substring(m2.end());
        }
        return null;
    }
}
