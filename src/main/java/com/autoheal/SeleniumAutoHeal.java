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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    public WebElement find(By original, String description) {
        return healer.find(original, description);
    }

    public WebElement find(By original, String description, Object pageObject) {
        return healer.find(original, description, pageObject);
    }

    public WebElement find(By original, String description, String sourceFile, int sourceLine) {
        return healer.find(original, description, sourceFile, sourceLine);
    }

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
