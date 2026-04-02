package com.autoheal.finder;

import com.autoheal.ai.AIProvider;
import com.autoheal.ai.AIResponse;
import com.autoheal.cache.HealCache;
import com.autoheal.reporter.HealRecord;
import com.autoheal.util.DomExtractor;
import com.autoheal.util.LocatorSourceResolver;
import com.autoheal.util.LocatorSourceResolver.SourceInfo;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import java.util.*;

public class SeleniumHealer {

    private final WebDriver driver;
    private final AIProvider aiProvider;
    private final HealCache cache;
    private final List<HealRecord> records;
    private String cachedDom;
    private String cachedDomUrl;

    // Pending broken locators for batch healing
    private final List<PendingHeal> pendingHeals = new ArrayList<>();
    private boolean batchMode = false;

    public SeleniumHealer(WebDriver driver, AIProvider aiProvider, HealCache cache, List<HealRecord> records) {
        this.driver = driver;
        this.aiProvider = aiProvider;
        this.cache = cache;
        this.records = records;
    }

    public void captureDom() {
        cachedDom = DomExtractor.fromSelenium(driver);
        cachedDomUrl = driver.getCurrentUrl();
    }

    public void setBatchMode(boolean enabled) {
        this.batchMode = enabled;
    }

    public WebElement find(By original, String description) {
        return find(original, description, null, -1);
    }

    public WebElement find(By original, String description, Object pageObject) {
        SourceInfo info = LocatorSourceResolver.resolve(original, pageObject);
        String sourceFile = info != null ? info.filePath() : null;
        int sourceLine = info != null ? info.lineNumber() : -1;
        return find(original, description, sourceFile, sourceLine);
    }

    public WebElement find(By original, String description, String sourceFile, int sourceLine) {
        long start = System.currentTimeMillis();
        String originalSelector = original.toString();

        // 1. Try original locator
        WebElement found = tryFind(original);
        if (found != null) {
            long elapsed = System.currentTimeMillis() - start;
            records.add(HealRecord.success(originalSelector, originalSelector,
                    HealResult.Strategy.ORIGINAL, elapsed, 0, "Original locator works",
                    sourceFile, sourceLine, extractElementInfo(found)));
            return found;
        }

        // 2. Check cache
        String cachedSelector = cache.get(originalSelector);
        if (cachedSelector != null) {
            WebElement cached = tryFind(toBy(cachedSelector));
            if (cached != null) {
                long elapsed = System.currentTimeMillis() - start;
                records.add(HealRecord.success(originalSelector, cachedSelector,
                        HealResult.Strategy.CACHED, elapsed, 0, "Found in cache",
                        sourceFile, sourceLine, extractElementInfo(cached)));
                return cached;
            }
        }

        // 3. If batch mode, collect for later and return null
        if (batchMode) {
            pendingHeals.add(new PendingHeal(original, originalSelector, description, sourceFile, sourceLine, start));
            return null;
        }

        // 4. Single DOM Heal via AI
        return healSingle(originalSelector, description, sourceFile, sourceLine, start);
    }

