package com.autoheal.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public interface AIProvider {
    AIResponse findLocator(String domSnapshot, String description, String originalSelector);

    /**
     * Batch heal: send DOM once, heal multiple locators in one AI call.
     * Each entry in locators is: key = originalSelector, value = description.
     * Returns a map of originalSelector -> AIResponse.
     */
    Map<String, AIResponse> findLocatorsBatch(String domSnapshot, Map<String, String> locators);

    default FailureAnalysis analyzeFailure(FailureContext context) {
        throw new UnsupportedOperationException("This AI provider does not support failure analysis");
    }

    static String buildFailureAnalysisPrompt(FailureContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a test automation failure analyst. A UI test has failed. ");
        sb.append("Analyze the screenshot and error log to determine the root cause.\n\n");
        sb.append("Error log:\n").append(context.getErrorLog()).append("\n\n");
        sb.append("Page URL: ").append(context.getPageUrl() != null ? context.getPageUrl() : "not provided").append("\n\n");
        sb.append("Respond in this exact format (no markdown, no extra text):\n");
        sb.append("SUMMARY: <one-line description of what went wrong>\n");
        sb.append("POSSIBLE_CAUSES:\n");
        sb.append("- <cause 1>\n");
        sb.append("- <cause 2>\n");
        sb.append("- <cause 3>\n");
        sb.append("SUGGESTIONS:\n");
        sb.append("- <actionable suggestion 1>\n");
        sb.append("- <actionable suggestion 2>\n");
        sb.append("- <actionable suggestion 3>");
        if (context.getDomSnapshot() != null) {
            sb.append("\n\nDOM snapshot (for additional context):\n```html\n");
            sb.append(context.getDomSnapshot()).append("\n```");
        }
        return sb.toString();
    }

    static FailureAnalysis parseFailureResponse(String text, int tokensUsed) {
        String summary = "";
        List<String> causes = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();
        String section = null;

        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.startsWith("SUMMARY:")) {
                summary = line.substring("SUMMARY:".length()).trim();
                section = null;
            } else if (line.equals("POSSIBLE_CAUSES:")) {
                section = "causes";
            } else if (line.equals("SUGGESTIONS:")) {
                section = "suggestions";
            } else if (line.startsWith("- ") && section != null) {
                String item = line.substring(2).trim();
                if ("causes".equals(section)) {
                    causes.add(item);
                } else {
                    suggestions.add(item);
                }
            }
        }

        if (summary.isEmpty()) {
            summary = text.trim().split("\n")[0];
        }

        return new FailureAnalysis(summary, causes, suggestions, tokensUsed);
    }

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
