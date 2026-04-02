package com.autoheal.reporter;

import com.autoheal.ai.FailureAnalysis;
import com.autoheal.finder.HealResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class ReportGenerator {

    private final String reportPath;
    private final String reportName;
    private final ObjectMapper mapper = new ObjectMapper();

    public ReportGenerator(String reportPath) {
        this(reportPath, null);
    }

    public ReportGenerator(String reportPath, String reportName) {
        this.reportPath = reportPath;
        this.reportName = reportName;
    }

    public void generate(List<HealRecord> records) {
        if (records.isEmpty()) return;

        LocalDateTime now = LocalDateTime.now();
        String fileTimestamp = now.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String displayTimestamp = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String filePrefix = reportName != null ? "AutoHeal_" + reportName + "_" : "AutoHeal_";
        Path dir = Paths.get(reportPath);

        try {
            Files.createDirectories(dir);

            // JSON report
            File jsonFile = dir.resolve(filePrefix + fileTimestamp + ".json").toFile();
            mapper.writerWithDefaultPrettyPrinter().writeValue(jsonFile, records);

            // HTML report
            String html = generateHtml(records, displayTimestamp);
            Path htmlFile = dir.resolve(filePrefix + fileTimestamp + ".html");
            Files.write(htmlFile, html.getBytes(StandardCharsets.UTF_8));

            System.out.println("[AutoHeal] Reports generated at: " + dir.toAbsolutePath());
            System.out.println("  JSON: " + jsonFile.getName());
            System.out.println("  HTML: " + htmlFile.getFileName());

        } catch (IOException e) {
            System.err.println("[AutoHeal] Failed to generate report: " + e.getMessage());
        }
    }

    static String formatTime(long ms) {
        long hours = ms / 3_600_000;
        long minutes = (ms % 3_600_000) / 60_000;
        long seconds = (ms % 60_000) / 1_000;
        long millis = ms % 1_000;
        return String.format("%02d:%02d:%02d.%03d", hours, minutes, seconds, millis);
    }

    private String generateHtml(List<HealRecord> records, String displayTimestamp) {
        long total = records.size();
        long successful = records.stream().filter(r -> r.getStatus() == HealRecord.Status.SUCCESS).count();
        long failed = total - successful;
        long original = records.stream().filter(r -> r.getStrategy() == HealResult.Strategy.ORIGINAL).count();
        long domHealed = records.stream().filter(r -> r.getStrategy() == HealResult.Strategy.DOM_HEALED && r.getStatus() == HealRecord.Status.SUCCESS).count();
        long cached = records.stream().filter(r -> r.getStrategy() == HealResult.Strategy.CACHED).count();
        int totalTokens = records.stream().mapToInt(HealRecord::getTokensUsed).sum();
        long totalTime = records.stream().mapToLong(HealRecord::getTimeMs).sum();

        try {
            InputStream is = getClass().getClassLoader().getResourceAsStream("report-template.html");
            if (is != null) {
                String template = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                String jsonData = mapper.writeValueAsString(records);
                return template
                        .replace("{{timestamp}}", displayTimestamp)
                        .replace("{{total}}", String.valueOf(total))
                        .replace("{{successful}}", String.valueOf(successful))
                        .replace("{{failed}}", String.valueOf(failed))
                        .replace("{{original}}", String.valueOf(original))
                        .replace("{{domHealed}}", String.valueOf(domHealed))
                        .replace("{{cached}}", String.valueOf(cached))
                        .replace("{{totalTokens}}", String.valueOf(totalTokens))
                        .replace("{{totalTime}}", formatTime(totalTime))
                        .replace("{{records}}", jsonData)
                        .replace("{{tableRows}}", buildTableRows(records));
            }
        } catch (IOException ignored) {
        }

        // Fallback: inline HTML
        return buildInlineHtml(records, displayTimestamp, total, successful, failed, original, domHealed, cached, totalTokens, totalTime);
    }

    private String buildTableRows(List<HealRecord> records) {
        StringBuilder sb = new StringBuilder();
        for (HealRecord r : records) {
            String statusClass = r.getStatus() == HealRecord.Status.SUCCESS ? "success" : "failed";
            sb.append("<tr class=\"").append(statusClass).append("\">");
            sb.append("<td>").append(escapeHtml(r.getOriginalSelector())).append("</td>");
            sb.append("<td>").append(escapeHtml(r.getActualSelector())).append("</td>");
            sb.append("<td>").append(r.getStrategy()).append("</td>");
            sb.append("<td>").append(r.getStatus()).append("</td>");
            sb.append("<td>").append(formatTime(r.getTimeMs())).append("</td>");
            sb.append("<td>").append(r.getTokensUsed()).append("</td>");
            sb.append("<td>").append(escapeHtml(r.getReasoning())).append("</td>");
            sb.append("<td>").append(r.getSourceFile() != null ? escapeHtml(r.getSourceFile()) + ":" + r.getSourceLine() : "-").append("</td>");
            sb.append("</tr>\n");

            if (r.getFailureAnalysis() != null) {
                sb.append(buildFailureAnalysisRow(r.getFailureAnalysis()));
            }
        }
        return sb.toString();
    }

    private String buildFailureAnalysisRow(FailureAnalysis analysis) {
        StringBuilder sb = new StringBuilder();
        sb.append("<tr class=\"analysis-row\"><td colspan=\"8\" style=\"background:#fff8f0;padding:12px 16px;white-space:normal;\">");
        sb.append("<details><summary style=\"cursor:pointer;font-weight:600;color:#e65100;\">Failure Analysis</summary>");
        sb.append("<div style=\"margin-top:8px;\">");
        sb.append("<p><strong>Summary:</strong> ").append(escapeHtml(analysis.getSummary())).append("</p>");

        if (!analysis.getPossibleCauses().isEmpty()) {
            sb.append("<p style=\"margin-top:6px;\"><strong>Possible Causes:</strong></p><ul>");
            for (String cause : analysis.getPossibleCauses()) {
                sb.append("<li>").append(escapeHtml(cause)).append("</li>");
            }
            sb.append("</ul>");
        }

        if (!analysis.getSuggestions().isEmpty()) {
            sb.append("<p style=\"margin-top:6px;\"><strong>Suggestions:</strong></p><ul>");
            for (String suggestion : analysis.getSuggestions()) {
                sb.append("<li>").append(escapeHtml(suggestion)).append("</li>");
            }
            sb.append("</ul>");
        }

        sb.append("</div></details></td></tr>\n");
        return sb.toString();
    }

    private String buildInlineHtml(List<HealRecord> records, String displayTimestamp,
                                   long total, long successful, long failed,
                                   long original, long domHealed, long cached,
                                   int totalTokens, long totalTime) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
        sb.append("<title>AutoHeal Report - ").append(displayTimestamp).append("</title>");
        sb.append("<style>");
        sb.append("body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;margin:20px;background:#f5f5f5;}");
        sb.append(".container{max-width:1200px;margin:0 auto;}");
        sb.append("h1{color:#1a1a2e;}");
        sb.append(".cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px;margin:20px 0;}");
        sb.append(".card{background:#fff;border-radius:8px;padding:16px;text-align:center;box-shadow:0 2px 4px rgba(0,0,0,0.1);}");
        sb.append(".card .value{font-size:28px;font-weight:bold;color:#1a1a2e;}");
        sb.append(".card .label{font-size:12px;color:#666;margin-top:4px;}");
        sb.append(".card.green .value{color:#27ae60;} .card.red .value{color:#e74c3c;} .card.blue .value{color:#2980b9;} .card.orange .value{color:#f39c12;}");
        sb.append("table{width:100%;border-collapse:collapse;background:#fff;border-radius:8px;overflow:hidden;box-shadow:0 2px 4px rgba(0,0,0,0.1);}");
        sb.append("th{background:#1a1a2e;color:#fff;padding:12px 8px;text-align:left;font-size:13px;}");
        sb.append("td{padding:10px 8px;border-bottom:1px solid #eee;font-size:13px;max-width:300px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}");
        sb.append("tr.success td:nth-child(4){color:#27ae60;font-weight:bold;}");
        sb.append("tr.failed td:nth-child(4){color:#e74c3c;font-weight:bold;}");
        sb.append("tr:hover td{background:#f8f9fa;}");
        sb.append("</style></head><body><div class='container'>");
        sb.append("<h1>AutoHeal Report</h1>");
        sb.append("<p style='color:#666;'>Generated: ").append(displayTimestamp).append("</p>");
        sb.append("<div class='cards'>");
        sb.append("<div class='card'><div class='value'>").append(total).append("</div><div class='label'>Total</div></div>");
        sb.append("<div class='card green'><div class='value'>").append(successful).append("</div><div class='label'>Successful</div></div>");
        sb.append("<div class='card red'><div class='value'>").append(failed).append("</div><div class='label'>Failed</div></div>");
        sb.append("<div class='card blue'><div class='value'>").append(original).append("</div><div class='label'>Original</div></div>");
        sb.append("<div class='card orange'><div class='value'>").append(domHealed).append("</div><div class='label'>DOM Healed</div></div>");
        sb.append("<div class='card blue'><div class='value'>").append(cached).append("</div><div class='label'>Cached</div></div>");
        sb.append("<div class='card'><div class='value'>").append(totalTokens).append("</div><div class='label'>Tokens Used</div></div>");
        sb.append("<div class='card'><div class='value'>").append(formatTime(totalTime)).append("</div><div class='label'>Total Time</div></div>");
        sb.append("</div>");
        sb.append("<table><thead><tr><th>Original Selector</th><th>Actual Selector</th><th>Strategy</th><th>Status</th><th>Time</th><th>Tokens</th><th>Reasoning</th><th>Source</th></tr></thead><tbody>");
        sb.append(buildTableRows(records));
        sb.append("</tbody></table></div></body></html>");
        return sb.toString();
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
