package com.text2report.model.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class UserDTO {
    private Integer id;
    private String username;
    private String displayName;
    private String role;
    private String email;
    private String phone;
    private Boolean isActive;
    private LocalDateTime lastLoginAt;
    private List<UserOrgGrantVO> orgGrants;
}
