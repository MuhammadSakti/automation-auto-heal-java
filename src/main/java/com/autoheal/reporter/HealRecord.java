package com.autoheal.reporter;

import com.autoheal.ai.FailureAnalysis;
import com.autoheal.finder.HealResult;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class HealRecord {

    public enum Status { SUCCESS, FAILED }

    private String originalSelector;
    private String actualSelector;
    private HealResult.Strategy strategy;
    private Status status;
    private long timeMs;
    private int tokensUsed;
    private String reasoning;
    private String sourceFile;
    private int sourceLine;
    private String elementInfo;
    private FailureAnalysis failureAnalysis;
    private String screenshotBase64;

    public HealRecord() {}

    public static HealRecord success(String originalSelector, String actualSelector,
                                     HealResult.Strategy strategy, long timeMs, int tokensUsed,
                                     String reasoning, String sourceFile, int sourceLine,
                                     String elementInfo) {
        HealRecord r = new HealRecord();
        r.originalSelector = originalSelector;
        r.actualSelector = actualSelector;
        r.strategy = strategy;
        r.status = Status.SUCCESS;
        r.timeMs = timeMs;
        r.tokensUsed = tokensUsed;
        r.reasoning = reasoning;
        r.sourceFile = sourceFile;
        r.sourceLine = sourceLine;
        r.elementInfo = elementInfo;
        return r;
    }

    public static HealRecord failed(String originalSelector, String attemptedSelector,
                                    long timeMs, int tokensUsed, String reasoning,
                                    String sourceFile, int sourceLine) {
        HealRecord r = new HealRecord();
        r.originalSelector = originalSelector;
        r.actualSelector = attemptedSelector;
        r.strategy = HealResult.Strategy.DOM_HEALED;
        r.status = Status.FAILED;
        r.timeMs = timeMs;
        r.tokensUsed = tokensUsed;
        r.reasoning = reasoning;
        r.sourceFile = sourceFile;
        r.sourceLine = sourceLine;
        return r;
    }

    // Getters
    public String getOriginalSelector() { return originalSelector; }
    public String getActualSelector() { return actualSelector; }
    public HealResult.Strategy getStrategy() { return strategy; }
    public Status getStatus() { return status; }
    public long getTimeMs() { return timeMs; }
    public int getTokensUsed() { return tokensUsed; }
    public String getReasoning() { return reasoning; }
    public String getSourceFile() { return sourceFile; }
    public int getSourceLine() { return sourceLine; }
    public String getElementInfo() { return elementInfo; }

    // Setters for Jackson deserialization
    public void setOriginalSelector(String originalSelector) { this.originalSelector = originalSelector; }
    public void setActualSelector(String actualSelector) { this.actualSelector = actualSelector; }
    public void setStrategy(HealResult.Strategy strategy) { this.strategy = strategy; }
    public void setStatus(Status status) { this.status = status; }
    public void setTimeMs(long timeMs) { this.timeMs = timeMs; }
    public void setTokensUsed(int tokensUsed) { this.tokensUsed = tokensUsed; }
    public void setReasoning(String reasoning) { this.reasoning = reasoning; }
    public void setSourceFile(String sourceFile) { this.sourceFile = sourceFile; }
    public void setSourceLine(int sourceLine) { this.sourceLine = sourceLine; }
    public void setElementInfo(String elementInfo) { this.elementInfo = elementInfo; }

    public FailureAnalysis getFailureAnalysis() { return failureAnalysis; }
    public void setFailureAnalysis(FailureAnalysis failureAnalysis) { this.failureAnalysis = failureAnalysis; }

    public String getScreenshotBase64() { return screenshotBase64; }
    public void setScreenshotBase64(String screenshotBase64) { this.screenshotBase64 = screenshotBase64; }

    public static HealRecord fromFailureAnalysis(FailureAnalysis analysis) {
        HealRecord r = new HealRecord();
        r.status = Status.FAILED;
        r.reasoning = analysis.getSummary();
        r.tokensUsed = analysis.getTokensUsed();
        r.failureAnalysis = analysis;
        return r;
    }
}
