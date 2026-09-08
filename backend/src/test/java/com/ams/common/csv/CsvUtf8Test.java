package com.ams.common.csv;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CsvUtf8Test {

    @Test
    void toDownloadBytes_prefixesUtf8Bom() {
        byte[] bytes = CsvUtf8.toDownloadBytes("资产编号,名称\nA001,测试房");
        assertEquals((byte) 0xEF, bytes[0]);
        assertEquals((byte) 0xBB, bytes[1]);
        assertEquals((byte) 0xBF, bytes[2]);
        String body = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        assertTrue(body.startsWith("资产编号,名称"));
        assertTrue(body.contains("测试房"));
    }

    @Test
    void toDownloadBytes_emptyStillHasBom() {
        byte[] bytes = CsvUtf8.toDownloadBytes("");
        assertArrayEquals(CsvUtf8.BOM, bytes);
    }

    @Test
    void stripBom_removesLeadingFeff() {
        assertEquals("assetNo,name", CsvUtf8.stripBom("\uFEFFassetNo,name"));
        assertEquals("assetNo,name", CsvUtf8.stripBom("assetNo,name"));
    }
}
