package com.autoheal;

import com.autoheal.ai.AIProvider;
import com.autoheal.ai.FailureAnalysis;
import com.autoheal.ai.FailureContext;
import com.autoheal.cache.HealCache;
import com.autoheal.config.AutoHealConfig;
import com.autoheal.finder.PlaywrightHealer;
import com.autoheal.fixer.SourceFixer;
import com.autoheal.reporter.HealRecord;
import com.autoheal.reporter.ReportGenerator;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

import com.autoheal.util.ScreenshotUtil;

import java.util.*;

/**
 * AutoHeal entry point for Playwright-only projects.
 * Does not reference any Selenium classes.
 */
public class PlaywrightAutoHeal {

    private final AutoHealConfig config;
    private final AIProvider aiProvider;
    private final Page page;
    private final PlaywrightHealer healer;
    private final List<HealRecord> records;
    private final ReportGenerator reportGenerator;
    private final SourceFixer sourceFixer;

    private PlaywrightAutoHeal(AutoHealConfig config, Page page, String reportName) {
        this.config = config;
        this.page = page;
        this.aiProvider = AutoHealFactory.createProvider(config);
        this.records = Collections.synchronizedList(new ArrayList<>());
        HealCache cache = new HealCache(config.isCacheEnabled(), config.getReportPath());
        this.healer = new PlaywrightHealer(page, aiProvider, cache, records);
        this.reportGenerator = new ReportGenerator(config.getReportPath(), reportName);
        this.sourceFixer = new SourceFixer();
    }

    public Locator find(Locator original, String description) {
        return healer.find(original, description);
    }

    public Locator find(Locator original, String description, Object pageObject) {
        return healer.find(original, description, pageObject);
    }

    public Locator find(Locator original, String description, String sourceFile, int sourceLine) {
        return healer.find(original, description, sourceFile, sourceLine);
    }

    /**
     * Capture the current page DOM now.
     * Call after navigation so the DOM is ready before heal calls.
     */
    public void captureDom() {
        healer.captureDom();
    }

    /**
     * Enable batch mode: broken locators are collected instead of healed immediately.
     * Call flushBatch() to heal all collected locators in one AI call.
     */
    public void startBatch() {
        healer.setBatchMode(true);
    }

    /**
     * Heal all collected broken locators in one AI call.
     * Returns map of original selector -> healed Locator.
     */
    public Map<String, Locator> flushBatch() {
        healer.setBatchMode(false);
        return healer.flushBatch();
    }

    public FailureAnalysis analyzeFailure(String errorLog) {
        byte[] screenshotBytes = page.screenshot();
        String base64 = ScreenshotUtil.compressToBase64(screenshotBytes);
        FailureContext context = FailureContext.builder()
                .screenshotBase64(base64)
                .errorLog(errorLog)
                .pageUrl(page.url())
                .build();
        return analyzeFailure(context);
    }

    public FailureAnalysis analyzeFailure(FailureContext context) {
        FailureAnalysis result = aiProvider.analyzeFailure(context);
        records.add(HealRecord.fromFailureAnalysis(result));
        return result;
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
        private String reportName;

        public Builder config(AutoHealConfig config) {
            this.config = config;
            return this;
        }

        public Builder page(Page page) {
            this.page = page;
            return this;
        }

        public Builder reportName(String reportName) {
            this.reportName = reportName;
            return this;
        }

        public PlaywrightAutoHeal build() {
            if (config == null) config = AutoHealConfig.fromEnv();
            if (page == null) throw new IllegalStateException("Playwright Page must be set.");
            return new PlaywrightAutoHeal(config, page, reportName);
        }
    }
}
