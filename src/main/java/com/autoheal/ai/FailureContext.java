package com.autoheal.ai;

public class FailureContext {

    private final String screenshotBase64;
    private final String mediaType;
    private final String errorLog;
    private final String pageUrl;
    private final String domSnapshot;

    private FailureContext(Builder builder) {
        this.screenshotBase64 = builder.screenshotBase64;
        this.mediaType = builder.mediaType != null ? builder.mediaType : "image/jpeg";
        this.errorLog = builder.errorLog;
        this.pageUrl = builder.pageUrl;
        this.domSnapshot = builder.domSnapshot;
    }

    public String getScreenshotBase64() { return screenshotBase64; }
    public String getMediaType() { return mediaType; }
    public String getErrorLog() { return errorLog; }
    public String getPageUrl() { return pageUrl; }
    public String getDomSnapshot() { return domSnapshot; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String screenshotBase64;
        private String mediaType;
        private String errorLog;
        private String pageUrl;
        private String domSnapshot;

        public Builder screenshotBase64(String screenshotBase64) {
            this.screenshotBase64 = screenshotBase64;
            return this;
        }

        public Builder mediaType(String mediaType) {
            this.mediaType = mediaType;
            return this;
        }

        public Builder errorLog(String errorLog) {
            this.errorLog = errorLog;
            return this;
        }

        public Builder pageUrl(String pageUrl) {
            this.pageUrl = pageUrl;
            return this;
        }

        public Builder domSnapshot(String domSnapshot) {
            this.domSnapshot = domSnapshot;
            return this;
        }

        public FailureContext build() {
            if (errorLog == null || errorLog.isEmpty()) {
                throw new IllegalStateException("errorLog is required");
            }
            return new FailureContext(this);
        }
    }
}
