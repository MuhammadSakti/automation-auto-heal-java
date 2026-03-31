package com.autoheal.finder;

public class HealResult {

    public enum Strategy { ORIGINAL, CACHED, DOM_HEALED }

    private final String selector;
    private final Strategy strategy;
    private final long timeMs;
    private final int tokensUsed;
    private final String reasoning;

    public HealResult(String selector, Strategy strategy, long timeMs, int tokensUsed, String reasoning) {
        this.selector = selector;
        this.strategy = strategy;
        this.timeMs = timeMs;
        this.tokensUsed = tokensUsed;
        this.reasoning = reasoning;
    }

    public String getSelector() { return selector; }
    public Strategy getStrategy() { return strategy; }
    public long getTimeMs() { return timeMs; }
    public int getTokensUsed() { return tokensUsed; }
    public String getReasoning() { return reasoning; }
}
