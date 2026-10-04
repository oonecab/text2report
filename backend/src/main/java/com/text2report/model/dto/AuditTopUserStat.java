package com.text2report.model.dto;

import lombok.Data;

/**
 * Raw top-user stat from audit aggregation query.
 */
@Data
public class AuditTopUserStat {

    private Integer userId;
    private Long count;
}
