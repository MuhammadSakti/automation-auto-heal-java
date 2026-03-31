package com.autoheal.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class GeminiProvider implements AIProvider {

    private static final String API_URL = "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";
    private static final MediaType JSON = MediaType.get("application/json");

    private final String apiKey;
    private final String model;
    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient client;

    public GeminiProvider(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public AIResponse findLocator(String domSnapshot, String description, String originalSelector) {
        try {
            String prompt = buildPrompt(domSnapshot, description, originalSelector);
            String url = String.format(API_URL, model, apiKey);

            ObjectNode body = mapper.createObjectNode();
            ArrayNode contents = body.putArray("contents");
            ObjectNode content = contents.addObject();
            ArrayNode parts = content.putArray("parts");
            ObjectNode part = parts.addObject();
            part.put("text", prompt);

            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(mapper.writeValueAsString(body), JSON))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "unknown error";
                    throw new IOException("Gemini API error " + response.code() + ": " + errorBody);
                }

                JsonNode root = mapper.readTree(response.body().string());
                String text = root.path("candidates").get(0)
                        .path("content").path("parts").get(0)
                        .path("text").asText();
                int totalTokens = root.path("usageMetadata").path("totalTokenCount").asInt(0);

                return parseResponse(text, totalTokens);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to call Gemini API: " + e.getMessage(), e);
        }
    }

    private String buildPrompt(String domSnapshot, String description, String originalSelector) {
        return "You are a test automation expert. A UI locator has broken and needs to be healed.\n\n" +
                "Original selector: " + originalSelector + "\n" +
                "Element description: " + description + "\n\n" +
                "Current page DOM:\n```html\n" + domSnapshot + "\n```\n\n" +
                "Find the best CSS or XPath selector for the described element.\n" +
                "Respond in this exact format (no markdown, no extra text):\n" +
                "SELECTOR: <the selector>\n" +
                "REASONING: <brief explanation>";
    }

    private AIResponse parseResponse(String text, int tokensUsed) {
        String selector = "";
        String reasoning = "";

        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.startsWith("SELECTOR:")) {
                selector = line.substring("SELECTOR:".length()).trim();
            } else if (line.startsWith("REASONING:")) {
                reasoning = line.substring("REASONING:".length()).trim();
            }
        }

        if (selector.isEmpty()) {
            selector = text.trim();
            reasoning = "Raw AI response (could not parse structured format)";
        }

        return new AIResponse(selector, reasoning, tokensUsed);
    }
}
