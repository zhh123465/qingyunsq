package com.campusforum.resource.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.campusforum.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("resources")
public class Resource extends BaseEntity {
    private Long uploaderId;
    private Long spaceId;
    private String fileName;
    private Long fileSize;
    private String fileType;
    /** @deprecated MD5 抗碰撞失效；新写入使用 fileSha256，此字段保留用于查询历史数据。 */
    @Deprecated
    private String fileMd5;
    /** SHA-256 hex 指纹，缺陷 1.32 加固；upload 现在写入此字段，去重优先匹配。 */
    private String fileSha256;
    private String storageKey;
    private String visibility;
    private String college;
    private String major;
    private String course;
    private String semester;
    private String tags;
    private Integer downloadCount;
    private Integer collectCount;
    private String version;
    private String description;
    /** 0=隐藏 1=已发布 2=待审核 3=已驳回 */
    private Integer status;
    /** 驳回原因（status=3 时有值）。 */
    private String reviewReason;
    private Long reviewedBy;
    private java.time.LocalDateTime reviewedAt;
}
