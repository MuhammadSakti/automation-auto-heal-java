package com.autoheal;

import com.autoheal.ai.*;
import com.autoheal.cache.HealCache;
import com.autoheal.config.AutoHealConfig;
import com.autoheal.fixer.SourceFixer;
import com.autoheal.reporter.HealRecord;
import com.autoheal.reporter.ReportGenerator;

import com.autoheal.util.ScreenshotUtil;

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

    private Object playwrightPage;
    private Object seleniumDriver;
    private Object playwrightHealer;
    private Object seleniumHealer;

    private AutoHeal(AutoHealConfig config, Object playwrightPage, Object seleniumDriver, String reportName) {
        this.config = config;
        this.aiProvider = createProvider(config);
        this.cache = new HealCache(config.isCacheEnabled(), config.getReportPath());
        this.records = Collections.synchronizedList(new ArrayList<>());
        this.reportGenerator = new ReportGenerator(config.getReportPath(), reportName);
        this.sourceFixer = new SourceFixer();

        this.playwrightPage = playwrightPage;
        this.seleniumDriver = seleniumDriver;

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
                                                  Object pageObject) {
        requirePlaywright();
        return ((com.autoheal.finder.PlaywrightHealer) playwrightHealer).find(original, description, pageObject);
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
                                                Object pageObject) {
        requireSelenium();
        return ((com.autoheal.finder.SeleniumHealer) seleniumHealer).find(original, description, pageObject);
    }

    public org.openqa.selenium.WebElement find(org.openqa.selenium.By original, String description,
                                                String sourceFile, int sourceLine) {
        requireSelenium();
        return ((com.autoheal.finder.SeleniumHealer) seleniumHealer).find(original, description, sourceFile, sourceLine);
    }

    // --- Failure Analysis ---

    public FailureAnalysis analyzeFailure(FailureContext context) {
        FailureAnalysis result = aiProvider.analyzeFailure(context);
        HealRecord record = HealRecord.fromFailureAnalysis(result);
        record.setScreenshotBase64(context.getScreenshotBase64());
        records.add(record);
        return result;
    }

    public FailureAnalysis analyzeFailure(String errorLog) {
        if (playwrightPage != null) {
            return analyzeFailurePlaywright(errorLog);
        }
        if (seleniumDriver != null) {
            return analyzeFailureSelenium(errorLog);
        }
        throw new IllegalStateException("No framework configured for auto-screenshot. Use analyzeFailure(FailureContext) instead.");
    }

    private FailureAnalysis analyzeFailurePlaywright(String errorLog) {
        com.microsoft.playwright.Page page = (com.microsoft.playwright.Page) playwrightPage;
        byte[] screenshotBytes = page.screenshot();
        String base64 = ScreenshotUtil.compressToBase64(screenshotBytes);
        FailureContext context = FailureContext.builder()
                .screenshotBase64(base64)
                .errorLog(errorLog)
                .pageUrl(page.url())
                .build();
        return analyzeFailure(context);
    }

    private FailureAnalysis analyzeFailureSelenium(String errorLog) {
        org.openqa.selenium.WebDriver driver = (org.openqa.selenium.WebDriver) seleniumDriver;
        String base64 = ((org.openqa.selenium.TakesScreenshot) driver)
                .getScreenshotAs(org.openqa.selenium.OutputType.BASE64);
        String compressed = ScreenshotUtil.compressBase64(base64);
        FailureContext context = FailureContext.builder()
                .screenshotBase64(compressed)
                .errorLog(errorLog)
                .pageUrl(driver.getCurrentUrl())
                .build();
        return analyzeFailure(context);
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
        private String reportName;

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

        public Builder reportName(String reportName) {
            this.reportName = reportName;
            return this;
        }

        public AutoHeal build() {
            if (config == null) {
                config = AutoHealConfig.fromEnv();
            }
            if (playwrightPage == null && seleniumDriver == null) {
                throw new IllegalStateException("Either playwrightPage or seleniumDriver must be set.");
            }
            return new AutoHeal(config, playwrightPage, seleniumDriver, reportName);
        }
    }
}
