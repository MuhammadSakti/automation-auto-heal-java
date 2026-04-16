package com.autoheal.fixer;

import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

public class SourceFixerTest {

    private final SourceFixer fixer = new SourceFixer();

    // ── 1. page.locator("...") direct replacement ─────────────────────────────

    @Test
    public void plainLocator_directReplace() {
        String line = "    this.btn = page.locator(\"//div[@class='old']\");";
        String result = fixer.tryReplaceLine(line,
                "Locator@//div[@class='old']",
                "//div[@class='new']");
        assertEquals(result, "    this.btn = page.locator(\"//div[@class='new']\");");
    }

    // ── 2. getByTestId ────────────────────────────────────────────────────────

    @Test
    public void getByTestId_bothTestIds_swapsValue() {
        String line = "    this.header = page.getByTestId(\"old-id\");";
        String result = fixer.tryReplaceLine(line,
                "Locator@internal:attr=[data-testid=\"old-id\"]",
                "[data-testid=\"new-id\"]");
        assertEquals(result, "    this.header = page.getByTestId(\"new-id\");");
    }

    @Test
    public void getByTestId_nonTestIdNewSelector_rewritesToLocator() {
        String line = "    this.header = page.getByTestId(\"old-id\");";
        String result = fixer.tryReplaceLine(line,
                "Locator@internal:attr=[data-testid=\"old-id\"]",
                "//h1[@class='title']");
        assertEquals(result, "    this.header = page.locator(\"//h1[@class='title']\");");
    }

    @Test
    public void getByTestId_preservesPageVariableName() {
        String line = "    this.header = pageInventory.getByTestId(\"old-id\");";
        String result = fixer.tryReplaceLine(line,
                "Locator@internal:attr=[data-testid=\"old-id\"]",
                "//h1");
        assertEquals(result, "    this.header = pageInventory.locator(\"//h1\");");
    }

    // ── 3. getByRole with .setName(...) ───────────────────────────────────────

    @Test
    public void getByRole_withSetName_rewritesToLocator() {
        String line = "    this.heading = page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName(\"Area Kos Terpopuler\"));";
        String result = fixer.tryReplaceLine(line,
                "Locator@internal:role=heading[name=\"Area Kos Terpopuler\"i]",
                "//h2[@data-qa='popular-area']");
        assertEquals(result, "    this.heading = page.locator(\"//h2[@data-qa='popular-area']\");");
    }

    @Test
    public void getByRole_withMultipleOptions_rewritesToLocator() {
        String line = "    this.link = page.getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName(\"Lihat semua\").setExact(true));";
        String result = fixer.tryReplaceLine(line,
                "Locator@internal:role=link[name=\"Lihat semua\"s]",
                ".see-all-link");
        assertEquals(result, "    this.link = page.locator(\".see-all-link\");");
    }

    // ── 4. getByText ──────────────────────────────────────────────────────────

    @Test
    public void getByText_rewritesToLocator() {
        String line = "    this.greeting = page.getByText(\"Hello\");";
        String result = fixer.tryReplaceLine(line,
                "Locator@internal:text=\"Hello\"i",
                "#greeting");
        assertEquals(result, "    this.greeting = page.locator(\"#greeting\");");
    }

    @Test
    public void getByText_withExactOption_rewritesToLocator() {
        String line = "    this.label = page.getByText(\"Submit\", new Page.GetByTextOptions().setExact(true));";
        String result = fixer.tryReplaceLine(line,
                "Locator@internal:text=\"Submit\"s",
                "button.submit");
        assertEquals(result, "    this.label = page.locator(\"button.submit\");");
    }

    // ── 5. getByLabel ─────────────────────────────────────────────────────────

    @Test
    public void getByLabel_rewritesToLocator() {
        String line = "    this.email = page.getByLabel(\"Email\");";
        String result = fixer.tryReplaceLine(line,
                "Locator@internal:label=\"Email\"i",
                "input[name='email']");
        assertEquals(result, "    this.email = page.locator(\"input[name='email']\");");
    }

    // ── 6. getByPlaceholder ───────────────────────────────────────────────────

    @Test
    public void getByPlaceholder_rewritesToLocator() {
        String line = "    this.search = page.getByPlaceholder(\"Search\");";
        String result = fixer.tryReplaceLine(line,
                "Locator@internal:attr=[placeholder=\"Search\"i]",
                "#search-input");
        assertEquals(result, "    this.search = page.locator(\"#search-input\");");
    }

    // ── 7. getByAltText ───────────────────────────────────────────────────────

    @Test
    public void getByAltText_rewritesToLocator() {
        String line = "    this.logo = page.getByAltText(\"Logo\");";
        String result = fixer.tryReplaceLine(line,
                "Locator@internal:attr=[alt=\"Logo\"i]",
                "img.logo");
        assertEquals(result, "    this.logo = page.locator(\"img.logo\");");
    }

    // ── 8. getByTitle ─────────────────────────────────────────────────────────

    @Test
    public void getByTitle_rewritesToLocator() {
        String line = "    this.tooltip = page.getByTitle(\"Tooltip\");";
        String result = fixer.tryReplaceLine(line,
                "Locator@internal:attr=[title=\"Tooltip\"i]",
                ".tooltip");
        assertEquals(result, "    this.tooltip = page.locator(\".tooltip\");");
    }

    // ── 9. Filter chains ──────────────────────────────────────────────────────

