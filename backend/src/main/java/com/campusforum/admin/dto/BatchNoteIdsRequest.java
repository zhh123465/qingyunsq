package com.campusforum.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 笔记（AiNote 的 String id）批量请求 DTO：用于 batch-delete / batch-restore / batch-purge。
 */
@Data
public class BatchNoteIdsRequest {

    @NotEmpty(message = "ids 不能为空")
    @Size(max = 100, message = "单次最多 100 条")
    private List<@NotBlank String> ids;
}
