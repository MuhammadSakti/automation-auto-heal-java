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

import java.util.List;

public class SeleniumHealer {

    private final WebDriver driver;
    private final AIProvider aiProvider;
    private final HealCache cache;
    private final List<HealRecord> records;
    private String cachedDom;
    private String cachedDomUrl;

    public SeleniumHealer(WebDriver driver, AIProvider aiProvider, HealCache cache, List<HealRecord> records) {
        this.driver = driver;
        this.aiProvider = aiProvider;
        this.cache = cache;
        this.records = records;
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

        // 3. DOM Heal via AI (cache DOM per URL)
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
