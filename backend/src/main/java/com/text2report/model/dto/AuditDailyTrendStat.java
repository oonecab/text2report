package com.text2report.model.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * Raw daily audit trend row.
 */
@Data
public class AuditDailyTrendStat {

    private LocalDate date;
    private Long count;
    private Long avgMs;
}
