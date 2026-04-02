package com.autoheal;

import com.autoheal.ai.AIProvider;
import com.autoheal.ai.FailureAnalysis;
import com.autoheal.ai.FailureContext;
import com.autoheal.cache.HealCache;
import com.autoheal.config.AutoHealConfig;
import com.autoheal.finder.SeleniumHealer;
import com.autoheal.fixer.SourceFixer;
import com.autoheal.reporter.HealRecord;
import com.autoheal.reporter.ReportGenerator;
import org.openqa.selenium.By;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import com.autoheal.util.ScreenshotUtil;

import java.util.*;

/**
 * AutoHeal entry point for Selenium-only projects.
 * Does not reference any Playwright classes.
 */
public class SeleniumAutoHeal {

    private final AutoHealConfig config;
    private final AIProvider aiProvider;
    private final WebDriver driver;
    private final SeleniumHealer healer;
    private final List<HealRecord> records;
    private final ReportGenerator reportGenerator;
    private final SourceFixer sourceFixer;

    private SeleniumAutoHeal(AutoHealConfig config, WebDriver driver, String reportName) {
        this.config = config;
        this.driver = driver;
        this.aiProvider = AutoHealFactory.createProvider(config);
        this.records = Collections.synchronizedList(new ArrayList<>());
        HealCache cache = new HealCache(config.isCacheEnabled(), config.getReportPath());
        this.healer = new SeleniumHealer(driver, aiProvider, cache, records);
        this.reportGenerator = new ReportGenerator(config.getReportPath(), reportName);
        this.sourceFixer = new SourceFixer();
    }

    /**
     * Find or heal a broken locator using AI.
     *
     * @param original    the original locator that may be broken
     * @param description human-readable description of the element
     * @return a working element, either from the original or a healed locator
     */
    public WebElement find(By original, String description) {
        return healer.find(original, description);
    }

    /**
     * Find or heal a broken locator using AI, with source fix support via page object reflection.
     *
     * @param original    the original locator that may be broken
     * @param description human-readable description of the element
     * @param pageObject  the page object instance containing the locator field
     * @return a working element, either from the original or a healed locator
     */
    public WebElement find(By original, String description, Object pageObject) {
        return healer.find(original, description, pageObject);
    }

    /**
     * Find or heal a broken locator using AI, with explicit source location for auto-fix.
     *
     * @param original    the original locator that may be broken
     * @param description human-readable description of the element
     * @param sourceFile  path to the source file containing the locator
     * @param sourceLine  line number in the source file
     * @return a working element, either from the original or a healed locator
     */
    public WebElement find(By original, String description, String sourceFile, int sourceLine) {
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
     * During batch mode, find() returns null for broken locators.
     * Call flushBatch() to heal all collected locators in one AI call.
     */
    public void startBatch() {
        healer.setBatchMode(true);
    }

    /**
     * Heal all collected broken locators in one AI call.
     * Returns map of original selector -> healed WebElement.
     */
    public Map<String, WebElement> flushBatch() {
        healer.setBatchMode(false);
        return healer.flushBatch();
    }

    /**
     * Analyze a test failure using AI with an auto-captured screenshot at default quality (70%).
     *
     * @param errorLog the error log or stack trace from the failure
     * @return AI-generated failure analysis
     */
    public FailureAnalysis analyzeFailure(String errorLog) {
        String base64 = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BASE64);
        String compressed = ScreenshotUtil.compressBase64(base64);
        FailureContext context = FailureContext.builder()
                .screenshotBase64(compressed)
                .errorLog(errorLog)
                .pageUrl(driver.getCurrentUrl())
                .build();
        return analyzeFailure(context);
    }

    /**
     * Analyze a test failure using AI with an auto-captured screenshot at custom quality.
     *
     * @param errorLog     the error log or stack trace from the failure
     * @param imageQuality JPEG quality percentage (1-100), higher values produce clearer screenshots
     * @return AI-generated failure analysis
     */
    public FailureAnalysis analyzeFailure(String errorLog, int imageQuality) {
        String base64 = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BASE64);
        String compressed = ScreenshotUtil.compressBase64(base64, imageQuality);
        FailureContext context = FailureContext.builder()
                .screenshotBase64(compressed)
                .errorLog(errorLog)
                .pageUrl(driver.getCurrentUrl())
                .build();
        return analyzeFailure(context);
    }

    /**
     * Analyze a test failure using AI with a pre-built failure context.
     *
     * @param context the failure context containing screenshot, error log, and page URL
     * @return AI-generated failure analysis
     */
    public FailureAnalysis analyzeFailure(FailureContext context) {
        FailureAnalysis result = aiProvider.analyzeFailure(context);
        HealRecord record = HealRecord.fromFailureAnalysis(result);
        record.setScreenshotBase64(context.getScreenshotBase64());
        records.add(record);
        return result;
    }

    /** Generate the HTML heal report from collected records. */
    public void generateReport() {
        reportGenerator.generate(records);
    }

    /**
     * Apply source code fixes for all healed locators.
     *
     * @return list of fix results indicating which fixes were applied
     */
    public List<SourceFixer.FixResult> applyFixes() {
        return sourceFixer.applyFixes(records);
    }

    /** Generate the report and apply auto-fixes if configured. */
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

    /** Return an unmodifiable view of all heal and failure analysis records. */
    public List<HealRecord> getRecords() {
        return Collections.unmodifiableList(records);
    }

    // --- Builder ---

    /** Create a new builder for {@link SeleniumAutoHeal}. */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private AutoHealConfig config;
        private WebDriver driver;
        private String reportName;

        public Builder config(AutoHealConfig config) {
            this.config = config;
            return this;
        }

        public Builder driver(WebDriver driver) {
            this.driver = driver;
            return this;
        }

        public Builder reportName(String reportName) {
            this.reportName = reportName;
            return this;
        }

        public SeleniumAutoHeal build() {
            if (config == null) config = AutoHealConfig.fromEnv();
            if (driver == null) throw new IllegalStateException("Selenium WebDriver must be set.");
            return new SeleniumAutoHeal(config, driver, reportName);
        }
    }
}
