package com.campusforum.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 笔记批量改状态请求 DTO。
 *
 * <p>{@code status ∈ {draft, published, hidden}}。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class BatchNoteStatusRequest extends BatchNoteIdsRequest {

    @NotBlank(message = "status 不能为空")
    @Pattern(regexp = "^(draft|published|hidden)$", message = "status 取值必须是 draft/published/hidden 之一")
    private String status;
}
