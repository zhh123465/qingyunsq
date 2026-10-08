package com.campusforum.search.service;

import com.campusforum.resource.domain.Resource;
import com.campusforum.space.domain.Space;
import com.campusforum.user.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 用户 / 资源 / 空间的 MeiliSearch 索引同步服务。
 *
 * <p>与帖子（{@code PostService#buildPostDoc}）采用同一范式：业务写路径成功后
 * 直接同步调用本服务写入 / 删除索引文档，MeiliSearch 不可用时静默失败
 * （{@link MeiliSearchClient} 内部吞异常），搜索侧再由 MySQL LIKE 兜底。</p>
 *
 * <p>索引策略：只索引"对外可见"的记录（status=1 且 deleted=0），
 * 隐藏 / 逻辑删除 / 封禁一律从索引删除。这样搜索路径无需再按 status 过滤。</p>
 *
 * <p>PII 约束（bugfix.md 漏洞 9 / T8.4）：用户文档<b>禁止</b>包含
 * email / studentNo 等可枚举字段，只索引 nickname 与学院专业展示信息。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchIndexService {

    public static final String INDEX_USERS = "users";
    public static final String INDEX_RESOURCES = "resources";
    public static final String INDEX_SPACES = "spaces";

    private final MeiliSearchClient meiliSearchClient;

    // ===== 用户 =====

    public void indexUser(User user) {
        if (user == null) return;
        if (user.getStatus() == null || user.getStatus() != 1) {
            deleteUser(user.getId());
            return;
        }
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", user.getId());
        doc.put("nickname", user.getNickname());
        doc.put("college", user.getCollege());
        doc.put("major", user.getMajor());
        doc.put("avatarUrl", user.getAvatarUrl());
        if (user.getTenantId() != null) doc.put("tenantId", user.getTenantId());
        meiliSearchClient.indexDocument(INDEX_USERS, doc);
    }

    public void deleteUser(Long userId) {
        if (userId == null) return;
        meiliSearchClient.deleteDocument(INDEX_USERS, userId);
    }

    // ===== 资源 =====

    public void indexResource(Resource r) {
        if (r == null) return;
        boolean visible = r.getStatus() != null && r.getStatus() == 1
                && (r.getDeleted() == null || r.getDeleted() == 0);
        if (!visible) {
            deleteResource(r.getId());
            return;
        }
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", r.getId());
        doc.put("fileName", r.getFileName());
        doc.put("description", r.getDescription());
        doc.put("course", r.getCourse());
        doc.put("college", r.getCollege());
        doc.put("major", r.getMajor());
        doc.put("semester", r.getSemester());
        doc.put("tags", r.getTags());
        doc.put("fileType", r.getFileType());
        doc.put("fileSize", r.getFileSize());
        doc.put("downloadCount", r.getDownloadCount());
        doc.put("uploaderId", r.getUploaderId());
        doc.put("createdAt", r.getCreatedAt());
        if (r.getTenantId() != null) doc.put("tenantId", r.getTenantId());
        meiliSearchClient.indexDocument(INDEX_RESOURCES, doc);
    }

    public void deleteResource(Long resourceId) {
        if (resourceId == null) return;
        meiliSearchClient.deleteDocument(INDEX_RESOURCES, resourceId);
    }

    // ===== 空间 =====

    public void indexSpace(Space s) {
        if (s == null) return;
        boolean visible = s.getStatus() != null && s.getStatus() == 1
                && (s.getDeleted() == null || s.getDeleted() == 0);
        if (!visible) {
            deleteSpace(s.getId());
            return;
        }
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", s.getId());
        doc.put("name", s.getName());
        doc.put("description", s.getDescription());
        doc.put("category", s.getCategory());
        doc.put("memberCount", s.getMemberCount());
        doc.put("postCount", s.getPostCount());
        doc.put("ownerId", s.getOwnerId());
        doc.put("createdAt", s.getCreatedAt());
        if (s.getTenantId() != null) doc.put("tenantId", s.getTenantId());
        meiliSearchClient.indexDocument(INDEX_SPACES, doc);
    }

    public void deleteSpace(Long spaceId) {
        if (spaceId == null) return;
        meiliSearchClient.deleteDocument(INDEX_SPACES, spaceId);
    }
}
