package com.campusforum.admin.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 数字 ID 通用批量请求 DTO：用于 batch-delete / batch-restore / batch-purge。
 *
 * <p>约束：ids 非空、单次最多 100 条（对齐 {@link BatchUpdateUserStatusRequest} 的既定阈值）。</p>
 */
@Data
public class BatchIdsRequest {

    /** 目标实体 ID 列表。 */
    @NotEmpty(message = "ids 不能为空")
    @Size(max = 100, message = "单次最多 100 条")
    private List<@NotNull Long> ids;
}
