package com.autoheal.util;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Resolves the source file and line number where a locator field is declared,
 * using reflection to match the locator reference to a field in the page object hierarchy.
 */
public class LocatorSourceResolver {

    public record SourceInfo(String filePath, int lineNumber, String fieldName) {}

    /**
     * Resolves source info from the call stack by finding the first caller
     * outside the com.autoheal package. Works for raw locator usage without
     * a page object.
     *
     * @return source info, or null if unable to resolve
     */
    public static SourceInfo resolveFromStack() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement frame : stack) {
            String className = frame.getClassName();
            if (className.startsWith("com.autoheal.") || className.startsWith("java.lang.")) continue;
            String filePath = findSourceFile(className);
            int lineNumber = frame.getLineNumber();
            if (filePath != null && lineNumber > 0) {
                return new SourceInfo(filePath, lineNumber, null);
            }
        }
        return null;
    }

    private static String findSourceFile(String fullClassName) {
        // Handle inner classes: use the outermost class for file lookup
        String outerClass = fullClassName.contains("$")
                ? fullClassName.substring(0, fullClassName.indexOf('$'))
                : fullClassName;
        String relativePath = outerClass.replace('.', '/') + ".java";
        String userDir = System.getProperty("user.dir");

        String[] sourceRoots = {
                "src/test/java/",
                "src/main/java/",
                "src/"
        };

        for (String root : sourceRoots) {
            File file = new File(userDir, root + relativePath);
            if (file.exists()) {
                return file.getAbsolutePath();
            }
        }
        return null;
    }

    /**
     * Finds which field in the page object (or its superclasses) holds the given locator reference,
     * then resolves the source file path and line number where that field is assigned.
     *
     * @param locator    the locator reference to match
     * @param pageObject the page object instance (walks the full class hierarchy)
     * @return source info, or null if the locator doesn't match any field
     */
    public static SourceInfo resolve(Object locator, Object pageObject) {
        if (locator == null || pageObject == null) return null;

        Class<?> clazz = pageObject.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                field.setAccessible(true);
                try {
                    Object value = field.get(pageObject);
                    if (value == locator) {
                        String fieldName = field.getName();
                        Class<?> declaringClass = field.getDeclaringClass();

                        String filePath = findSourceFile(declaringClass);
                        int lineNumber = filePath != null ? findFieldAssignmentLine(filePath, fieldName) : -1;

                        return new SourceInfo(
                                filePath != null ? filePath : declaringClass.getSimpleName() + ".java",
                                lineNumber,
                                fieldName
                        );
                    }
                } catch (IllegalAccessException ignored) {
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    private static String findSourceFile(Class<?> clazz) {
        return findSourceFile(clazz.getName());
    }

    /**
     * Scans a source file for the line where a field is assigned (e.g., "this.fieldName = ...").
     * Returns 1-based line number, or -1 if not found.
     */
    private static int findFieldAssignmentLine(String filePath, String fieldName) {
        try {
            List<String> lines = Files.readAllLines(Path.of(filePath));
            String pattern1 = "this." + fieldName + " =";
            String pattern2 = "this." + fieldName + "=";

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.contains(pattern1) || line.contains(pattern2)) {
                    return i + 1;
                }
            }
        } catch (IOException ignored) {
        }
        return -1;
    }
}
