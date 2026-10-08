package com.campusforum.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Dashboard 7 天新增趋势的单日数据点。
 * date：YYYY-MM-DD，缺失日期 service 会补 0 填齐 7 天。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DailyStat {
    private LocalDate date;
    private Long newPosts;
    private Long newUsers;
    private Long newComments;
}
