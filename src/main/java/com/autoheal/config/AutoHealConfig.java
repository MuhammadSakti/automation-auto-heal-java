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

    /** @deprecated Use {@link #load()} instead. */
    @Deprecated
    public static AutoHealConfig fromEnv() { return load(); }

    public static AutoHealConfig load() {
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
        String[][] candidates = switch (provider) {
            case CLAUDE -> new String[][]{
                    {"AUTOHEAL_AI_API_KEY", "autoheal.ai.api-key"},
                    {"CLAUDE_API_KEY", "claude.api-key"},
                    {"ANTHROPIC_API_KEY", "anthropic.api-key"}};
            case OPENAI -> new String[][]{
                    {"AUTOHEAL_AI_API_KEY", "autoheal.ai.api-key"},
                    {"OPENAI_API_KEY", "openai.api-key"}};
            case GEMINI -> new String[][]{
                    {"AUTOHEAL_AI_API_KEY", "autoheal.ai.api-key"},
                    {"GEMINI_API_KEY", "gemini.api-key"},
                    {"GOOGLE_API_KEY", "google.api-key"}};
        };
        for (String[] pair : candidates) {
            String key = resolve(props, pair[0], pair[1], "");
            if (!key.isEmpty()) return key;
        }
        return "";
    }

    private static AiProvider parseProvider(String value) {
        return switch (value.toLowerCase()) {
            case "gemini" -> AiProvider.GEMINI;
            case "openai", "chatgpt" -> AiProvider.OPENAI;
            default -> AiProvider.CLAUDE;
        };
    }

    private static AutoFixMode parseAutoFix(String value) {
        return switch (value.toLowerCase()) {
            case "auto" -> AutoFixMode.AUTO;
            case "manual" -> AutoFixMode.MANUAL;
            default -> AutoFixMode.OFF;
        };
    }

    private static String defaultModel(AiProvider provider) {
        return switch (provider) {
            case GEMINI -> "gemini-2.0-flash";
            case OPENAI -> "gpt-4o";
            default -> "claude-sonnet-4-6";
        };
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
            this.config = AutoHealConfig.load();
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
