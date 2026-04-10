package com.autoheal.ai;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.List;

public class FailureAnalysis {

    private final String summary;
    private final List<String> possibleCauses;
    private final List<String> suggestions;
    private final int tokensUsed;

    @JsonCreator
    public FailureAnalysis(
            @JsonProperty("summary") String summary,
            @JsonProperty("possibleCauses") List<String> possibleCauses,
            @JsonProperty("suggestions") List<String> suggestions,
            @JsonProperty("tokensUsed") int tokensUsed) {
        this.summary = summary;
        this.possibleCauses = possibleCauses != null
                ? Collections.unmodifiableList(possibleCauses) : Collections.emptyList();
        this.suggestions = suggestions != null
                ? Collections.unmodifiableList(suggestions) : Collections.emptyList();
        this.tokensUsed = tokensUsed;
    }

    public String getSummary() { return summary; }
    public List<String> getPossibleCauses() { return possibleCauses; }
    public List<String> getSuggestions() { return suggestions; }
    public int getTokensUsed() { return tokensUsed; }
}
