package com.autoheal.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class OpenAIProvider implements AIProvider {

    private static final String API_URL = "https://api.openai.com/v1/chat/completions";

    private final String apiKey;
    private final String model;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client;

    public OpenAIProvider(String apiKey, String model) {
        this.apiKey = apiKey;
        this.model = model;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public AIResponse findLocator(String domSnapshot, String description, String originalSelector) {
        try {
            String prompt = buildPrompt(domSnapshot, description, originalSelector);

            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.put("max_tokens", 1024);
            ArrayNode messages = body.putArray("messages");
            ObjectNode sysMsg = messages.addObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", "You are a test automation expert that heals broken UI locators.");
            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", prompt);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("OpenAI API error " + response.statusCode() + ": " + response.body());
            }

            JsonNode root = mapper.readTree(response.body());
            String text = root.path("choices").get(0).path("message").path("content").asText();
            int totalTokens = root.path("usage").path("total_tokens").asInt(0);

            return parseResponse(text, totalTokens);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to call OpenAI API: " + e.getMessage(), e);
        }
    }

    private String buildPrompt(String domSnapshot, String description, String originalSelector) {
        return "A UI locator has broken and needs to be healed.\n\n" +
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
