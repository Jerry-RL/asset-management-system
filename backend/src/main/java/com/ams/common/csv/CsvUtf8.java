package com.ams.common.csv;

import java.nio.charset.StandardCharsets;

/**
 * Excel（Windows）直接打开 UTF-8 CSV 时需带 BOM，否则中文会乱码。
 */
public final class CsvUtf8 {

    /** UTF-8 BOM：EF BB BF */
    public static final byte[] BOM = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private CsvUtf8() {}

    /** 将 CSV 文本编码为带 BOM 的 UTF-8 字节，供浏览器下载 / Excel 打开。 */
    public static byte[] toDownloadBytes(String csv) {
        byte[] body = (csv == null ? "" : csv).getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[BOM.length + body.length];
        System.arraycopy(BOM, 0, out, 0, BOM.length);
        System.arraycopy(body, 0, out, BOM.length, body.length);
        return out;
    }

    /** 去掉行首 BOM（导入时兼容 Excel 另存为 UTF-8）。 */
    public static String stripBom(String line) {
        if (line == null || line.isEmpty()) {
            return line;
        }
        if (line.charAt(0) == '\uFEFF') {
            return line.substring(1);
        }
        return line;
    }
}
