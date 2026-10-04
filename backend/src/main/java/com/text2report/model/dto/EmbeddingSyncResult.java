package com.text2report.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Embedding sync result DTO.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmbeddingSyncResult {
    private int examples;
    private int glossary;
    private int tables;
}
