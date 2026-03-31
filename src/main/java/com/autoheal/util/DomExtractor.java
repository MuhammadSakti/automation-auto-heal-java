package com.autoheal.util;

public class DomExtractor {

    private static final int MAX_DOM_LENGTH = 50000;

    /**
     * Extract clean DOM from a Playwright Page.
     */
    public static String fromPlaywright(com.microsoft.playwright.Page page) {
        String dom = (String) page.evaluate(
                "() => {\n" +
                "  function clean(el) {\n" +
                "    const clone = el.cloneNode(false);\n" +
                "    const skip = ['SCRIPT', 'STYLE', 'NOSCRIPT', 'SVG', 'PATH', 'META', 'LINK'];\n" +
                "    for (const child of el.childNodes) {\n" +
                "      if (child.nodeType === 3) {\n" +
                "        const text = child.textContent.trim();\n" +
                "        if (text) clone.appendChild(document.createTextNode(text));\n" +
                "      } else if (child.nodeType === 1 && !skip.includes(child.tagName)) {\n" +
                "        clone.appendChild(clean(child));\n" +
                "      }\n" +
                "    }\n" +
                "    return clone;\n" +
                "  }\n" +
                "  return clean(document.body).outerHTML;\n" +
                "}"
        );
        return truncate(dom);
    }

    /**
     * Extract clean DOM from a Selenium WebDriver.
     */
    public static String fromSelenium(org.openqa.selenium.WebDriver driver) {
        org.openqa.selenium.JavascriptExecutor js = (org.openqa.selenium.JavascriptExecutor) driver;
        String dom = (String) js.executeScript(
                "function clean(el) {" +
                "  var clone = el.cloneNode(false);" +
                "  var skip = ['SCRIPT', 'STYLE', 'NOSCRIPT', 'SVG', 'PATH', 'META', 'LINK'];" +
                "  for (var i = 0; i < el.childNodes.length; i++) {" +
                "    var child = el.childNodes[i];" +
                "    if (child.nodeType === 3) {" +
                "      var text = child.textContent.trim();" +
                "      if (text) clone.appendChild(document.createTextNode(text));" +
                "    } else if (child.nodeType === 1 && skip.indexOf(child.tagName) === -1) {" +
                "      clone.appendChild(clean(child));" +
                "    }" +
                "  }" +
                "  return clone;" +
                "}" +
                "return clean(document.body).outerHTML;"
        );
        return truncate(dom);
    }

    private static String truncate(String dom) {
        if (dom != null && dom.length() > MAX_DOM_LENGTH) {
            return dom.substring(0, MAX_DOM_LENGTH) + "\n<!-- DOM truncated -->";
        }
        return dom != null ? dom : "";
    }
}
