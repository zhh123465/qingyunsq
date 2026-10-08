package com.campusforum.admin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 数字 ID 批量改状态请求 DTO：用于 batch-status。
 *
 * <p>status 范围放宽到 [0,2] 以覆盖 Post 的 0/1/2 三态；其他实体只用 0/1，controller 内额外收紧。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class BatchStatusRequest extends BatchIdsRequest {

    @NotNull(message = "status 不能为空")
    @Min(value = 0, message = "status 取值范围 [0,2]")
    @Max(value = 2, message = "status 取值范围 [0,2]")
    private Integer status;
}
