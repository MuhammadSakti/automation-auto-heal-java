package com.autoheal.finder;

import com.autoheal.ai.AIProvider;
import com.autoheal.ai.AIResponse;
import com.autoheal.cache.HealCache;
import com.autoheal.reporter.HealRecord;
import com.autoheal.util.DomExtractor;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import java.util.List;

public class SeleniumHealer {

    private final WebDriver driver;
    private final AIProvider aiProvider;
    private final HealCache cache;
    private final List<HealRecord> records;

    public SeleniumHealer(WebDriver driver, AIProvider aiProvider, HealCache cache, List<HealRecord> records) {
        this.driver = driver;
        this.aiProvider = aiProvider;
        this.cache = cache;
        this.records = records;
    }

    public WebElement find(By original, String description) {
        return find(original, description, null, -1);
    }

    public WebElement find(By original, String description, String sourceFile, int sourceLine) {
        long start = System.currentTimeMillis();
        String originalSelector = original.toString();

        // 1. Try original locator
        try {
            List<WebElement> elements = driver.findElements(original);
            if (!elements.isEmpty() && elements.get(0).isDisplayed()) {
                long elapsed = System.currentTimeMillis() - start;
                records.add(HealRecord.success(originalSelector, originalSelector,
                        HealResult.Strategy.ORIGINAL, elapsed, 0, "Original locator works",
                        sourceFile, sourceLine, extractElementInfo(elements.get(0))));
                return elements.get(0);
            }
        } catch (Exception ignored) {
        }

        // 2. Check cache
        String cachedSelector = cache.get(originalSelector);
        if (cachedSelector != null) {
            try {
                By cachedBy = toBy(cachedSelector);
                List<WebElement> elements = driver.findElements(cachedBy);
                if (!elements.isEmpty() && elements.get(0).isDisplayed()) {
                    long elapsed = System.currentTimeMillis() - start;
                    records.add(HealRecord.success(originalSelector, cachedSelector,
                            HealResult.Strategy.CACHED, elapsed, 0, "Found in cache",
                            sourceFile, sourceLine, extractElementInfo(elements.get(0))));
                    return elements.get(0);
                }
            } catch (Exception ignored) {
            }
        }

        // 3. DOM Heal via AI
        String dom = DomExtractor.fromSelenium(driver);
        AIResponse aiResponse = aiProvider.findLocator(dom, description, originalSelector);
        String newSelector = aiResponse.getSelector();

        try {
            By newBy = toBy(newSelector);
            List<WebElement> elements = driver.findElements(newBy);
            if (!elements.isEmpty()) {
                long elapsed = System.currentTimeMillis() - start;
                cache.put(originalSelector, newSelector);
                records.add(HealRecord.success(originalSelector, newSelector,
                        HealResult.Strategy.DOM_HEALED, elapsed, aiResponse.getTokensUsed(),
                        aiResponse.getReasoning(), sourceFile, sourceLine,
                        extractElementInfo(elements.get(0))));
                return elements.get(0);
            }
        } catch (Exception ignored) {
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

    private By toBy(String selector) {
        if (selector.startsWith("//") || selector.startsWith("(//")) {
            return By.xpath(selector);
        }
        return By.cssSelector(selector);
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
