package com.autoheal;

import com.autoheal.ai.AIProvider;
import com.autoheal.cache.HealCache;
import com.autoheal.config.AutoHealConfig;
import com.autoheal.finder.PlaywrightHealer;
import com.autoheal.fixer.SourceFixer;
import com.autoheal.reporter.HealRecord;
import com.autoheal.reporter.ReportGenerator;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AutoHeal entry point for Playwright-only projects.
 * Does not reference any Selenium classes.
 */
public class PlaywrightAutoHeal {

    private final AutoHealConfig config;
    private final PlaywrightHealer healer;
    private final List<HealRecord> records;
    private final ReportGenerator reportGenerator;
    private final SourceFixer sourceFixer;

    private PlaywrightAutoHeal(AutoHealConfig config, Page page) {
        this.config = config;
        AIProvider aiProvider = AutoHealFactory.createProvider(config);
        this.records = Collections.synchronizedList(new ArrayList<>());
        HealCache cache = new HealCache(config.isCacheEnabled());
        this.healer = new PlaywrightHealer(page, aiProvider, cache, records);
        this.reportGenerator = new ReportGenerator(config.getReportPath());
        this.sourceFixer = new SourceFixer();
    }

    public Locator find(Locator original, String description) {
        return healer.find(original, description);
    }

    public Locator find(Locator original, String description, String sourceFile, int sourceLine) {
        return healer.find(original, description, sourceFile, sourceLine);
    }

    public void generateReport() {
        reportGenerator.generate(records);
    }

    public List<SourceFixer.FixResult> applyFixes() {
        return sourceFixer.applyFixes(records);
    }

    public void finish() {
        reportGenerator.generate(records);
        if (config.getAutoFix() == AutoHealConfig.AutoFixMode.AUTO) {
            List<SourceFixer.FixResult> fixes = sourceFixer.applyFixes(records);
            for (SourceFixer.FixResult fix : fixes) {
                if (fix.applied) {
                    System.out.println("[AutoHeal] Fixed: " + fix.sourceFile + ":" + fix.sourceLine +
                            " -> " + fix.newSelector);
                } else {
                    System.out.println("[AutoHeal] Could not fix: " + fix.sourceFile + ":" + fix.sourceLine +
                            " - " + fix.message);
                }
            }
        }
    }

    public List<HealRecord> getRecords() {
        return Collections.unmodifiableList(records);
    }

    // --- Builder ---

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private AutoHealConfig config;
        private Page page;

        public Builder config(AutoHealConfig config) {
            this.config = config;
            return this;
        }

        public Builder page(Page page) {
            this.page = page;
            return this;
        }

        public PlaywrightAutoHeal build() {
            if (config == null) config = AutoHealConfig.fromEnv();
            if (page == null) throw new IllegalStateException("Playwright Page must be set.");
            return new PlaywrightAutoHeal(config, page);
        }
    }
}
