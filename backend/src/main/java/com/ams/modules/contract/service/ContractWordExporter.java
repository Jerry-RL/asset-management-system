package com.ams.modules.contract.service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;

/**
 * 将填充后的合同 HTML 转为简易 Word(.docx) 字节流，用于在线预览配套导出。
 */
final class ContractWordExporter {

    private static final Pattern TAG_BR = Pattern.compile("(?i)<br\\s*/?>");
    private static final Pattern BLOCK_SPLIT = Pattern.compile("(?i)</?(?:p|h[1-6]|div|tr|li)[^>]*>");
    private static final Pattern STRIP_TAGS = Pattern.compile("<[^>]+>");
    private static final Pattern MULTI_NL = Pattern.compile("\\n{3,}");

    private ContractWordExporter() {
    }

    static byte[] toDocx(String html, String title) {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (title != null && !title.isBlank()) {
                XWPFParagraph tp = doc.createParagraph();
                tp.setAlignment(ParagraphAlignment.CENTER);
                XWPFRun tr = tp.createRun();
                tr.setBold(true);
                tr.setFontSize(16);
                tr.setFontFamily("宋体");
                tr.setText(title);
            }
            for (String line : toLines(html)) {
                String text = decode(line.trim());
                if (text.isBlank()) {
                    continue;
                }
                boolean heading = text.length() <= 40 && (text.startsWith("第") || text.contains("合同"));
                XWPFParagraph p = doc.createParagraph();
                if (heading && !text.contains("：") && !text.contains(":")) {
                    p.setAlignment(ParagraphAlignment.LEFT);
                }
                XWPFRun run = p.createRun();
                run.setFontFamily("宋体");
                run.setFontSize(heading ? 12 : 11);
                run.setBold(heading && text.length() < 20);
                run.setText(text);
            }
            doc.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("生成 Word 失败: " + e.getMessage(), e);
        }
    }

    private static List<String> toLines(String html) {
        if (html == null || html.isBlank()) {
            return List.of();
        }
        String normalized = TAG_BR.matcher(html).replaceAll("\n");
        normalized = BLOCK_SPLIT.matcher(normalized).replaceAll("\n");
        normalized = STRIP_TAGS.matcher(normalized).replaceAll("");
        normalized = MULTI_NL.matcher(normalized).replaceAll("\n\n");
        String[] parts = normalized.split("\\n");
        List<String> lines = new ArrayList<>();
        for (String part : parts) {
            String t = part.trim();
            if (!t.isEmpty()) {
                lines.add(t);
            }
        }
        return lines;
    }

    private static String decode(String s) {
        return s.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }

    static String wrapPreviewDocument(String bodyHtml, String title) {
        String safeTitle = title == null ? "合同预览" : title;
        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8"/>
                  <title>%s</title>
                  <style>
                    body{background:#e8e8e8;margin:0;padding:24px;font-family:SimSun,serif;}
                    .page{background:#fff;max-width:794px;margin:0 auto;padding:48px 56px;min-height:1000px;
                      box-shadow:0 2px 12px rgba(0,0,0,.12);}
                    .badge{position:fixed;top:12px;right:16px;background:#1677ff;color:#fff;padding:4px 10px;
                      border-radius:4px;font-size:12px;font-family:sans-serif;}
                  </style>
                </head>
                <body>
                  <div class="badge">Word 版在线预览</div>
                  <div class="page">%s</div>
                </body>
                </html>
                """.formatted(escapeAttr(safeTitle), bodyHtml == null ? "" : bodyHtml);
    }

    private static String escapeAttr(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
