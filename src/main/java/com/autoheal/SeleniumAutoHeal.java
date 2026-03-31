package com.autoheal;

import com.autoheal.ai.AIProvider;
import com.autoheal.cache.HealCache;
import com.autoheal.config.AutoHealConfig;
import com.autoheal.finder.SeleniumHealer;
import com.autoheal.fixer.SourceFixer;
import com.autoheal.reporter.HealRecord;
import com.autoheal.reporter.ReportGenerator;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AutoHeal entry point for Selenium-only projects.
 * Does not reference any Playwright classes.
 */
public class SeleniumAutoHeal {

    private final AutoHealConfig config;
    private final SeleniumHealer healer;
    private final List<HealRecord> records;
    private final ReportGenerator reportGenerator;
    private final SourceFixer sourceFixer;

    private SeleniumAutoHeal(AutoHealConfig config, WebDriver driver) {
        this.config = config;
        AIProvider aiProvider = AutoHealFactory.createProvider(config);
        this.records = Collections.synchronizedList(new ArrayList<>());
        HealCache cache = new HealCache(config.isCacheEnabled(), config.getReportPath());
        this.healer = new SeleniumHealer(driver, aiProvider, cache, records);
        this.reportGenerator = new ReportGenerator(config.getReportPath());
        this.sourceFixer = new SourceFixer();
    }

    public WebElement find(By original, String description) {
        return healer.find(original, description);
    }

    public WebElement find(By original, String description, String sourceFile, int sourceLine) {
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
        private WebDriver driver;

        public Builder config(AutoHealConfig config) {
            this.config = config;
            return this;
        }

        public Builder driver(WebDriver driver) {
            this.driver = driver;
            return this;
        }

        public SeleniumAutoHeal build() {
            if (config == null) config = AutoHealConfig.fromEnv();
            if (driver == null) throw new IllegalStateException("Selenium WebDriver must be set.");
            return new SeleniumAutoHeal(config, driver);
        }
    }
}
