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

            if (oldSelector != null && line.contains(oldSelector)) {
                String newLine = line.replace(oldSelector, newSelector);
                lines.set(lineIndex, newLine);
                Files.write(path, lines, StandardCharsets.UTF_8);
                return new FixResult(record.getSourceFile(), record.getSourceLine(),
                        oldSelector, newSelector, true,
                        "Fixed at line " + record.getSourceLine());
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
}