    @Test
    public void locatorFilterChain_rewritesEntireChain() {
        String line = "    this.item = page.locator(\".item\").filter(new Locator.FilterOptions().setHasText(\"Hello\"));";
        String result = fixer.tryReplaceLine(line,
                "Locator@.item >> internal:has-text=\"Hello\"i",
                ".item-hello");
        assertEquals(result, "    this.item = page.locator(\".item-hello\");");
    }

    @Test
    public void getByRoleWithTrailingFilter_rewritesWholeChain() {
        String line = "    this.row = page.getByRole(AriaRole.ROW, new Page.GetByRoleOptions().setName(\"Jakarta\")).filter(new Locator.FilterOptions().setHasText(\"Kos\"));";
        String result = fixer.tryReplaceLine(line,
                "Locator@internal:role=row[name=\"Jakarta\"i] >> internal:has-text=\"Kos\"i",
                "tr.jakarta-kos");
        assertEquals(result, "    this.row = page.locator(\"tr.jakarta-kos\");");
    }

    // ── 10. Negative cases ────────────────────────────────────────────────────

    @Test
    public void unmatchedLine_returnsNull() {
        String line = "    // unrelated comment";
        String result = fixer.tryReplaceLine(line,
                "Locator@internal:role=heading[name=\"Foo\"i]",
                "h1");
        assertNull(result);
    }

    @Test
    public void getByRoleWithDifferentName_doesNotMatch() {
        // Key value "Foo" doesn't appear inside the call's args, so leave it alone
        String line = "    this.header = page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName(\"Bar\"));";
        String result = fixer.tryReplaceLine(line,
                "Locator@internal:role=heading[name=\"Foo\"i]",
                "h1");
        assertNull(result);
    }

    // ── 11. Variable-name safety (regression for v1.3.5) ──────────────────────

    @Test
    public void getByTestId_doesNotCorruptSiblingIdentifiers() {
        // "this.header" must not be rewritten to "this.app-header"
        String line = "    this.header = page.getByTestId(\"header\");";
        String result = fixer.tryReplaceLine(line,
                "Locator@internal:attr=[data-testid=\"header\"]",
                "[data-testid=\"app-header\"]");
        assertEquals(result, "    this.header = page.getByTestId(\"app-header\");");
    }

    // ── 12. Selenium — By.<type>("...") rewriting ─────────────────────────────

    @Test
    public void seleniumById_toXpath_switchesByType() {
        String line = "    By loginBtn = By.id(\"login\");";
        String result = fixer.tryReplaceLine(line, "By.id: login", "//button[@data-qa='login']");
        assertEquals(result, "    By loginBtn = By.xpath(\"//button[@data-qa='login']\");");
    }

    @Test
    public void seleniumById_toCssSelector_switchesByType() {
        String line = "    By loginBtn = By.id(\"login\");";
        String result = fixer.tryReplaceLine(line, "By.id: login", "#login-btn");
        assertEquals(result, "    By loginBtn = By.cssSelector(\"#login-btn\");");
    }

    @Test
    public void seleniumXpath_toXpath_sameTypePreserved() {
        String line = "    By el = By.xpath(\"//div[@class='old']\");";
        String result = fixer.tryReplaceLine(line,
                "By.xpath: //div[@class='old']",
                "//div[@class='new']");
        assertEquals(result, "    By el = By.xpath(\"//div[@class='new']\");");
    }

    @Test
    public void seleniumCssSelector_toCssSelector_sameTypePreserved() {
        String line = "    By el = By.cssSelector(\".old-class\");";
        String result = fixer.tryReplaceLine(line,
                "By.cssSelector: .old-class",
                ".new-class");
        assertEquals(result, "    By el = By.cssSelector(\".new-class\");");
    }

    @Test
    public void seleniumName_toCss_switchesByType() {
        String line = "    By field = By.name(\"username\");";
        String result = fixer.tryReplaceLine(line, "By.name: username", "input[name='user']");
        assertEquals(result, "    By field = By.cssSelector(\"input[name='user']\");");
    }

    @Test
    public void seleniumLinkText_toXpath_switchesByType() {
        String line = "    By link = By.linkText(\"Click me\");";
        String result = fixer.tryReplaceLine(line, "By.linkText: Click me",
                "//a[contains(text(),'Click me')]");
        assertEquals(result, "    By link = By.xpath(\"//a[contains(text(),'Click me')]\");");
    }

    @Test
    public void seleniumClassName_toCss_switchesByType() {
        String line = "    By btn = By.className(\"primary-btn\");";
        String result = fixer.tryReplaceLine(line, "By.className: primary-btn", ".btn.btn-primary");
        assertEquals(result, "    By btn = By.cssSelector(\".btn.btn-primary\");");
    }

    @Test
    public void seleniumDoesNotCorruptIdenticalFieldNames() {
        // Field name "login" matches the id value "login" — the old SourceFixer would
        // replace BOTH. The new rewrite targets only the call, leaving the field alone.
        String line = "    private final By login = By.id(\"login\");";
        String result = fixer.tryReplaceLine(line, "By.id: login", "#login-v2");
        assertEquals(result, "    private final By login = By.cssSelector(\"#login-v2\");");
    }

    @Test
    public void seleniumUnmatchedCall_returnsNull() {
        // Source references a different helper method — we can't safely rewrite it
        String line = "    By btn = findById(\"login\");";
        String result = fixer.tryReplaceLine(line, "By.id: login", "#login-v2");
        assertNull(result);
    }
}
