package com.autoheal;

import com.autoheal.ai.*;
import com.autoheal.config.AutoHealConfig;

/**
 * Internal factory for creating AI providers.
 */
class AutoHealFactory {

    static AIProvider createProvider(AutoHealConfig config) {
        switch (config.getAiProvider()) {
            case GEMINI:
                return new GeminiProvider(config.getAiApiKey(), config.getAiModel());
            case OPENAI:
                return new OpenAIProvider(config.getAiApiKey(), config.getAiModel());
            default:
                return new ClaudeProvider(config.getAiApiKey(), config.getAiModel());
        }
    }
}
