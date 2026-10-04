package com.text2report.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Tree node DTO for org tree rendering
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrgTreeNode {

    private Integer id;

    private String name;

    private String code;

    private Integer level;

    private Integer parentId;

    private Boolean isActive;

    private List<OrgTreeNode> children;
}
