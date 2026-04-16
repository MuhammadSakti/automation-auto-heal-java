package com.autoheal.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

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
        Properties props = new Properties();
        try (InputStream is = AutoHealConfig.class.getClassLoader()
                .getResourceAsStream("autoheal.properties")) {
            if (is != null) {
                props.load(is);
            }
        } catch (IOException e) {
            // ignore — properties file is optional
        }

        AutoHealConfig config = new AutoHealConfig();
        config.aiProvider = parseProvider(resolve(props, "AUTOHEAL_AI_PROVIDER", "autoheal.ai.provider", "claude"));
        config.aiApiKey = resolveApiKey(props, config.aiProvider);
        config.aiModel = resolve(props, "AUTOHEAL_AI_MODEL", "autoheal.ai.model", defaultModel(config.aiProvider));
        config.reportPath = resolve(props, "AUTOHEAL_REPORT_PATH", "autoheal.report-path", "./autoheal-reports/");
        config.autoFix = parseAutoFix(resolve(props, "AUTOHEAL_AUTOFIX", "autoheal.autofix", "off"));
        config.cacheEnabled = Boolean.parseBoolean(resolve(props, "AUTOHEAL_CACHE_ENABLED", "autoheal.cache-enabled", "true"));
        return config;
    }

    private static String resolve(Properties props, String envKey, String propKey, String defaultValue) {
        String sysEnv = System.getenv(envKey);
        if (sysEnv != null && !sysEnv.isEmpty()) return sysEnv;
        String sysProp = System.getProperty(propKey);
        if (sysProp != null && !sysProp.isEmpty()) return sysProp;
        String val = props.getProperty(propKey);
        if (val != null && !val.isEmpty()) return val;
        val = props.getProperty(envKey);
        if (val != null && !val.isEmpty()) return val;
        return defaultValue;
    }

    private static String resolveApiKey(Properties props, AiProvider provider) {
        // Try generic key first
        String key = resolve(props, "AUTOHEAL_AI_API_KEY", "autoheal.ai.api-key", "");
        if (!key.isEmpty()) return key;

        // Fall back to provider-specific keys
        switch (provider) {
            case CLAUDE:
                key = resolve(props, "CLAUDE_API_KEY", "claude.api-key", "");
                if (!key.isEmpty()) return key;
                return resolve(props, "ANTHROPIC_API_KEY", "anthropic.api-key", "");
            case OPENAI:
                return resolve(props, "OPENAI_API_KEY", "openai.api-key", "");
            case GEMINI:
                key = resolve(props, "GEMINI_API_KEY", "gemini.api-key", "");
                if (!key.isEmpty()) return key;
                return resolve(props, "GOOGLE_API_KEY", "google.api-key", "");
            default:
                return "";
        }
    }

    private static AiProvider parseProvider(String value) {
        switch (value.toLowerCase()) {
            case "gemini": return AiProvider.GEMINI;
            case "openai": case "chatgpt": return AiProvider.OPENAI;
            case "anthropic": case "claude": return AiProvider.CLAUDE;
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
