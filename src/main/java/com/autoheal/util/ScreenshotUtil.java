package com.autoheal.util;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

public class ScreenshotUtil {

    private static final int MAX_WIDTH = 1280;
    private static final float JPEG_QUALITY = 0.7f;

    /**
     * Resize screenshot bytes (PNG) to max width and re-encode as JPEG.
     * Returns base64-encoded JPEG. If processing fails, falls back to
     * base64 of the original bytes.
     */
    public static String compressToBase64(byte[] pngBytes) {
        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(pngBytes));
            if (original == null) {
                return Base64.getEncoder().encodeToString(pngBytes);
            }

            BufferedImage resized = resize(original);
            byte[] jpegBytes = toJpeg(resized);
            return Base64.getEncoder().encodeToString(jpegBytes);
        } catch (IOException e) {
            return Base64.getEncoder().encodeToString(pngBytes);
        }
    }

    /**
     * Resize screenshot bytes (PNG) to max width and re-encode as JPEG with custom quality.
     * Returns base64-encoded JPEG. If processing fails, falls back to
     * base64 of the original bytes.
     *
     * @param imageQuality JPEG quality percentage (1-100)
     */
    public static String compressToBase64(byte[] pngBytes, int imageQuality) {
        if (imageQuality < 1 || imageQuality > 100) {
            throw new IllegalArgumentException("imageQuality must be between 1 and 100, got: " + imageQuality);
        }
        float quality = imageQuality / 100f;
        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(pngBytes));
            if (original == null) {
                return Base64.getEncoder().encodeToString(pngBytes);
            }

            BufferedImage resized = resize(original);
            byte[] jpegBytes = toJpeg(resized, quality);
            return Base64.getEncoder().encodeToString(jpegBytes);
        } catch (IOException e) {
            return Base64.getEncoder().encodeToString(pngBytes);
        }
    }

    /**
     * Compress a base64-encoded PNG screenshot. Returns base64-encoded JPEG.
     * Falls back to the original string if processing fails.
     */
    public static String compressBase64(String base64Png) {
        try {
            byte[] pngBytes = Base64.getDecoder().decode(base64Png);
            return compressToBase64(pngBytes);
        } catch (IllegalArgumentException e) {
            return base64Png;
        }
    }

    /**
     * Compress a base64-encoded PNG screenshot with custom quality.
     * Returns base64-encoded JPEG. Falls back to the original string if processing fails.
     *
     * @param imageQuality JPEG quality percentage (1-100)
     */
    public static String compressBase64(String base64Png, int imageQuality) {
        try {
            byte[] pngBytes = Base64.getDecoder().decode(base64Png);
            return compressToBase64(pngBytes, imageQuality);
        } catch (IllegalArgumentException e) {
            return base64Png;
        }
    }

    /**
     * Returns "image/jpeg" when compression is used, for setting correct media types.
     */
    public static String getCompressedMediaType() {
        return "image/jpeg";
    }

    private static BufferedImage resize(BufferedImage original) {
        if (original.getWidth() <= MAX_WIDTH) {
            return original;
        }
        double scale = (double) MAX_WIDTH / original.getWidth();
        int newHeight = (int) (original.getHeight() * scale);
        BufferedImage resized = new BufferedImage(MAX_WIDTH, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(original, 0, 0, MAX_WIDTH, newHeight, null);
        g.dispose();
        return resized;
    }

    private static byte[] toJpeg(BufferedImage image) throws IOException {
        return toJpeg(image, JPEG_QUALITY);
    }

    private static byte[] toJpeg(BufferedImage image, float quality) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        var writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG writer available");
        }
        var writer = writers.next();
        var param = writer.getDefaultWriteParam();
        param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);

        // Convert to RGB if needed (JPEG doesn't support alpha)
        BufferedImage rgb = image;
        if (image.getType() != BufferedImage.TYPE_INT_RGB) {
            rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.drawImage(image, 0, 0, null);
            g.dispose();
        }

        writer.setOutput(ImageIO.createImageOutputStream(baos));
        writer.write(null, new javax.imageio.IIOImage(rgb, null, null), param);
        writer.dispose();
        return baos.toByteArray();
    }
}
