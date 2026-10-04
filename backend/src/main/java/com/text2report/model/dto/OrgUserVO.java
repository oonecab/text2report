package com.text2report.model.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OrgUserVO {
    private Integer userId;
    private String username;
    private String displayName;
    private String role;
    private String scope;
    private Boolean isPrimary;
    private LocalDateTime grantedAt;
}
