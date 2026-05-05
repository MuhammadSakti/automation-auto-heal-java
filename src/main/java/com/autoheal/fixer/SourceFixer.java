package com.autoheal.fixer;

import com.autoheal.finder.HealResult;
import com.autoheal.reporter.HealRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SourceFixer {

    public static class FixResult {
        public final String sourceFile;
        public final int sourceLine;
        public final String oldSelector;
        public final String newSelector;
        public final boolean applied;
        public final String message;

        public FixResult(String sourceFile, int sourceLine, String oldSelector,
                         String newSelector, boolean applied, String message) {
            this.sourceFile = sourceFile;
            this.sourceLine = sourceLine;
            this.oldSelector = oldSelector;
            this.newSelector = newSelector;
            this.applied = applied;
            this.message = message;
        }
    }

    public List<FixResult> applyFixes(List<HealRecord> records) {
        List<FixResult> results = new ArrayList<>();

        for (HealRecord record : records) {
            if (record.getStrategy() != HealResult.Strategy.DOM_HEALED) continue;
            if (record.getStatus() != HealRecord.Status.SUCCESS) continue;
            if (record.getSourceFile() == null || record.getSourceLine() < 1) continue;

            FixResult result = fixRecord(record);
            results.add(result);
        }

        return results;
    }

    private FixResult fixRecord(HealRecord record) {
        String sourceFile = record.getSourceFile();
        int sourceLine = record.getSourceLine();
        String originalSelector = record.getOriginalSelector();
        String newSelector = record.getActualSelector();
        Path path = Paths.get(sourceFile);

        if (!Files.exists(path)) {
            return skip(sourceFile, sourceLine, originalSelector, newSelector,
                    "Source file not found: " + sourceFile);
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return skip(sourceFile, sourceLine, originalSelector, newSelector,
                    "IO error: " + e.getMessage());
        }

        int lineIndex = sourceLine - 1;
        if (lineIndex < 0 || lineIndex >= lines.size()) {
            return skip(sourceFile, sourceLine, originalSelector, newSelector,
                    "Line number out of range: " + sourceLine);
        }

        String line = lines.get(lineIndex);
        String newLine = tryReplaceLine(line, originalSelector, newSelector);

        if (newLine == null) {
            return skip(sourceFile, sourceLine, originalSelector, newSelector,
                    "Could not find original selector in source line");
        }

        try {
            lines.set(lineIndex, newLine);
            Files.write(path, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return skip(sourceFile, sourceLine, originalSelector, newSelector,
                    "IO error writing file: " + e.getMessage());
        }

        return new FixResult(sourceFile, sourceLine, originalSelector, newSelector, true,
                "Fixed at line " + sourceLine);
    }

    // Exposed for unit testing
    String tryReplaceLine(String line, String originalSelector, String newSelector) {
        // 0. Selenium By.<type>: value format — precise rewrite that preserves the By call
        //    and switches the By type when the healed selector's shape requires it.
        SeleniumBy by = extractSeleniumBy(originalSelector);
        if (by != null) {
            return rewriteSeleniumByCall(line, by, newSelector);
        }

        // 1. Direct replacement (raw selector appears as-is, e.g. page.locator("...") style)
        String oldSelector = extractSelectorFromOriginal(originalSelector);
        if (oldSelector != null && line.contains(oldSelector)) {
            // Try converting Playwright role=/text= selectors to idiomatic getByX calls
            String idiomaticRewrite = tryRewriteToPlaywrightGetBy(line, oldSelector, newSelector);
            if (idiomaticRewrite != null) return idiomaticRewrite;
            return line.replace(oldSelector, newSelector);
        }

        // 2. Identify Playwright getByX call from the internal format
        LocatorCall call = extractLocatorCall(originalSelector);
        if (call != null) {
            String result = rewritePlaywrightBuilder(line, call, newSelector);
            if (result != null) return result;
        }

        // 3. Plain .locator(...).filter(...) chain (no getBy involved)
        if (originalSelector != null && originalSelector.contains(">>")) {
            return rewriteFilterChain(line, newSelector);
        }

        return null;
    }

    /**
     * Tries a testid → testid value-swap first (keeps the {@code byTestId()} call intact),
     * then falls back to a full call rewrite.
     */
    private String rewritePlaywrightBuilder(String line, LocatorCall call, String newSelector) {
        String preserved = tryPreserveTestId(line, call, newSelector);
        if (preserved != null) return preserved;
        return rewriteGetByCall(line, call, newSelector);
    }

    private String tryPreserveTestId(String line, LocatorCall call, String newSelector) {
        if (!call.method.equals("getByTestId")) return null;
        String newTestId = extractTestId(newSelector);
        if (newTestId == null) return null;
        return replaceTestIdInCall(line, call.keyValue, newTestId);
    }

    private FixResult skip(String sourceFile, int sourceLine,
                           String oldSelector, String newSelector, String message) {
        return new FixResult(sourceFile, sourceLine, oldSelector, newSelector, false, message);
    }

    /**
     * Extracts the raw selector string from locator toString representations.
     * E.g., "Locator@//div[@class='foo']" -> "//div[@class='foo']"
     */
    private String extractSelectorFromOriginal(String original) {
        if (original == null) return null;
        // Playwright Locator.toString() format: "Locator@selector"
        int atIndex = original.indexOf('@');
        if (atIndex >= 0) {
            return original.substring(atIndex + 1);
        }
        // Selenium By.toString() format: "By.xpath: //div" or "By.cssSelector: .foo"
        int colonIndex = original.indexOf(':');
        if (colonIndex >= 0) {
            return original.substring(colonIndex + 1).trim();
        }
        return original;
    }

    // Playwright internal formats:
    //   getByTestId   → internal:testid=[data-testid="X"s]  or  internal:attr=[data-testid="X"]
    //   getByRole     → internal:role=heading[name="X"i]
    //   getByText     → internal:text="X"i
    //   getByLabel    → internal:label="X"i
    //   getByPlaceholder / getByAltText / getByTitle → internal:attr=[placeholder|alt|title="X"i]
    private static final Pattern TEST_ID_VALUE_PATTERN = Pattern.compile(
            "data-testid=[\"']([^\"']+)[\"']");
    private static final Pattern ROLE_NAME_PATTERN = Pattern.compile(
            "internal:role=\\w+\\[name=[\"']([^\"']+)[\"']");
    private static final Pattern TEXT_PATTERN = Pattern.compile(
            "internal:text=[\"']([^\"']+)[\"']");
    private static final Pattern LABEL_PATTERN = Pattern.compile(
            "internal:label=[\"']([^\"']+)[\"']");
    private static final Pattern ATTR_PATTERN = Pattern.compile(
            "internal:(?:attr|testid)=\\[([\\w-]+)=[\"']([^\"']+)[\"']");

    private static class LocatorCall {
        final String method;
        final String keyValue;
        LocatorCall(String method, String keyValue) {
            this.method = method;
            this.keyValue = keyValue;
        }
    }

    /**
     * Extracts the test-id value from a Playwright internal or CSS data-testid format.
     * E.g., "internal:attr=[data-testid=\"header\"]" -> "header"
     */
    private String extractTestId(String selector) {
        if (selector == null) return null;
        Matcher m = TEST_ID_VALUE_PATTERN.matcher(selector);
        return m.find() ? m.group(1) : null;
    }

    /** Identifies which Playwright getByX builder the internal selector came from. */
    private LocatorCall extractLocatorCall(String selector) {
        if (selector == null) return null;
        Matcher m;
        if ((m = ROLE_NAME_PATTERN.matcher(selector)).find()) {
            return new LocatorCall("getByRole", m.group(1));
        }
        if ((m = TEXT_PATTERN.matcher(selector)).find()) {
            return new LocatorCall("getByText", m.group(1));
        }
        if ((m = LABEL_PATTERN.matcher(selector)).find()) {
            return new LocatorCall("getByLabel", m.group(1));
        }
        if ((m = ATTR_PATTERN.matcher(selector)).find()) {
            String attr = m.group(1);
            String value = m.group(2);
            switch (attr) {
                case "data-testid": return new LocatorCall("getByTestId", value);
                case "placeholder": return new LocatorCall("getByPlaceholder", value);
                case "alt":         return new LocatorCall("getByAltText", value);
                case "title":       return new LocatorCall("getByTitle", value);
                default: return null;
            }
        }
        return null;
    }

    /**
     * Replaces the test-id value inside a byTestId("old")/getByTestId("old") call,
     * only touching the string literal — not variable names or other text on the line.
     */
    private String replaceTestIdInCall(String line, String oldTestId, String newTestId) {
        Pattern p = Pattern.compile("((?:get)?[Bb]yTestId\\s*\\(\\s*[\"'])" +
                Pattern.quote(oldTestId) + "([\"']\\s*\\))");
        Matcher m = p.matcher(line);
        if (m.find()) {
            return line.substring(0, m.start()) +
                    m.group(1) + newTestId + m.group(2) +
                    line.substring(m.end());
        }
        return null;
    }

    /**
     * Rewrites {@code obj.getByX(args)}  — possibly followed by {@code .filter(...)} chains —
     * to {@code obj.locator("newSelector")}, preserving the object variable name.
     * The call is identified by matching the method name AND the key value appearing
     * (quoted) inside the call's argument list.
     */
    private String rewriteGetByCall(String line, LocatorCall call, String newSelector) {
        String methodAlt = call.method.equals("getByTestId")
                ? "(?:get)?[Bb]yTestId"
                : Pattern.quote(call.method);
        Pattern p = Pattern.compile("(\\w+)\\s*\\.\\s*" + methodAlt + "\\s*\\(");
        Matcher m = p.matcher(line);
        while (m.find()) {
            int openParen = m.end() - 1;
            int closeParen = findMatchingParen(line, openParen);
            if (closeParen < 0) continue;

            String args = line.substring(openParen + 1, closeParen);
            if (!containsQuoted(args, call.keyValue)) continue;

            int end = consumeFilterChain(line, closeParen + 1);
            String objName = m.group(1);
            return line.substring(0, m.start()) +
                    objName + ".locator(\"" + escapeForString(newSelector) + "\")" +
                    line.substring(end);
        }

        // Fallback: standalone byTestId("...") / getByTestId("...") with no object prefix
        if (call.method.equals("getByTestId")) {
            Pattern p2 = Pattern.compile("(?:get)?[Bb]yTestId\\s*\\(\\s*[\"']" +
                    Pattern.quote(call.keyValue) + "[\"']\\s*\\)");
            Matcher m2 = p2.matcher(line);
            if (m2.find()) {
                return line.substring(0, m2.start()) +
                        "locator(\"" + escapeForString(newSelector) + "\")" +
                        line.substring(m2.end());
            }
        }
        return null;
    }

    /**
     * Rewrites {@code obj.locator(...).filter(...)} chains to {@code obj.locator("newSelector")}.
     * Only matches when at least one .filter(...) is chained — otherwise the plain
     * locator() case is already handled by direct string replacement.
     */
    private String rewriteFilterChain(String line, String newSelector) {
        Pattern p = Pattern.compile("(\\w+)\\s*\\.\\s*locator\\s*\\(");
        Matcher m = p.matcher(line);
        while (m.find()) {
            int openParen = m.end() - 1;
            int closeParen = findMatchingParen(line, openParen);
            if (closeParen < 0) continue;

            int end = consumeFilterChain(line, closeParen + 1);
            if (end == closeParen + 1) continue; // no trailing .filter(...)

            String objName = m.group(1);
            return line.substring(0, m.start()) +
                    objName + ".locator(\"" + escapeForString(newSelector) + "\")" +
                    line.substring(end);
        }
        return null;
    }

    /**
     * If the substring starting at {@code pos} is one or more chained {@code .filter(...)}
     * calls (with optional whitespace between), returns the position after the last one.
     * Otherwise returns {@code pos} unchanged.
     */
    private int consumeFilterChain(String line, int pos) {
        while (true) {
            int next = pos;
            while (next < line.length() && Character.isWhitespace(line.charAt(next))) next++;
            if (next + 7 > line.length() || !line.startsWith(".filter", next)) return pos;
            int filterOpen = line.indexOf('(', next);
            if (filterOpen < 0) return pos;
            int filterClose = findMatchingParen(line, filterOpen);
            if (filterClose < 0) return pos;
            pos = filterClose + 1;
        }
    }

    /**
     * Finds the index of the close paren matching the open paren at {@code openIdx}.
     * Skips quoted strings so parens inside string literals don't affect depth.
     */
    private int findMatchingParen(String s, int openIdx) {
        int depth = 1;
        boolean inString = false;
        char stringChar = 0;
        for (int i = openIdx + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (c == '\\' && i + 1 < s.length()) { i++; continue; }
                if (c == stringChar) inString = false;
                continue;
            }
            if (c == '"' || c == '\'') { inString = true; stringChar = c; continue; }
            if (c == '(') { depth++; continue; }
            if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }

    private boolean containsQuoted(String haystack, String value) {
        return haystack.contains("\"" + value + "\"") || haystack.contains("'" + value + "'");
    }

    private String escapeForString(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ── Playwright role=/text= → getByRole/getByText rewriting ──────────────

    private static final Pattern ROLE_SELECTOR = Pattern.compile(
            "^role=(\\w+)(?:\\[name=[\"']([^\"']*)[\"'](?:i)?\\])?$");
    private static final Pattern TEXT_SELECTOR = Pattern.compile(
            "^text=[\"']?([^\"']+)[\"']?$");

    private static final Map<String, String> ARIA_ROLE_MAP = Map.ofEntries(
            Map.entry("button", "BUTTON"), Map.entry("heading", "HEADING"),
            Map.entry("link", "LINK"), Map.entry("checkbox", "CHECKBOX"),
            Map.entry("textbox", "TEXTBOX"), Map.entry("combobox", "COMBOBOX"),
            Map.entry("img", "IMG"), Map.entry("list", "LIST"),
            Map.entry("listitem", "LISTITEM"), Map.entry("navigation", "NAVIGATION"),
            Map.entry("dialog", "DIALOG"), Map.entry("tab", "TAB"),
            Map.entry("tabpanel", "TABPANEL"), Map.entry("radio", "RADIO"),
            Map.entry("menuitem", "MENUITEM"), Map.entry("menu", "MENU"),
            Map.entry("cell", "CELL"), Map.entry("row", "ROW"),
            Map.entry("table", "TABLE"), Map.entry("alert", "ALERT"),
            Map.entry("banner", "BANNER"), Map.entry("main", "MAIN"),
            Map.entry("region", "REGION"), Map.entry("search", "SEARCH"),
            Map.entry("switch", "SWITCH"), Map.entry("slider", "SLIDER"),
            Map.entry("spinbutton", "SPINBUTTON"), Map.entry("progressbar", "PROGRESSBAR"),
            Map.entry("separator", "SEPARATOR"), Map.entry("toolbar", "TOOLBAR"),
            Map.entry("tree", "TREE"), Map.entry("treeitem", "TREEITEM"),
            Map.entry("grid", "GRID"), Map.entry("gridcell", "GRIDCELL"),
            Map.entry("paragraph", "PARAGRAPH"), Map.entry("contentinfo", "CONTENTINFO"),
            Map.entry("complementary", "COMPLEMENTARY"), Map.entry("form", "FORM"),
            Map.entry("article", "ARTICLE"), Map.entry("group", "GROUP"),
            Map.entry("status", "STATUS"), Map.entry("tooltip", "TOOLTIP"),
            Map.entry("option", "OPTION")
    );

    /**
     * If the new selector is a Playwright {@code role=} or {@code text=} selector,
     * rewrites the entire {@code obj.locator("old")} call to an idiomatic
     * {@code obj.getByRole(AriaRole.X, ...)} or {@code obj.getByText("...")} call.
     */
    private String tryRewriteToPlaywrightGetBy(String line, String oldSelector, String newSelector) {
        String getByCall = toGetByCall(newSelector);
        if (getByCall == null) return null;

        // Match obj.locator("oldSelector") — possibly with xpath= or css= prefix
        Pattern p = Pattern.compile("(\\w+)\\s*\\.\\s*locator\\s*\\(\\s*[\"'](?:xpath=|css=)?" +
                Pattern.quote(oldSelector) + "[\"']\\s*\\)");
        Matcher m = p.matcher(line);
        if (m.find()) {
            String objName = m.group(1);
            int end = consumeFilterChain(line, m.end());
            return line.substring(0, m.start()) + objName + "." + getByCall + line.substring(end);
        }

        // Also match getByX(...) calls that contain the old selector value
        // (for cases where the original was already a getBy call)
        Pattern p2 = Pattern.compile("(\\w+)\\s*\\.\\s*(?:getBy\\w+|locator)\\s*\\(");
        Matcher m2 = p2.matcher(line);
        while (m2.find()) {
            int openParen = m2.end() - 1;
            int closeParen = findMatchingParen(line, openParen);
            if (closeParen < 0) continue;
            String args = line.substring(openParen + 1, closeParen);
            if (containsQuoted(args, oldSelector)) {
                String objName = m2.group(1);
                int end = consumeFilterChain(line, closeParen + 1);
                return line.substring(0, m2.start()) + objName + "." + getByCall + line.substring(end);
            }
        }

        return null;
    }

    /**
     * Converts a Playwright selector engine string to an idiomatic Java getBy call.
     * E.g. {@code role=button[name="Submit"]} → {@code getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Submit"))}
     * E.g. {@code text=Hello} → {@code getByText("Hello")}
     */
    private String toGetByCall(String selector) {
        if (selector == null) return null;
        Matcher roleMatcher = ROLE_SELECTOR.matcher(selector.trim());
        if (roleMatcher.matches()) {
            String role = roleMatcher.group(1).toLowerCase();
            String name = roleMatcher.group(2);
            String ariaEnum = ARIA_ROLE_MAP.get(role);
            if (ariaEnum == null) return null;
            if (name != null && !name.isEmpty()) {
                return "getByRole(AriaRole." + ariaEnum +
                        ", new Page.GetByRoleOptions().setName(\"" + escapeForString(name) + "\"))";
            }
            return "getByRole(AriaRole." + ariaEnum + ")";
        }
        Matcher textMatcher = TEXT_SELECTOR.matcher(selector.trim());
        if (textMatcher.matches()) {
            String text = textMatcher.group(1);
            return "getByText(\"" + escapeForString(text) + "\")";
        }
        return null;
    }

    // ── Selenium By.<type>: value handling ──────────────────────────────────

    private static final Pattern SELENIUM_BY_TOSTRING = Pattern.compile(
            "^By\\.(\\w+)\\s*:\\s*(.*)$");

    private static class SeleniumBy {
        final String type;   // id / name / xpath / cssSelector / className / tagName / linkText / partialLinkText
        final String value;
        SeleniumBy(String type, String value) { this.type = type; this.value = value; }
    }

    /** Parses {@code "By.xpath: //div"} / {@code "By.id: foo"} etc. */
    private SeleniumBy extractSeleniumBy(String original) {
        if (original == null) return null;
        Matcher m = SELENIUM_BY_TOSTRING.matcher(original.trim());
        return m.matches() ? new SeleniumBy(m.group(1), m.group(2)) : null;
    }

    /**
     * Rewrites a Selenium {@code By.<type>("oldValue")} call to {@code By.<newType>("newSelector")}.
     * The new By type is inferred from the healed selector's shape (xpath vs. cssSelector),
     * so an {@code id}/{@code name}/{@code linkText} locator can be swapped to an xpath/CSS one
     * without leaving a broken call behind.
     */
    private String rewriteSeleniumByCall(String line, SeleniumBy by, String newSelector) {
        Pattern p = Pattern.compile("By\\s*\\.\\s*" + Pattern.quote(by.type) +
                "\\s*\\(\\s*[\"']" + Pattern.quote(by.value) + "[\"']\\s*\\)");
        Matcher m = p.matcher(line);
        if (m.find()) {
            String newType = detectSeleniumByType(newSelector);
            String newCall = "By." + newType + "(\"" + escapeForString(newSelector) + "\")";
            return line.substring(0, m.start()) + newCall + line.substring(m.end());
        }
        return null;
    }

    /**
     * Heuristic: if the healed selector looks like an XPath, use {@code By.xpath};
     * otherwise default to {@code By.cssSelector} (accepts CSS, IDs via {@code #}, etc.).
     */
    private String detectSeleniumByType(String selector) {
        String t = selector.trim();
        if (t.startsWith("//") || t.startsWith("./") || t.startsWith(".//")
                || t.startsWith("(/") || t.startsWith("(./") || t.startsWith("(.//")) {
            return "xpath";
        }
        return "cssSelector";
    }
}
