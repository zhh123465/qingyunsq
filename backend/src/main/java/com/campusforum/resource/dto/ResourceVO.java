package com.campusforum.resource.dto;

import com.campusforum.user.dto.PublicUserVO;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class ResourceVO {
    private Long id;
    private Long uploaderId;
    private PublicUserVO uploader;
    private Long spaceId;
    private String fileName;
    private Long fileSize;
    private String fileType;
    private String visibility;
    private String college;
    private String major;
    private String course;
    private String semester;
    private List<String> tags;
    private Integer downloadCount;
    private Integer collectCount;
    private String version;
    private String description;
    /** 资源状态：0=隐藏 1=已发布 2=待审核 3=已驳回。前台列表默认过滤 status=1。 */
    private Integer status;
    /** 驳回原因（status=3 时有值），仅上传者本人与管理端可见。 */
    private String reviewReason;
    private LocalDateTime reviewedAt;
    private LocalDateTime createdAt;
}
