package com.ams.modules.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ams.modules.contract.service.ContractTemplateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContractTemplateServiceTest {

    private final ContractTemplateService service =
            new ContractTemplateService(null, new ObjectMapper());

    @Test
    void extractSlots_preservesOrderAndUniqueness() {
        String html = "编号{{contractNo}} 租户{{tenantName}} 再写{{contractNo}}";
        List<String> slots = service.extractSlots(html);
        assertEquals(List.of("contractNo", "tenantName"), slots);
    }

    @Test
    void fill_replacesSlotsAndEscapesHtml() {
        String html = "<p>{{tenantName}} 租金 {{rentAmount}}</p>";
        String out = service.fill(html, Map.of("tenantName", "甲<乙>", "rentAmount", "1000"));
        assertTrue(out.contains("甲&lt;乙&gt;"));
        assertTrue(out.contains("1000"));
        assertTrue(!out.contains("{{"));
    }
}
