package com.autoheal.finder;

import com.autoheal.ai.AIProvider;
import com.autoheal.ai.AIResponse;
import com.autoheal.cache.HealCache;
import com.autoheal.reporter.HealRecord;
import com.autoheal.util.DomExtractor;
import com.autoheal.util.LocatorSourceResolver;
import com.autoheal.util.LocatorSourceResolver.SourceInfo;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

import java.util.List;

public class PlaywrightHealer {

    private final Page page;
    private final AIProvider aiProvider;
    private final HealCache cache;
    private final List<HealRecord> records;
    private String cachedDom;
    private String cachedDomUrl;

    public PlaywrightHealer(Page page, AIProvider aiProvider, HealCache cache, List<HealRecord> records) {
        this.page = page;
        this.aiProvider = aiProvider;
        this.cache = cache;
        this.records = records;
    }

    public Locator find(Locator original, String description) {
        return find(original, description, null, -1);
    }

    public Locator find(Locator original, String description, Object pageObject) {
        SourceInfo info = LocatorSourceResolver.resolve(original, pageObject);
        String sourceFile = info != null ? info.filePath() : null;
        int sourceLine = info != null ? info.lineNumber() : -1;
        return find(original, description, sourceFile, sourceLine);
    }

    public Locator find(Locator original, String description, String sourceFile, int sourceLine) {
        long start = System.currentTimeMillis();
        String originalSelector = original.toString();

        // 1. Try original locator
        try {
            if (original.count() > 0 && original.first().isVisible()) {
                long elapsed = System.currentTimeMillis() - start;
                records.add(HealRecord.success(originalSelector, originalSelector,
                        HealResult.Strategy.ORIGINAL, elapsed, 0, "Original locator works",
                        sourceFile, sourceLine, extractElementInfo(original)));
                return original;
            }
        } catch (Exception ignored) {
        }

        // 2. Check cache
        String cachedSelector = cache.get(originalSelector);
        if (cachedSelector != null) {
            try {
                Locator cachedLocator = page.locator(cachedSelector);
                if (cachedLocator.count() > 0 && cachedLocator.first().isVisible()) {
                    long elapsed = System.currentTimeMillis() - start;
                    records.add(HealRecord.success(originalSelector, cachedSelector,
                            HealResult.Strategy.CACHED, elapsed, 0, "Found in cache",
                            sourceFile, sourceLine, extractElementInfo(cachedLocator)));
                    return cachedLocator;
                }
            } catch (Exception ignored) {
            }
        }

        // 3. DOM Heal via AI (cache DOM per URL)
        String currentUrl = page.url();
        if (cachedDom == null || !currentUrl.equals(cachedDomUrl)) {
            cachedDom = DomExtractor.fromPlaywright(page);
            cachedDomUrl = currentUrl;
        }
        String dom = cachedDom;
        AIResponse aiResponse = aiProvider.findLocator(dom, description, originalSelector);
        String newSelector = aiResponse.getSelector();

        try {
            Locator healed = page.locator(newSelector);
            if (healed.count() > 0) {
                long elapsed = System.currentTimeMillis() - start;
                cache.put(originalSelector, newSelector);
                records.add(HealRecord.success(originalSelector, newSelector,
                        HealResult.Strategy.DOM_HEALED, elapsed, aiResponse.getTokensUsed(),
                        aiResponse.getReasoning(), sourceFile, sourceLine, extractElementInfo(healed)));
                return healed;
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

    private String extractElementInfo(Locator locator) {
        try {
            return (String) locator.first().evaluate(
                    "el => { " +
                    "  const attrs = {}; " +
                    "  for (const a of el.attributes) attrs[a.name] = a.value; " +
                    "  return JSON.stringify({ tag: el.tagName, text: el.textContent.trim().substring(0, 100), attributes: attrs }); " +
                    "}"
            );
        } catch (Exception e) {
            return "{}";
        }
    }
}
