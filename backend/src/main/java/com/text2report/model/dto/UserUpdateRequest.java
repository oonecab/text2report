package com.text2report.model.dto;

import lombok.Data;

@Data
public class UserUpdateRequest {
    private String displayName;
    private String role;
    private String email;
    private String phone;
    private Boolean isActive;
}
