package com.autoheal.ai;

import java.util.LinkedHashMap;
import java.util.Map;

public interface AIProvider {
    AIResponse findLocator(String domSnapshot, String description, String originalSelector);

    /**
     * Batch heal: send DOM once, heal multiple locators in one AI call.
     * Each entry in locators is: key = originalSelector, value = description.
     * Returns a map of originalSelector -> AIResponse.
     */
    Map<String, AIResponse> findLocatorsBatch(String domSnapshot, Map<String, String> locators);

    static Map<String, AIResponse> parseBatchResponse(String text, int totalTokens, int count) {
        Map<String, AIResponse> results = new LinkedHashMap<>();
        int tokensPerLocator = totalTokens / Math.max(count, 1);

        String currentOriginal = null;
        String currentSelector = null;
        String currentReasoning = null;

        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.startsWith("SELECTOR:")) {
                currentSelector = line.substring("SELECTOR:".length()).trim();
                continue;
            }
            if (line.startsWith("REASONING:")) {
                currentReasoning = line.substring("REASONING:".length()).trim();
                continue;
            }
            if (!line.startsWith("ORIGINAL:")) continue;

            flushEntry(results, currentOriginal, currentSelector, currentReasoning, tokensPerLocator);
            currentOriginal = line.substring("ORIGINAL:".length()).trim();
            currentSelector = null;
            currentReasoning = null;
        }
        flushEntry(results, currentOriginal, currentSelector, currentReasoning, tokensPerLocator);

        return results;
    }

    private static void flushEntry(Map<String, AIResponse> results, String original,
                                   String selector, String reasoning, int tokens) {
        if (original == null || selector == null) return;
        results.put(original, new AIResponse(selector, reasoning != null ? reasoning : "", tokens));
    }
}
