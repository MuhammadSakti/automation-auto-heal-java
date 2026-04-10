package com.autoheal.reporter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans per-class AutoHeal_*.json reports in a run folder and writes a
 * cross-class dashboard.html that links back to each class's HTML report.
 */
public final class ReportDashboardGenerator {

    private static final Pattern REPORT_PATTERN = Pattern.compile(
            "^AutoHeal_(?<name>.*?)_(?<ts>\\d{8}_\\d{6})\\.json$");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ReportDashboardGenerator() {}

    public static void generate(String reportRoot) {
        Path target = resolveTargetFolder(reportRoot);
        if (target == null) {
            System.out.println("[AutoHeal] No run folder resolved for dashboard; skipping.");
            return;
        }
        generate(target);
    }

    public static void generate(Path runFolder) {
        if (runFolder == null || !Files.isDirectory(runFolder)) {
            System.out.println("[AutoHeal] Run folder does not exist; skipping dashboard.");
            return;
        }

        List<ClassRollup> rollups = scan(runFolder);
        if (rollups.isEmpty()) {
            System.out.println("[AutoHeal] No per-class reports found; dashboard not generated.");
            return;
        }

        GlobalRollup global = aggregate(rollups);
        String html = renderHtml(runFolder, rollups, global);

        Path output = runFolder.resolve("dashboard.html");
        try {
            Files.write(output, html.getBytes(StandardCharsets.UTF_8));
            System.out.println("[AutoHeal] Dashboard: " + output.toAbsolutePath());
            System.out.println("  Classes aggregated: " + rollups.size());
            System.out.println("  Records aggregated: " + global.totalRecords);
        } catch (IOException e) {
            System.err.println("[AutoHeal] Failed to write dashboard: " + e.getMessage());
        }
    }

    // ---- folder resolution -------------------------------------------------

    private static Path resolveTargetFolder(String reportRoot) {
        Path current = RunContext.current();
        if (current != null && Files.isDirectory(current)) {
            return current;
        }
        if (reportRoot == null) {
            return null;
        }
        Path root = Paths.get(reportRoot);
        if (!Files.isDirectory(root)) {
            return null;
        }
        Path newest = findNewestRunFolder(root);
        if (newest != null) {
            return newest;
        }
        // Fallback: scan reportRoot itself for pre-1.3.0 flat layouts.
        return root;
    }

