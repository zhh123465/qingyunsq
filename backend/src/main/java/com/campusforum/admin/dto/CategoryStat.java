package com.campusforum.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Dashboard 空间分类分布的单条 [category, count] 数据。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CategoryStat {
    private String category;
    private Long count;
}
