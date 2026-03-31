package com.autoheal.ai;

public interface AIProvider {
    AIResponse findLocator(String domSnapshot, String description, String originalSelector);
}