    private static Path findNewestRunFolder(Path root) {
        Path newest = null;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root, "run_*")) {
            for (Path p : stream) {
                if (!Files.isDirectory(p)) continue;
                if (newest == null || p.getFileName().toString().compareTo(newest.getFileName().toString()) > 0) {
                    newest = p;
                }
            }
        } catch (IOException e) {
            System.err.println("[AutoHeal] Failed to scan run folders in " + root + ": " + e.getMessage());
        }
        return newest;
    }

    // ---- scanning ----------------------------------------------------------

    private static List<ClassRollup> scan(Path folder) {
        List<ClassRollup> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "AutoHeal_*.json")) {
            for (Path json : stream) {
                ClassRollup rollup = rollupFile(json);
                if (rollup != null) {
                    out.add(rollup);
                }
            }
        } catch (IOException e) {
            System.err.println("[AutoHeal] Failed to scan dashboard folder " + folder + ": " + e.getMessage());
        }
        out.sort(Comparator.comparing((ClassRollup r) -> r.reportName == null ? "" : r.reportName));
        return out;
    }

    private static ClassRollup rollupFile(Path json) {
        String fileName = json.getFileName().toString();
        Matcher m = REPORT_PATTERN.matcher(fileName);
        String name;
        String displayTs;
        if (m.matches()) {
            name = m.group("name");
            String raw = m.group("ts");
            displayTs = formatDisplayTimestamp(raw);
        } else {
            name = "(unnamed)";
            displayTs = "-";
        }
        if (name == null || name.isEmpty()) {
            name = "(unnamed)";
        }

        List<HealRecord> records;
        try {
            records = MAPPER.readValue(json.toFile(), new TypeReference<List<HealRecord>>() {});
        } catch (IOException e) {
            System.err.println("[AutoHeal] Skipping unreadable report " + fileName + ": " + e.getMessage());
            return null;
        }
        if (records == null) {
            records = new ArrayList<>();
        }

        ClassRollup rollup = new ClassRollup();
        rollup.reportName = name;
        rollup.displayTimestamp = displayTs;
        rollup.htmlFileName = fileName.substring(0, fileName.length() - ".json".length()) + ".html";

        for (HealRecord r : records) {
            rollup.totalTimeMs += r.getTimeMs();
            rollup.totalTokens += r.getTokensUsed();

            if (isSkipped(r)) {
                rollup.skipped++;
            } else if (r.getStatus() == HealRecord.Status.SUCCESS) {
                rollup.successful++;
            } else {
                rollup.failed++;
            }
        }
        rollup.classFailed = rollup.failed > 0;
        return rollup;
    }

    private static boolean isSkipped(HealRecord r) {
        if (r.getKind() == HealRecord.Kind.FAILURE_ANALYSIS) return true;
        // Fallback for pre-1.3.0 JSON where kind is absent: a record with no
        // strategy is a failure-analysis record.
        return r.getStrategy() == null;
    }

    private static String formatDisplayTimestamp(String raw) {
        try {
            LocalDateTime t = LocalDateTime.parse(raw, DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            return t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            return raw;
        }
    }

    // ---- aggregation -------------------------------------------------------

    private static GlobalRollup aggregate(List<ClassRollup> rollups) {
        GlobalRollup g = new GlobalRollup();
        for (ClassRollup r : rollups) {
            g.totalClasses++;
            if (r.classFailed) g.failedClasses++;
            g.totalSuccess += r.successful;
            g.totalFailed += r.failed;
            g.totalSkipped += r.skipped;
            g.totalTimeMs += r.totalTimeMs;
            g.totalTokens += r.totalTokens;
        }
        g.totalLocatorsChecked = g.totalSuccess + g.totalFailed;
        g.totalRecords = g.totalLocatorsChecked + g.totalSkipped;
        return g;
    }

    // ---- rendering ---------------------------------------------------------

    private static String renderHtml(Path runFolder, List<ClassRollup> rollups, GlobalRollup g) {
        String displayTs = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String rows = buildClassRows(rollups);

        try {
            InputStream is = ReportDashboardGenerator.class.getClassLoader().getResourceAsStream("report-dashboard-template.html");
            if (is != null) {
                String template = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                return template
                        .replace("{{timestamp}}", displayTs)
                        .replace("{{runFolder}}", escapeHtml(runFolder.getFileName() != null
                                ? runFolder.getFileName().toString() : runFolder.toString()))
                        .replace("{{totalClasses}}", String.valueOf(g.totalClasses))
                        .replace("{{failedClasses}}", String.valueOf(g.failedClasses))
                        .replace("{{failedClassesPct}}", pct(g.failedClasses, g.totalClasses))
                        .replace("{{totalLocators}}", String.valueOf(g.totalLocatorsChecked))
                        .replace("{{totalFailed}}", String.valueOf(g.totalFailed))
                        .replace("{{failedLocatorsPct}}", pct(g.totalFailed, g.totalLocatorsChecked))
                        .replace("{{totalSkipped}}", String.valueOf(g.totalSkipped))
                        .replace("{{totalSuccess}}", String.valueOf(g.totalSuccess))
                        .replace("{{totalTime}}", ReportGenerator.formatTime(g.totalTimeMs))
                        .replace("{{totalTokens}}", String.valueOf(g.totalTokens))
                        .replace("{{classRows}}", rows);
            }
        } catch (IOException ignored) {
        }

        return buildInlineDashboard(displayTs, runFolder, rollups, g, rows);
    }

    private static String buildClassRows(List<ClassRollup> rollups) {
        StringBuilder sb = new StringBuilder();
        for (ClassRollup r : rollups) {
            long checked = r.successful + r.failed;
            String successPct = pct(r.successful, checked);
            String cls = r.classFailed ? "failed" : "passed";
            sb.append("<tr class=\"").append(cls).append("\">");
            sb.append("<td>").append(escapeHtml(r.reportName)).append("</td>");
            sb.append("<td>").append(escapeHtml(r.displayTimestamp)).append("</td>");
            sb.append("<td>").append(checked).append("</td>");
            sb.append("<td>").append(r.failed).append("</td>");
            sb.append("<td>").append(r.skipped).append("</td>");
            sb.append("<td>").append(successPct).append("%</td>");
            sb.append("<td>").append(ReportGenerator.formatTime(r.totalTimeMs)).append("</td>");
            sb.append("<td>").append(r.totalTokens).append("</td>");
            // Force forward slashes for href so file:// URLs work on Windows too.
            sb.append("<td><a class=\"btn-details\" href=\"").append(escapeHtml(r.htmlFileName))
                    .append("\">View Details</a></td>");
            sb.append("</tr>\n");
        }
        return sb.toString();
    }

    private static String buildInlineDashboard(String displayTs, Path runFolder,
                                                List<ClassRollup> rollups, GlobalRollup g, String rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
        sb.append("<title>AutoHeal Dashboard - ").append(displayTs).append("</title>");
        sb.append("<style>body{font-family:sans-serif;margin:20px;}table{width:100%;border-collapse:collapse;}");
        sb.append("th,td{padding:8px;border-bottom:1px solid #ccc;text-align:left;}th{background:#1a1a2e;color:#fff;}");
        sb.append("tr.failed td:nth-child(4){color:#e74c3c;font-weight:bold;}");
        sb.append(".btn-details{padding:4px 8px;background:#1a1a2e;color:#fff;text-decoration:none;border-radius:4px;}");
        sb.append("</style></head><body>");
        sb.append("<h1>AutoHeal Dashboard</h1>");
        sb.append("<p>Generated: ").append(displayTs).append(" &middot; Run: ")
                .append(escapeHtml(runFolder.getFileName() != null ? runFolder.getFileName().toString() : runFolder.toString()))
                .append("</p>");
        sb.append("<ul>");
        sb.append("<li>Total Classes: ").append(g.totalClasses).append("</li>");
        sb.append("<li>Failed Classes: ").append(g.failedClasses).append(" (").append(pct(g.failedClasses, g.totalClasses)).append("%)</li>");
        sb.append("<li>Locators Checked: ").append(g.totalLocatorsChecked).append("</li>");
        sb.append("<li>Failed: ").append(g.totalFailed).append(" (").append(pct(g.totalFailed, g.totalLocatorsChecked)).append("%)</li>");
        sb.append("<li>Successful: ").append(g.totalSuccess).append("</li>");
        sb.append("<li>Skipped: ").append(g.totalSkipped).append("</li>");
        sb.append("<li>Total Time: ").append(ReportGenerator.formatTime(g.totalTimeMs)).append("</li>");
        sb.append("<li>Tokens Used: ").append(g.totalTokens).append("</li>");
        sb.append("</ul>");
        sb.append("<table><thead><tr><th>Class</th><th>Timestamp</th><th>Checked</th><th>Failed</th>");
        sb.append("<th>Skipped</th><th>Success %</th><th>Time</th><th>Tokens</th><th>Details</th></tr></thead><tbody>");
        sb.append(rows);
        sb.append("</tbody></table></body></html>");
        return sb.toString();
    }

    private static String pct(long numerator, long denominator) {
        if (denominator <= 0) return "0";
        double v = (numerator * 100.0) / denominator;
        if (v == Math.floor(v)) {
            return String.valueOf((long) v);
        }
        return String.format("%.1f", v);
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ---- DTOs --------------------------------------------------------------

    private static final class ClassRollup {
        String reportName;
        String displayTimestamp;
        String htmlFileName;
        long successful;
        long failed;
        long skipped;
        long totalTimeMs;
        long totalTokens;
        boolean classFailed;
    }

    private static final class GlobalRollup {
        long totalClasses;
        long failedClasses;
        long totalLocatorsChecked;
        long totalSuccess;
        long totalFailed;
        long totalSkipped;
        long totalTimeMs;
        long totalTokens;
        long totalRecords;
    }
}
