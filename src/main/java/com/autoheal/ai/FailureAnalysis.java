package com.autoheal.ai;

import java.util.Collections;
import java.util.List;

public class FailureAnalysis {

    private final String summary;
    private final List<String> possibleCauses;
    private final List<String> suggestions;
    private final int tokensUsed;

    public FailureAnalysis(String summary, List<String> possibleCauses, List<String> suggestions, int tokensUsed) {
        this.summary = summary;
        this.possibleCauses = Collections.unmodifiableList(possibleCauses);
        this.suggestions = Collections.unmodifiableList(suggestions);
        this.tokensUsed = tokensUsed;
    }

    public String getSummary() { return summary; }
    public List<String> getPossibleCauses() { return possibleCauses; }
    public List<String> getSuggestions() { return suggestions; }
    public int getTokensUsed() { return tokensUsed; }
}
