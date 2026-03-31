package com.autoheal.config;

import io.github.cdimascio.dotenv.Dotenv;

public class AutoHealConfig {

    public enum AiProvider { CLAUDE, GEMINI, OPENAI }
    public enum AutoFixMode { OFF, AUTO, MANUAL }

    private AiProvider aiProvider;
    private String aiApiKey;
    private String aiModel;
    private String reportPath;
    private AutoFixMode autoFix;
    private boolean cacheEnabled;

    private AutoHealConfig() {}

    public static AutoHealConfig fromEnv() {
        Dotenv dotenv;
        try {
            dotenv = Dotenv.configure().ignoreIfMissing().load();
        } catch (Exception e) {
            dotenv = null;
        }

        AutoHealConfig config = new AutoHealConfig();
        config.aiProvider = parseProvider(resolve(dotenv, "AUTOHEAL_AI_PROVIDER", "claude"));
        config.aiApiKey = resolve(dotenv, "AUTOHEAL_AI_API_KEY", "");
        config.aiModel = resolve(dotenv, "AUTOHEAL_AI_MODEL", defaultModel(config.aiProvider));
        config.reportPath = resolve(dotenv, "AUTOHEAL_REPORT_PATH", "./autoheal-reports/");
        config.autoFix = parseAutoFix(resolve(dotenv, "AUTOHEAL_AUTOFIX", "off"));
        config.cacheEnabled = Boolean.parseBoolean(resolve(dotenv, "AUTOHEAL_CACHE_ENABLED", "true"));
        return config;
    }

    private static String resolve(Dotenv dotenv, String key, String defaultValue) {
        String sysEnv = System.getenv(key);
        if (sysEnv != null && !sysEnv.isEmpty()) return sysEnv;
        if (dotenv != null) {
            String dotenvVal = dotenv.get(key);
            if (dotenvVal != null && !dotenvVal.isEmpty()) return dotenvVal;
        }
        return defaultValue;
    }

    private static AiProvider parseProvider(String value) {
        switch (value.toLowerCase()) {
            case "gemini": return AiProvider.GEMINI;
            case "openai": return AiProvider.OPENAI;
            default: return AiProvider.CLAUDE;
        }
    }

    private static AutoFixMode parseAutoFix(String value) {
        switch (value.toLowerCase()) {
            case "auto": return AutoFixMode.AUTO;
            case "manual": return AutoFixMode.MANUAL;
            default: return AutoFixMode.OFF;
        }
    }

    private static String defaultModel(AiProvider provider) {
        switch (provider) {
            case GEMINI: return "gemini-2.0-flash";
            case OPENAI: return "gpt-4o";
            default: return "claude-sonnet-4-6";
        }
    }

    // Getters
    public AiProvider getAiProvider() { return aiProvider; }
    public String getAiApiKey() { return aiApiKey; }
    public String getAiModel() { return aiModel; }
    public String getReportPath() { return reportPath; }
    public AutoFixMode getAutoFix() { return autoFix; }
    public boolean isCacheEnabled() { return cacheEnabled; }

    // Builder
    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private final AutoHealConfig config;

        private Builder() {
            this.config = AutoHealConfig.fromEnv();
        }

        public Builder aiProvider(AiProvider provider) { config.aiProvider = provider; return this; }
        public Builder aiApiKey(String key) { config.aiApiKey = key; return this; }
        public Builder aiModel(String model) { config.aiModel = model; return this; }
        public Builder reportPath(String path) { config.reportPath = path; return this; }
        public Builder autoFix(AutoFixMode mode) { config.autoFix = mode; return this; }
        public Builder cacheEnabled(boolean enabled) { config.cacheEnabled = enabled; return this; }

        public AutoHealConfig build() { return config; }
    }
}
