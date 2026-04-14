package com.autoheal.reporter;

import com.autoheal.finder.HealResult;
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
 * Scans ALL run_* subdirectories under the report root and writes a single
 * dashboard.html at the root that aggregates every run's per-class reports.
 */
public final class ReportDashboardGenerator {

    private static final Pattern REPORT_PATTERN = Pattern.compile(
            "^AutoHeal_(?<name>.*?)_(?<ts>\\d{8}_\\d{6})\\.json$");

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ReportDashboardGenerator() {}

    public static void generate(String reportRoot) {
        if (reportRoot == null) {
            System.out.println("[AutoHeal] No report path configured; skipping dashboard.");
            return;
        }
        Path root = Paths.get(reportRoot);
        if (!Files.isDirectory(root)) {
            System.out.println("[AutoHeal] Report path does not exist; skipping dashboard.");
            return;
        }

        // Collect all run_* subdirectories
        List<RunRollup> runs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root, "run_*")) {
            for (Path p : stream) {
                if (!Files.isDirectory(p)) continue;
                String folderName = p.getFileName().toString();
                List<ClassRollup> classes = scan(p, folderName + "/");
                if (!classes.isEmpty()) {
                    runs.add(new RunRollup(folderName, classes));
                }
            }
        } catch (IOException e) {
            System.err.println("[AutoHeal] Failed to scan run folders in " + root + ": " + e.getMessage());
        }

        // Flat-layout fallback (pre-1.3.0 compat)
        if (runs.isEmpty()) {
            List<ClassRollup> classes = scan(root, "");
            if (!classes.isEmpty()) {
                runs.add(new RunRollup("(flat)", classes));
            }
        }

        if (runs.isEmpty()) {
            System.out.println("[AutoHeal] No per-class reports found; dashboard not generated.");
            return;
        }

        // Sort runs reverse-chronologically (newest first by folder name)
        runs.sort(Comparator.comparing((RunRollup r) -> r.folderName).reversed());

        // Compute global rollup across all runs
        List<ClassRollup> allClasses = new ArrayList<>();
        for (RunRollup run : runs) {
            allClasses.addAll(run.classes);
        }
        GlobalRollup global = aggregate(allClasses);

        String html = renderHtml(root, runs, global);

        Path output = root.resolve("dashboard.html");
        try {
            Files.write(output, html.getBytes(StandardCharsets.UTF_8));
            System.out.println("[AutoHeal] Dashboard: " + output.toAbsolutePath());
            System.out.println("  Runs aggregated: " + runs.size());
            System.out.println("  Classes aggregated: " + allClasses.size());
            System.out.println("  Records aggregated: " + global.totalRecords);
        } catch (IOException e) {
            System.err.println("[AutoHeal] Failed to write dashboard: " + e.getMessage());
        }
    }

    // ---- scanning ----------------------------------------------------------

    private static List<ClassRollup> scan(Path folder, String relativePrefix) {
        List<ClassRollup> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "AutoHeal_*.json")) {
            for (Path json : stream) {
                ClassRollup rollup = rollupFile(json, relativePrefix);
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

    private static ClassRollup rollupFile(Path json, String relativePrefix) {
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
        rollup.relativePrefix = relativePrefix;

        for (HealRecord r : records) {
            rollup.totalTimeMs += r.getTimeMs();
            rollup.totalTokens += r.getTokensUsed();

            if (isSkipped(r)) {
                rollup.skipped++;
            } else if (r.getStatus() == HealRecord.Status.SUCCESS) {
                if (r.getStrategy() == HealResult.Strategy.ORIGINAL) {
                    rollup.passed++;
                } else {
                    rollup.healed++;
                }
            } else {
                rollup.failed++;
            }
        }
        rollup.classFailed = rollup.failed > 0 || rollup.healed > 0 || rollup.skipped > 0;
        return rollup;
    }

    private static boolean isSkipped(HealRecord r) {
        if (r.getKind() == HealRecord.Kind.FAILURE_ANALYSIS) return true;
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
            g.totalPassed += r.passed;
            g.totalHealed += r.healed;
            g.totalFailed += r.failed;
            g.totalSkipped += r.skipped;
            g.totalTimeMs += r.totalTimeMs;
            g.totalTokens += r.totalTokens;
        }
        g.totalLocatorsChecked = g.totalPassed + g.totalHealed + g.totalFailed;
        g.totalRecords = g.totalLocatorsChecked + g.totalSkipped;
        return g;
    }

    // ---- rendering ---------------------------------------------------------

    private static String renderHtml(Path reportRoot, List<RunRollup> runs, GlobalRollup g) {
        String displayTs = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String latestRun = runs.get(0).folderName;
        String runSections = buildRunSections(runs);

        try {
            InputStream is = ReportDashboardGenerator.class.getClassLoader().getResourceAsStream("report-dashboard-template.html");
            if (is != null) {
                String template = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                return template
                        .replace("{{timestamp}}", displayTs)
                        .replace("{{latestRun}}", escapeHtml(latestRun))
                        .replace("{{totalRuns}}", String.valueOf(runs.size()))
                        .replace("{{totalClasses}}", String.valueOf(g.totalClasses))
                        .replace("{{failedClasses}}", String.valueOf(g.failedClasses))
                        .replace("{{failedClassesPct}}", pct(g.failedClasses, g.totalClasses))
                        .replace("{{totalLocators}}", String.valueOf(g.totalLocatorsChecked))
                        .replace("{{totalFailed}}", String.valueOf(g.totalFailed))
                        .replace("{{failedLocatorsPct}}", pct(g.totalFailed, g.totalLocatorsChecked))
                        .replace("{{totalSkipped}}", String.valueOf(g.totalSkipped))
                        .replace("{{totalPassed}}", String.valueOf(g.totalPassed))
                        .replace("{{totalHealed}}", String.valueOf(g.totalHealed))
                        .replace("{{healPct}}", pct(g.totalHealed, g.totalHealed + g.totalFailed))
                        .replace("{{totalTime}}", ReportGenerator.formatTime(g.totalTimeMs))
                        .replace("{{totalTokens}}", String.valueOf(g.totalTokens))
                        .replace("{{runSections}}", runSections);
            }
        } catch (IOException ignored) {
        }

        return buildInlineDashboard(displayTs, runs, g, runSections);
    }

    private static String buildRunSections(List<RunRollup> runs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < runs.size(); i++) {
            RunRollup run = runs.get(i);
            GlobalRollup runStats = aggregate(run.classes);
            boolean isNewest = (i == 0);

            sb.append("<details class=\"run-group\"").append(isNewest ? " open" : "").append(">\n");
            sb.append("<summary class=\"run-header\">");
            sb.append("<span class=\"run-name\">").append(escapeHtml(run.folderName)).append("</span>");
            sb.append("<span class=\"run-stats\">");
            sb.append(runStats.totalClasses).append(" classes &middot; ");
            sb.append(runStats.totalLocatorsChecked).append(" checked &middot; ");
            sb.append("<span style=\"color:#e67e22\">").append(runStats.totalHealed).append(" healed</span> &middot; ");
            sb.append("<span style=\"color:#e74c3c\">").append(runStats.totalFailed).append(" failed</span> &middot; ");
            sb.append(runStats.totalSkipped).append(" skipped &middot; ");
            sb.append(ReportGenerator.formatTime(runStats.totalTimeMs));
            sb.append("</span>");
            sb.append("</summary>\n");

            sb.append("<div class=\"table-wrapper\">\n<table>\n<thead><tr>");
            sb.append("<th>Class</th><th>Timestamp</th><th>Checked</th><th>Passed</th><th>Healed</th><th>Failed</th>");
            sb.append("<th>Skipped</th><th>Heal %</th><th>Time</th><th>Tokens</th><th>Details</th>");
            sb.append("</tr></thead>\n<tbody>\n");
            sb.append(buildClassRows(run.classes));
            sb.append("</tbody>\n</table>\n</div>\n");
            sb.append("</details>\n");
        }
        return sb.toString();
    }

    private static String buildClassRows(List<ClassRollup> rollups) {
        StringBuilder sb = new StringBuilder();
        for (ClassRollup r : rollups) {
            long checked = r.passed + r.healed + r.failed;
            long healDenom = r.healed + r.failed;
            String healPct = healDenom > 0 ? pct(r.healed, healDenom) + "%" : "-";
            String cls = r.classFailed ? "failed" : "passed";
            sb.append("<tr class=\"").append(cls).append("\">");
            sb.append("<td>").append(escapeHtml(r.reportName)).append("</td>");
            sb.append("<td>").append(escapeHtml(r.displayTimestamp)).append("</td>");
            sb.append("<td>").append(checked).append("</td>");
            sb.append("<td>").append(r.passed).append("</td>");
            sb.append("<td>").append(r.healed).append("</td>");
            sb.append("<td>").append(r.failed).append("</td>");
            sb.append("<td>").append(r.skipped).append("</td>");
            sb.append("<td>").append(healPct).append("</td>");
            sb.append("<td>").append(ReportGenerator.formatTime(r.totalTimeMs)).append("</td>");
            sb.append("<td>").append(r.totalTokens).append("</td>");
            sb.append("<td><a class=\"btn-details\" href=\"")
                    .append(escapeHtml(r.relativePrefix + r.htmlFileName))
                    .append("\">View Details</a></td>");
            sb.append("</tr>\n");
        }
        return sb.toString();
    }

    private static String buildInlineDashboard(String displayTs, List<RunRollup> runs,
                                                GlobalRollup g, String runSections) {
        String latestRun = runs.get(0).folderName;
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
        sb.append("<title>AutoHeal Dashboard - ").append(displayTs).append("</title>");
        sb.append("<style>body{font-family:sans-serif;margin:20px;}table{width:100%;border-collapse:collapse;}");
        sb.append("th,td{padding:8px;border-bottom:1px solid #ccc;text-align:left;}th{background:#1a1a2e;color:#fff;}");
        sb.append("tr.failed td:nth-child(6){color:#e74c3c;font-weight:bold;}");
        sb.append(".btn-details{padding:4px 8px;background:#1a1a2e;color:#fff;text-decoration:none;border-radius:4px;}");
        sb.append(".run-group{margin-bottom:16px;}");
        sb.append(".run-header{background:#e8eaf0;padding:10px 14px;border-radius:8px;cursor:pointer;display:flex;justify-content:space-between;align-items:center;}");
        sb.append("</style></head><body>");
        sb.append("<h1>AutoHeal Dashboard</h1>");
        sb.append("<p>Generated: ").append(displayTs)
                .append(" &middot; Latest: ").append(escapeHtml(latestRun))
                .append(" &middot; Runs: ").append(runs.size())
                .append("</p>");
        sb.append("<ul>");
        sb.append("<li>Total Classes: ").append(g.totalClasses).append("</li>");
        sb.append("<li>Failed Classes: ").append(g.failedClasses).append(" (").append(pct(g.failedClasses, g.totalClasses)).append("%)</li>");
        sb.append("<li>Locators Checked: ").append(g.totalLocatorsChecked).append("</li>");
        sb.append("<li>Passed: ").append(g.totalPassed).append("</li>");
        sb.append("<li>Healed: ").append(g.totalHealed).append("</li>");
        sb.append("<li>Failed: ").append(g.totalFailed).append(" (").append(pct(g.totalFailed, g.totalLocatorsChecked)).append("%)</li>");
        sb.append("<li>Skipped: ").append(g.totalSkipped).append("</li>");
        sb.append("<li>Total Time: ").append(ReportGenerator.formatTime(g.totalTimeMs)).append("</li>");
        sb.append("<li>Tokens Used: ").append(g.totalTokens).append("</li>");
        sb.append("</ul>");
        sb.append(runSections);
        sb.append("</body></html>");
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
        String relativePrefix = "";
        long passed;
        long healed;
        long failed;
        long skipped;
        long totalTimeMs;
        long totalTokens;
        boolean classFailed;
    }

    private static final class RunRollup {
        final String folderName;
        final List<ClassRollup> classes;

        RunRollup(String folderName, List<ClassRollup> classes) {
            this.folderName = folderName;
            this.classes = classes;
        }
    }

    private static final class GlobalRollup {
        long totalClasses;
        long failedClasses;
        long totalLocatorsChecked;
        long totalPassed;
        long totalHealed;
        long totalFailed;
        long totalSkipped;
        long totalTimeMs;
        long totalTokens;
        long totalRecords;
    }
}
