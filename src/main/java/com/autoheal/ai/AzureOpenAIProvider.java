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
import java.util.Map;

public class AzureOpenAIProvider implements AIProvider {

    private final String apiKey;
    private final String apiUrl;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client;

    public AzureOpenAIProvider(String apiKey, String endpoint, String deployment, String apiVersion) {
        this.apiKey = apiKey;
        String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.apiUrl = base + "/openai/deployments/" + deployment + "/chat/completions?api-version=" + apiVersion;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public AIResponse findLocator(String domSnapshot, String description, String originalSelector) {
        try {
            String prompt = buildPrompt(domSnapshot, description, originalSelector);
            ObjectNode body = buildRequestBody(1024, "You are a test automation expert that heals broken UI locators.", prompt);

            String responseText = callApi(body, Duration.ofSeconds(60));
            JsonNode root = mapper.readTree(responseText);
            String text = extractContent(root);
            int totalTokens = root.path("usage").path("total_tokens").asInt(0);

            return parseResponse(text, totalTokens);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to call Azure OpenAI API: " + e.getMessage(), e);
        }
    }

    @Override
    public Map<String, AIResponse> findLocatorsBatch(String domSnapshot, Map<String, String> locators) {
        try {
            String prompt = buildBatchPrompt(domSnapshot, locators);
            ObjectNode body = buildRequestBody(4096, "You are a test automation expert that heals broken UI locators.", prompt);

            String responseText = callApi(body, Duration.ofSeconds(120));
            JsonNode root = mapper.readTree(responseText);
            String text = extractContent(root);
            int totalTokens = root.path("usage").path("total_tokens").asInt(0);

            return AIProvider.parseBatchResponse(text, totalTokens, locators.size());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to call Azure OpenAI API (batch): " + e.getMessage(), e);
        }
    }

    @Override
    public FailureAnalysis analyzeFailure(FailureContext context) {
        try {
            String prompt = AIProvider.buildFailureAnalysisPrompt(context);

            ObjectNode body = mapper.createObjectNode();
            body.put("max_tokens", 2048);
            ArrayNode messages = body.putArray("messages");
            ObjectNode sysMsg = messages.addObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", "You are a test automation failure analyst.");
            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            ArrayNode content = userMsg.putArray("content");

            ObjectNode imagePart = content.addObject();
            imagePart.put("type", "image_url");
            ObjectNode imageUrl = imagePart.putObject("image_url");
            imageUrl.put("url", "data:" + context.getMediaType() + ";base64," + context.getScreenshotBase64());

            ObjectNode textPart = content.addObject();
            textPart.put("type", "text");
            textPart.put("text", prompt);

            String responseText = callApi(body, Duration.ofSeconds(60));
            JsonNode root = mapper.readTree(responseText);
            String text = extractContent(root);
            int totalTokens = root.path("usage").path("total_tokens").asInt(0);

            return AIProvider.parseFailureResponse(text, totalTokens);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to call Azure OpenAI API for failure analysis: " + e.getMessage(), e);
        }
    }

    private ObjectNode buildRequestBody(int maxTokens, String systemPrompt, String userPrompt) {
        ObjectNode body = mapper.createObjectNode();
        body.put("max_tokens", maxTokens);
        ArrayNode messages = body.putArray("messages");
        ObjectNode sysMsg = messages.addObject();
        sysMsg.put("role", "system");
        sysMsg.put("content", systemPrompt);
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);
        return body;
    }

    private String callApi(ObjectNode body, Duration timeout) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("api-key", apiKey)
                .header("Content-Type", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Azure OpenAI API error " + response.statusCode() + ": " + response.body());
        }

        return response.body();
    }

    private String extractContent(JsonNode root) {
        return root.path("choices").get(0).path("message").path("content").asText();
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

    private String buildBatchPrompt(String domSnapshot, Map<String, String> locators) {
        StringBuilder sb = new StringBuilder();
        sb.append("Multiple UI locators have broken and need to be healed.\n\n");
        sb.append("Current page DOM:\n```html\n").append(domSnapshot).append("\n```\n\n");
        sb.append("Broken locators:\n");
        int i = 1;
        for (Map.Entry<String, String> entry : locators.entrySet()) {
            sb.append(i++).append(". Original: ").append(entry.getKey())
              .append(" | Description: ").append(entry.getValue()).append("\n");
        }
        sb.append("\nFor each locator, find the best CSS or XPath selector.\n");
        sb.append("Respond in this exact format for each (no markdown, no extra text):\n");
        sb.append("ORIGINAL: <original selector>\nSELECTOR: <new selector>\nREASONING: <brief explanation>\n\n");
        return sb.toString();
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
