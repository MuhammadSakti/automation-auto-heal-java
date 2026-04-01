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

import java.util.*;

public class PlaywrightHealer {

    private final Page page;
    private final AIProvider aiProvider;
    private final HealCache cache;
    private final List<HealRecord> records;
    private String cachedDom;
    private String cachedDomUrl;

    // Pending broken locators for batch healing
    private final List<PendingHeal> pendingHeals = new ArrayList<>();
    private boolean batchMode = false;

    public PlaywrightHealer(Page page, AIProvider aiProvider, HealCache cache, List<HealRecord> records) {
        this.page = page;
        this.aiProvider = aiProvider;
        this.cache = cache;
        this.records = records;
    }

    public void setBatchMode(boolean enabled) {
        this.batchMode = enabled;
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

        // 3. If batch mode, collect for later
        if (batchMode) {
            pendingHeals.add(new PendingHeal(original, originalSelector, description, sourceFile, sourceLine, start));
            return original; // return original as placeholder
        }

        // 4. Single DOM Heal via AI
        return healSingle(original, originalSelector, description, sourceFile, sourceLine, start);
    }

    /**
     * Flush all pending broken locators in one batch AI call.
     * Returns map of original selector -> healed Locator.
     */
    public Map<String, Locator> flushBatch() {
        Map<String, Locator> results = new LinkedHashMap<>();
        if (pendingHeals.isEmpty()) return results;

        // Extract DOM once
        String currentUrl = page.url();
        if (cachedDom == null || !currentUrl.equals(cachedDomUrl)) {
            cachedDom = DomExtractor.fromPlaywright(page);
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
            try {
                Locator healed = page.locator(newSelector);
                if (healed.count() > 0) {
                    long elapsed = System.currentTimeMillis() - p.startTime;
                    cache.put(p.originalSelector, newSelector);
                    records.add(HealRecord.success(p.originalSelector, newSelector,
                            HealResult.Strategy.DOM_HEALED, elapsed, aiResponse.getTokensUsed(),
                            aiResponse.getReasoning(), p.sourceFile, p.sourceLine, extractElementInfo(healed)));
                    results.put(p.originalSelector, healed);
                    continue;
                }
            } catch (Exception ignored) {
            }

            long elapsed = System.currentTimeMillis() - p.startTime;
            records.add(HealRecord.failed(p.originalSelector, newSelector,
                    elapsed, aiResponse.getTokensUsed(), aiResponse.getReasoning(),
                    p.sourceFile, p.sourceLine));
        }

        pendingHeals.clear();
        return results;
    }

    private Locator healSingle(Locator original, String originalSelector, String description,
                                String sourceFile, int sourceLine, long start) {
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

    private static class PendingHeal {
        final Locator original;
        final String originalSelector;
        final String description;
        final String sourceFile;
        final int sourceLine;
        final long startTime;

        PendingHeal(Locator original, String originalSelector, String description,
                    String sourceFile, int sourceLine, long startTime) {
            this.original = original;
            this.originalSelector = originalSelector;
            this.description = description;
            this.sourceFile = sourceFile;
            this.sourceLine = sourceLine;
            this.startTime = startTime;
        }
    }
}
