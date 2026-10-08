package com.campusforum.admin.dto;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 通用分页结果 DTO：admin 内容管理页统一分页返回体。
 *
 * <p>字段与 Naive UI NDataTable 的 pagination prop 一一对应：
 * {@code page → pageNum}、{@code size → pageSize}、{@code total → itemCount}。</p>
 *
 * @param <T> 列表项类型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    /** 本页数据。 */
    private List<T> items;

    /** 记录总数。 */
    private long total;

    /** 当前页码，1-based。 */
    private long page;

    /** 每页记录数。 */
    private long size;

    /** 总页数 = ceil(total / size)。 */
    private long pages;

    /** 从 MyBatis-Plus 的 {@link IPage} 构建。 */
    public static <T> PageResult<T> of(IPage<T> ipage) {
        return new PageResult<>(
                ipage.getRecords(),
                ipage.getTotal(),
                ipage.getCurrent(),
                ipage.getSize(),
                ipage.getPages()
        );
    }

    /** 手工构造（当 Service 未使用 IPage 时）。 */
    public static <T> PageResult<T> of(List<T> items, long total, long page, long size) {
        long pages = size <= 0 ? 0 : (total + size - 1) / size;
        return new PageResult<>(items, total, page, size, pages);
    }
}
