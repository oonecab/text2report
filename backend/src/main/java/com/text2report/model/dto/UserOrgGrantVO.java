package com.text2report.model.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserOrgGrantVO {
    private Integer id;
    private Integer orgId;
    private String orgName;
    private String orgPath;
    private String scope;
    private Boolean isPrimary;
    private String grantedBy;
    private LocalDateTime grantedAt;
}
