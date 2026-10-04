package com.text2report.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrgSyncResult {
    private int inserted;
    private int updated;
    private int deactivated;
    private int total;
}
