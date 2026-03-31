package com.autoheal;

import com.autoheal.ai.*;
import com.autoheal.cache.HealCache;
import com.autoheal.config.AutoHealConfig;
import com.autoheal.fixer.SourceFixer;
import com.autoheal.reporter.HealRecord;
import com.autoheal.reporter.ReportGenerator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Main entry point for auto-healing locators.
 * Supports Playwright and Selenium independently — consumers only need
 * the framework they actually use on the classpath.
 *
 * All Playwright/Selenium types are referenced only via fully-qualified names
 * and only in methods specific to that framework, so the JVM only loads them
 * when those methods are actually called.
 */
public class AutoHeal {

    private final AutoHealConfig config;
    private final AIProvider aiProvider;
    private final HealCache cache;
    private final List<HealRecord> records;
    private final ReportGenerator reportGenerator;
    private final SourceFixer sourceFixer;

    private Object playwrightHealer;
    private Object seleniumHealer;

    private AutoHeal(AutoHealConfig config, Object playwrightPage, Object seleniumDriver) {
        this.config = config;
        this.aiProvider = createProvider(config);
        this.cache = new HealCache(config.isCacheEnabled(), config.getReportPath());
        this.records = Collections.synchronizedList(new ArrayList<>());
        this.reportGenerator = new ReportGenerator(config.getReportPath());
        this.sourceFixer = new SourceFixer();

        if (playwrightPage != null) {
            initPlaywright(playwrightPage);
        }
        if (seleniumDriver != null) {
            initSelenium(seleniumDriver);
        }
    }

    private void initPlaywright(Object page) {
        this.playwrightHealer = new com.autoheal.finder.PlaywrightHealer(
                (com.microsoft.playwright.Page) page, aiProvider, cache, records);
    }

    private void initSelenium(Object driver) {
        this.seleniumHealer = new com.autoheal.finder.SeleniumHealer(
                (org.openqa.selenium.WebDriver) driver, aiProvider, cache, records);
    }

    // --- Playwright methods ---

    public com.microsoft.playwright.Locator find(com.microsoft.playwright.Locator original, String description) {
        requirePlaywright();
        return ((com.autoheal.finder.PlaywrightHealer) playwrightHealer).find(original, description);
    }

    public com.microsoft.playwright.Locator find(com.microsoft.playwright.Locator original, String description,
                                                  String sourceFile, int sourceLine) {
        requirePlaywright();
        return ((com.autoheal.finder.PlaywrightHealer) playwrightHealer).find(original, description, sourceFile, sourceLine);
    }

    // --- Selenium methods ---

    public org.openqa.selenium.WebElement find(org.openqa.selenium.By original, String description) {
        requireSelenium();
        return ((com.autoheal.finder.SeleniumHealer) seleniumHealer).find(original, description);
    }

    public org.openqa.selenium.WebElement find(org.openqa.selenium.By original, String description,
                                                String sourceFile, int sourceLine) {
        requireSelenium();
        return ((com.autoheal.finder.SeleniumHealer) seleniumHealer).find(original, description, sourceFile, sourceLine);
    }

    // --- Report & Fix ---

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

    // --- Internal ---

    private void requirePlaywright() {
        if (playwrightHealer == null) {
            throw new IllegalStateException("AutoHeal was not configured with a Playwright Page. Use .playwrightPage(page) in builder.");
        }
    }

    private void requireSelenium() {
        if (seleniumHealer == null) {
            throw new IllegalStateException("AutoHeal was not configured with a Selenium WebDriver. Use .seleniumDriver(driver) in builder.");
        }
    }

    private static AIProvider createProvider(AutoHealConfig config) {
        switch (config.getAiProvider()) {
            case GEMINI:
                return new GeminiProvider(config.getAiApiKey(), config.getAiModel());
            case OPENAI:
                return new OpenAIProvider(config.getAiApiKey(), config.getAiModel());
            default:
                return new ClaudeProvider(config.getAiApiKey(), config.getAiModel());
        }
    }

    // --- Builder ---

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder accepts Object types so consumers don't need both
     * Playwright and Selenium on their classpath.
     * Type checking is deferred to build() / runtime.
     */
    public static class Builder {
        private AutoHealConfig config;
        private Object playwrightPage;
        private Object seleniumDriver;

        public Builder config(AutoHealConfig config) {
            this.config = config;
            return this;
        }

        /**
         * Set the Playwright Page instance.
         * Accepts Object to avoid compile-time dependency on Playwright.
         */
        public Builder playwrightPage(Object page) {
            this.playwrightPage = page;
            return this;
        }

        /**
         * Set the Selenium WebDriver instance.
         * Accepts Object to avoid compile-time dependency on Selenium.
         */
        public Builder seleniumDriver(Object driver) {
            this.seleniumDriver = driver;
            return this;
        }

        public AutoHeal build() {
            if (config == null) {
                config = AutoHealConfig.fromEnv();
            }
            if (playwrightPage == null && seleniumDriver == null) {
                throw new IllegalStateException("Either playwrightPage or seleniumDriver must be set.");
            }
            return new AutoHeal(config, playwrightPage, seleniumDriver);
        }
    }
}
