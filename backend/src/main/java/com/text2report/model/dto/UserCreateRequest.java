package com.text2report.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UserCreateRequest {
    @NotBlank
    private String username;
    @NotBlank
    private String password;
    private String displayName;
    private String role;
    private String email;
    private String phone;
}