    /**
     * Flush all pending broken locators in one batch AI call.
     * Returns map of original selector -> healed WebElement.
     */
    public Map<String, WebElement> flushBatch() {
        Map<String, WebElement> results = new LinkedHashMap<>();
        if (pendingHeals.isEmpty()) return results;

        // Extract DOM once
        String currentUrl = driver.getCurrentUrl();
        if (cachedDom == null || !currentUrl.equals(cachedDomUrl)) {
            cachedDom = DomExtractor.fromSelenium(driver);
            cachedDomUrl = currentUrl;
        }

        // Build batch request: originalSelector -> description
        Map<String, String> locatorMap = new LinkedHashMap<>();
        for (PendingHeal p : pendingHeals) {
            locatorMap.put(p.originalSelector, p.description);
        }

        // One AI call for all broken locators
        Map<String, AIResponse> batchResults = aiProvider.findLocatorsBatch(cachedDom, locatorMap);

        // Process results
        for (PendingHeal p : pendingHeals) {
            AIResponse aiResponse = batchResults.get(p.originalSelector);
            if (aiResponse == null) {
                long elapsed = System.currentTimeMillis() - p.startTime;
                records.add(HealRecord.failed(p.originalSelector, "",
                        elapsed, 0, "Not found in batch response",
                        p.sourceFile, p.sourceLine));
                continue;
            }

            String newSelector = aiResponse.getSelector();
            WebElement healed = tryFind(toBy(newSelector));
            if (healed != null) {
                long elapsed = System.currentTimeMillis() - p.startTime;
                cache.put(p.originalSelector, newSelector);
                records.add(HealRecord.success(p.originalSelector, newSelector,
                        HealResult.Strategy.DOM_HEALED, elapsed, aiResponse.getTokensUsed(),
                        aiResponse.getReasoning(), p.sourceFile, p.sourceLine, extractElementInfo(healed)));
                results.put(p.originalSelector, healed);
                continue;
            }

            long elapsed = System.currentTimeMillis() - p.startTime;
            records.add(HealRecord.failed(p.originalSelector, newSelector,
                    elapsed, aiResponse.getTokensUsed(), aiResponse.getReasoning(),
                    p.sourceFile, p.sourceLine));
        }

        pendingHeals.clear();
        return results;
    }

    private WebElement healSingle(String originalSelector, String description,
                                   String sourceFile, int sourceLine, long start) {
        String currentUrl = driver.getCurrentUrl();
        if (cachedDom == null || !currentUrl.equals(cachedDomUrl)) {
            cachedDom = DomExtractor.fromSelenium(driver);
            cachedDomUrl = currentUrl;
        }
        String dom = cachedDom;
        AIResponse aiResponse = aiProvider.findLocator(dom, description, originalSelector);
        String newSelector = aiResponse.getSelector();

        WebElement healed = tryFind(toBy(newSelector));
        if (healed != null) {
            long elapsed = System.currentTimeMillis() - start;
            cache.put(originalSelector, newSelector);
            records.add(HealRecord.success(originalSelector, newSelector,
                    HealResult.Strategy.DOM_HEALED, elapsed, aiResponse.getTokensUsed(),
                    aiResponse.getReasoning(), sourceFile, sourceLine,
                    extractElementInfo(healed)));
            return healed;
        }

        // All strategies failed
        long elapsed = System.currentTimeMillis() - start;
        records.add(HealRecord.failed(originalSelector, newSelector,
                elapsed, aiResponse.getTokensUsed(), aiResponse.getReasoning(),
                sourceFile, sourceLine));

        throw new RuntimeException("AutoHeal: Could not find element. Description: " + description +
                "\nOriginal: " + originalSelector +
                "\nAI suggested: " + newSelector +
                "\nReasoning: " + aiResponse.getReasoning());
    }

    private WebElement tryFind(By by) {
        try {
            List<WebElement> elements = driver.findElements(by);
            if (!elements.isEmpty() && elements.get(0).isDisplayed()) return elements.get(0);
        } catch (Exception ignored) {}
        return null;
    }

    private By toBy(String selector) {
        if (selector.startsWith("//") || selector.startsWith("(//")) {
            return By.xpath(selector);
        }
        return By.cssSelector(selector);
    }

    private static class PendingHeal {
        final By original;
        final String originalSelector;
        final String description;
        final String sourceFile;
        final int sourceLine;
        final long startTime;

        PendingHeal(By original, String originalSelector, String description,
                    String sourceFile, int sourceLine, long startTime) {
            this.original = original;
            this.originalSelector = originalSelector;
            this.description = description;
            this.sourceFile = sourceFile;
            this.sourceLine = sourceLine;
            this.startTime = startTime;
        }
    }

    private String extractElementInfo(WebElement element) {
        try {
            JavascriptExecutor js = (JavascriptExecutor) driver;
            return (String) js.executeScript(
                    "var el = arguments[0]; " +
                    "var attrs = {}; " +
                    "for (var i = 0; i < el.attributes.length; i++) " +
                    "  attrs[el.attributes[i].name] = el.attributes[i].value; " +
                    "return JSON.stringify({ tag: el.tagName, text: el.textContent.trim().substring(0, 100), attributes: attrs });",
                    element
            );
        } catch (Exception e) {
            return "{}";
        }
    }
}
