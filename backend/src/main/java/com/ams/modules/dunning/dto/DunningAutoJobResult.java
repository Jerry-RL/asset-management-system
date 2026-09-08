package com.ams.modules.dunning.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** 自动化催缴扫描结果（FR-DUN-ESC-*）。 */
@Data
public class DunningAutoJobResult {

    private int scanned;
    private int upgraded;
    private int preDueReminded;
    private int tasksCreated;
    private int smsSent;
    private List<String> highlights = new ArrayList<>();
}
