package com.autoheal;

import com.autoheal.ai.*;
import com.autoheal.cache.HealCache;
import com.autoheal.config.AutoHealConfig;
import com.autoheal.finder.PlaywrightHealer;
import com.autoheal.finder.SeleniumHealer;
import com.autoheal.fixer.SourceFixer;
import com.autoheal.reporter.HealRecord;
import com.autoheal.reporter.ReportGenerator;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AutoHeal {

    private final AutoHealConfig config;
    private final AIProvider aiProvider;
    private final HealCache cache;
    private final List<HealRecord> records;
    private final ReportGenerator reportGenerator;
    private final SourceFixer sourceFixer;

    // Playwright
    private PlaywrightHealer playwrightHealer;

    // Selenium
    private SeleniumHealer seleniumHealer;

    private AutoHeal(AutoHealConfig config, Page playwrightPage, WebDriver seleniumDriver) {
        this.config = config;
        this.aiProvider = createProvider(config);
        this.cache = new HealCache(config.isCacheEnabled());
        this.records = Collections.synchronizedList(new ArrayList<>());
        this.reportGenerator = new ReportGenerator(config.getReportPath());
        this.sourceFixer = new SourceFixer();

        if (playwrightPage != null) {
            this.playwrightHealer = new PlaywrightHealer(playwrightPage, aiProvider, cache, records);
        }
        if (seleniumDriver != null) {
            this.seleniumHealer = new SeleniumHealer(seleniumDriver, aiProvider, cache, records);
        }
    }

    // --- Playwright methods ---

    public Locator find(Locator original, String description) {
        requirePlaywright();
        return playwrightHealer.find(original, description);
    }

    public Locator find(Locator original, String description, String sourceFile, int sourceLine) {
        requirePlaywright();
        return playwrightHealer.find(original, description, sourceFile, sourceLine);
    }

    // --- Selenium methods ---

    public WebElement find(By original, String description) {
        requireSelenium();
        return seleniumHealer.find(original, description);
    }

    public WebElement find(By original, String description, String sourceFile, int sourceLine) {
        requireSelenium();
        return seleniumHealer.find(original, description, sourceFile, sourceLine);
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

    public static class Builder {
        private AutoHealConfig config;
        private Page playwrightPage;
        private WebDriver seleniumDriver;

        public Builder config(AutoHealConfig config) {
            this.config = config;
            return this;
        }

        public Builder playwrightPage(Page page) {
            this.playwrightPage = page;
            return this;
        }

        public Builder seleniumDriver(WebDriver driver) {
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
