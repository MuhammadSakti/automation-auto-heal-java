package com.autoheal.ai;

public class AIResponse {
    private final String selector;
    private final String reasoning;
    private final int tokensUsed;

    public AIResponse(String selector, String reasoning, int tokensUsed) {
        this.selector = selector;
        this.reasoning = reasoning;
        this.tokensUsed = tokensUsed;
    }

    public String getSelector() { return selector; }
    public String getReasoning() { return reasoning; }
    public int getTokensUsed() { return tokensUsed; }
}
