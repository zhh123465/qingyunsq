package com.campusforum.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 公告批量改状态请求 DTO。
 *
 * <p>{@code status ∈ {draft, published, archived}}。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class BatchAnnouncementStatusRequest extends BatchIdsRequest {

    @NotBlank(message = "status 不能为空")
    @Pattern(regexp = "^(draft|published|archived)$",
             message = "status 取值必须是 draft/published/archived 之一")
    private String status;
}
