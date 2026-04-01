package com.autoheal.ai;

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
}
