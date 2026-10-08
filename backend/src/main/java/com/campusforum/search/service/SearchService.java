package com.campusforum.search.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campusforum.post.domain.Post;
import com.campusforum.post.mapper.PostMapper;
import com.campusforum.resource.domain.Resource;
import com.campusforum.resource.mapper.ResourceMapper;
import com.campusforum.search.dto.SearchResultVO;
import com.campusforum.space.domain.Space;
import com.campusforum.space.mapper.SpaceMapper;
import com.campusforum.user.domain.User;
import com.campusforum.user.dto.PublicUserVO;
import com.campusforum.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    private final PostMapper postMapper;
    private final UserMapper userMapper;
    private final ResourceMapper resourceMapper;
    private final SpaceMapper spaceMapper;
    private final MeiliSearchClient meiliSearchClient;

    /** 搜索关键字长度硬上限，避免巨型输入触发 ReDoS / 索引 DoS。 */
    private static final int MAX_KEYWORD_LENGTH = 64;

    public List<SearchResultVO> search(String keyword, String type, String sort, Long cursor, int limit) {
        int size = Math.min(limit, 50);
        if (keyword == null) return List.of();
        // 长度截断：保护 FULLTEXT/MeiliSearch 与正则
        String trimmed = keyword.length() > MAX_KEYWORD_LENGTH
                ? keyword.substring(0, MAX_KEYWORD_LENGTH) : keyword;
        String safeKeyword = trimmed.replaceAll("[^\\p{L}\\p{N}\\s]", "").strip();
        if (safeKeyword.isBlank()) return List.of();

        if (type != null && !type.isBlank()) {
            return switch (type.toUpperCase()) {
                case "POST" -> searchPosts(safeKeyword, sort, cursor, size);
                case "USER" -> searchUsers(safeKeyword, cursor, size);
                case "RESOURCE" -> searchResources(safeKeyword, cursor, size);
                case "SPACE" -> searchSpaces(safeKeyword, cursor, size);
                default -> List.of();
            };
        }

        // 无 type 则搜索全部
        List<SearchResultVO> results = new ArrayList<>();
        results.addAll(searchPosts(safeKeyword, sort, cursor, size));
        results.addAll(searchUsers(safeKeyword, cursor, size));
        results.addAll(searchResources(safeKeyword, cursor, size));
        results.addAll(searchSpaces(safeKeyword, cursor, size));
        return results;
    }

    private List<SearchResultVO> searchPosts(String keyword, String sort, Long cursor, int limit) {
        // 优先走 MeiliSearch，未命中或不可用时再降级到 MySQL FULLTEXT/LIKE。
        try {
            List<SearchResultVO> meiliResults = searchPostsViaMeiliSearch(keyword, limit);
            if (!meiliResults.isEmpty()) {
                return meiliResults;
            }
        } catch (Exception e) {
            log.warn("MeiliSearch unavailable, falling back to MySQL FULLTEXT: {}", e.getMessage());
        }

        // MySQL FULLTEXT 兜底
        try {
            LambdaQueryWrapper<Post> qw = new LambdaQueryWrapper<>();
            qw.eq(Post::getStatus, 1);
            qw.apply("MATCH(title, content) AGAINST({0} IN NATURAL LANGUAGE MODE)", keyword);
            if (cursor != null) {
                qw.lt(Post::getId, cursor);
            }
            if ("time".equals(sort)) {
                qw.orderByDesc(Post::getCreatedAt);
            } else {
                qw.orderByDesc(Post::getLikeCount, Post::getId);
            }
            qw.last("LIMIT " + limit);

            List<SearchResultVO> results = postMapper.selectList(qw).stream().map(p -> {
                User author = userMapper.selectById(p.getAuthorId());
                String content = p.getContent() != null ? p.getContent() : "";
                return SearchResultVO.builder()
                        .type("POST")
                        .id(p.getId())
                        .title(p.getTitle())
                        .description(content.length() > 200 ? content.substring(0, 200) + "..." : content)
                        .author(toUserVO(author))
                        .createdAt(p.getCreatedAt())
                        .likeCount(p.getLikeCount())
                        .commentCount(p.getCommentCount())
                        .viewCount(p.getViewCount())
                        .build();
            }).toList();
            return results.isEmpty() ? searchPostsByLike(keyword, sort, cursor, limit) : results;
        } catch (Exception e) {
            log.warn("MySQL FULLTEXT search failed for keyword={}: {}", keyword, e.getMessage());
            // 最终降级：LIKE 模糊搜索
            return searchPostsByLike(keyword, sort, cursor, limit);
        }
    }

    private List<SearchResultVO> searchPostsByLike(String keyword, String sort, Long cursor, int limit) {
        try {
            LambdaQueryWrapper<Post> qw = new LambdaQueryWrapper<>();
            qw.eq(Post::getStatus, 1);
            qw.and(w -> w.like(Post::getTitle, keyword)
                    .or().like(Post::getContent, keyword)
                    .or().like(Post::getTopics, keyword)
                    .or().like(Post::getTags, keyword));
            if (cursor != null) qw.lt(Post::getId, cursor);
            if ("time".equals(sort)) {
                qw.orderByDesc(Post::getCreatedAt);
            } else {
                qw.orderByDesc(Post::getLikeCount, Post::getId);
            }
            qw.last("LIMIT " + limit);

            return postMapper.selectList(qw).stream().map(p -> {
                User author = userMapper.selectById(p.getAuthorId());
                String content = p.getContent() != null ? p.getContent() : "";
                return SearchResultVO.builder()
                        .type("POST")
                        .id(p.getId())
                        .title(p.getTitle())
                        .description(content.length() > 200 ? content.substring(0, 200) + "..." : content)
                        .author(toUserVO(author))
                        .createdAt(p.getCreatedAt())
                        .likeCount(p.getLikeCount())
                        .commentCount(p.getCommentCount())
                        .viewCount(p.getViewCount())
                        .build();
            }).toList();
        } catch (Exception e) {
            log.error("LIKE search also failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<SearchResultVO> searchPostsViaMeiliSearch(String keyword, int limit) {
        // 显式传入当前租户，避免 multi 模式下漏过 filter
        Long tid = com.campusforum.tenant.TenantContext.getTenantId();
        List<Map<String, Object>> hits = meiliSearchClient.search("posts", keyword, limit, tid);
        if (hits.isEmpty()) return List.of();

        return hits.stream().map(hit -> {
            Long postId = toLong(hit.get("id"));
            Long authorId = toLong(hit.get("authorId"));
            User author = authorId != null ? userMapper.selectById(authorId) : null;
            String content = (String) hit.getOrDefault("content", "");
            return SearchResultVO.builder()
                    .type("POST")
                    .id(postId)
                    .title((String) hit.getOrDefault("title", ""))
                    .description(content.length() > 200 ? content.substring(0, 200) + "..." : content)
                    .author(toUserVO(author))
                    .createdAt(toLocalDateTime(hit.get("createdAt")))
                    .likeCount(toInt(hit.get("likeCount")))
                    .commentCount(toInt(hit.get("commentCount")))
                    .viewCount(toInt(hit.get("viewCount")))
                    .build();
        }).toList();
    }

    private Long toLong(Object val) {
        if (val == null) return null;
        if (val instanceof Number n) return n.longValue();
        try { return Long.parseLong(val.toString()); } catch (NumberFormatException e) { return null; }
    }

    private Integer toInt(Object val) {
        if (val == null) return 0;
        if (val instanceof Number n) return n.intValue();
        try { return Integer.parseInt(val.toString()); } catch (NumberFormatException e) { return 0; }
    }

    private LocalDateTime toLocalDateTime(Object val) {
        if (val == null) return null;
        if (val instanceof LocalDateTime dt) return dt;
        try { return LocalDateTime.parse(val.toString().replace("Z", "")); } catch (Exception e) { return null; }
    }

    /**
     * 用户搜索：仅按 {@code nickname} 模糊匹配。
     *
     * <p>对应 bugfix.md 漏洞 9 / T8.4 收紧：</p>
     * <ul>
     *   <li>移除原有的 {@code email} / {@code studentNo} LIKE 分支 ——
     *       即便结果 VO 已经脱敏，LIKE 命中本身就构成"邮箱后缀 / 学号前缀确认"的副信道，
     *       让攻击者搜索 {@code @163.com} 即可批量枚举该域用户列表；</li>
     *   <li>关键字长度 &lt; 2 直接返回空列表，避免单字符搜出全表；</li>
     *   <li>关键字疑似邮箱（含 {@code @}）或全数字 ≥ 8 位（疑似学号）时返回空，
     *       让公共搜索完全无法以 PII 维度反向枚举用户。管理员需要按 email/studentNo
     *       精确定位用户时，请走 {@code /api/v1/admin/users} 后台路径。</li>
     * </ul>
     *
     * <p>同时 {@link SearchResultVO#getAuthor()} 仍使用 {@link PublicUserVO}（仅含
     * {@code id/nickname/avatarUrl/bio}），不再回传 email / studentNo 字段。</p>
     */
    private List<SearchResultVO> searchUsers(String keyword, Long cursor, int limit) {
        // 漏洞 9：长度 < 2 一律拒绝，避免单字符搜出整张表
        if (keyword == null || keyword.length() < 2) {
            return List.of();
        }
        // 漏洞 9：疑似邮箱（含 @）或纯数字 ≥ 8 位（疑似学号）一律拒绝
        if (keyword.contains("@") || keyword.matches("^\\d{8,}$")) {
            return List.of();
        }

        // 优先走 MeiliSearch（索引文档不含 email/studentNo，天然满足漏洞 9 的 PII 约束）
        try {
            Long tid = com.campusforum.tenant.TenantContext.getTenantId();
            List<Map<String, Object>> hits = meiliSearchClient.search(SearchIndexService.INDEX_USERS, keyword, limit, tid);
            if (!hits.isEmpty()) {
                return hits.stream().map(hit -> {
                    String college = (String) hit.getOrDefault("college", null);
                    String major = (String) hit.getOrDefault("major", null);
                    return SearchResultVO.builder()
                            .type("USER")
                            .id(toLong(hit.get("id")))
                            .title((String) hit.getOrDefault("nickname", ""))
                            .description(college != null ? college + " " + (major != null ? major : "") : "")
                            .author(PublicUserVO.builder()
                                    .id(toLong(hit.get("id")))
                                    .nickname((String) hit.getOrDefault("nickname", ""))
                                    .avatarUrl((String) hit.getOrDefault("avatarUrl", null))
                                    .build())
                            .build();
                }).toList();
            }
        } catch (Exception e) {
            log.warn("MeiliSearch user search unavailable, falling back to MySQL LIKE: {}", e.getMessage());
        }

        LambdaQueryWrapper<User> qw = new LambdaQueryWrapper<>();
        qw.eq(User::getStatus, 1);
        // 漏洞 9：仅保留 nickname LIKE，禁止按 email / studentNo 模糊匹配
        qw.like(User::getNickname, keyword);
        if (cursor != null) {
            qw.lt(User::getId, cursor);
        }
        qw.orderByDesc(User::getId);
        qw.last("LIMIT " + limit);

        return userMapper.selectList(qw).stream().map(u -> SearchResultVO.builder()
                .type("USER")
                .id(u.getId())
                .title(u.getNickname())
                .description(u.getCollege() != null ? u.getCollege() + " " + (u.getMajor() != null ? u.getMajor() : "") : "")
                .author(PublicUserVO.builder().id(u.getId()).nickname(u.getNickname()).avatarUrl(u.getAvatarUrl()).build())
                .build()).toList();
    }

    private List<SearchResultVO> searchResources(String keyword, Long cursor, int limit) {
        // 优先走 MeiliSearch，不可用或无命中时降级 MySQL LIKE
        try {
            Long tid = com.campusforum.tenant.TenantContext.getTenantId();
            List<Map<String, Object>> hits = meiliSearchClient.search(SearchIndexService.INDEX_RESOURCES, keyword, limit, tid);
            if (!hits.isEmpty()) {
                return hits.stream().map(hit -> {
                    Long uploaderId = toLong(hit.get("uploaderId"));
                    User uploader = uploaderId != null ? userMapper.selectById(uploaderId) : null;
                    return SearchResultVO.builder()
                            .type("RESOURCE")
                            .id(toLong(hit.get("id")))
                            .title((String) hit.getOrDefault("fileName", ""))
                            .description((String) hit.getOrDefault("description", null))
                            .author(toUserVO(uploader))
                            .createdAt(toLocalDateTime(hit.get("createdAt")))
                            .downloadCount(toInt(hit.get("downloadCount")))
                            .fileType((String) hit.getOrDefault("fileType", null))
                            .fileSize(toLong(hit.get("fileSize")))
                            .build();
                }).toList();
            }
        } catch (Exception e) {
            log.warn("MeiliSearch resource search unavailable, falling back to MySQL LIKE: {}", e.getMessage());
        }

        LambdaQueryWrapper<Resource> qw = new LambdaQueryWrapper<>();
        qw.eq(Resource::getStatus, 1);
        qw.and(w -> w.like(Resource::getFileName, keyword)
                .or().like(Resource::getDescription, keyword)
                .or().like(Resource::getCourse, keyword)
                .or().like(Resource::getCollege, keyword)
                .or().like(Resource::getMajor, keyword)
                .or().like(Resource::getSemester, keyword)
                .or().like(Resource::getTags, keyword));
        if (cursor != null) {
            qw.lt(Resource::getId, cursor);
        }
        qw.orderByDesc(Resource::getId);
        qw.last("LIMIT " + limit);

        return resourceMapper.selectList(qw).stream().map(r -> {
            User uploader = userMapper.selectById(r.getUploaderId());
            return SearchResultVO.builder()
                    .type("RESOURCE")
                    .id(r.getId())
                    .title(r.getFileName())
                    .description(r.getDescription())
                    .author(toUserVO(uploader))
                    .createdAt(r.getCreatedAt())
                    .downloadCount(r.getDownloadCount())
                    .fileType(r.getFileType())
                    .fileSize(r.getFileSize())
                    .build();
        }).toList();
    }

    private List<SearchResultVO> searchSpaces(String keyword, Long cursor, int limit) {
        // 优先走 MeiliSearch，不可用或无命中时降级 MySQL LIKE
        try {
            Long tid = com.campusforum.tenant.TenantContext.getTenantId();
            List<Map<String, Object>> hits = meiliSearchClient.search(SearchIndexService.INDEX_SPACES, keyword, limit, tid);
            if (!hits.isEmpty()) {
                return hits.stream().map(hit -> {
                    Long ownerId = toLong(hit.get("ownerId"));
                    User owner = ownerId != null ? userMapper.selectById(ownerId) : null;
                    return SearchResultVO.builder()
                            .type("SPACE")
                            .id(toLong(hit.get("id")))
                            .title((String) hit.getOrDefault("name", ""))
                            .description((String) hit.getOrDefault("description", null))
                            .author(toUserVO(owner))
                            .createdAt(toLocalDateTime(hit.get("createdAt")))
                            .category((String) hit.getOrDefault("category", null))
                            .memberCount(toInt(hit.get("memberCount")))
                            .postCount(toInt(hit.get("postCount")))
                            .build();
                }).toList();
            }
        } catch (Exception e) {
            log.warn("MeiliSearch space search unavailable, falling back to MySQL LIKE: {}", e.getMessage());
        }

        LambdaQueryWrapper<Space> qw = new LambdaQueryWrapper<>();
        qw.eq(Space::getStatus, 1);
        qw.and(w -> w.like(Space::getName, keyword)
                .or().like(Space::getDescription, keyword)
                .or().like(Space::getCategory, keyword));
        if (cursor != null) {
            qw.lt(Space::getId, cursor);
        }
        qw.orderByDesc(Space::getMemberCount, Space::getId);
        qw.last("LIMIT " + limit);

        return spaceMapper.selectList(qw).stream().map(s -> {
            User owner = userMapper.selectById(s.getOwnerId());
            return SearchResultVO.builder()
                    .type("SPACE")
                    .id(s.getId())
                    .title(s.getName())
                    .description(s.getDescription())
                    .author(toUserVO(owner))
                    .createdAt(s.getCreatedAt())
                    .category(s.getCategory())
                    .memberCount(s.getMemberCount())
                    .postCount(s.getPostCount())
                    .build();
        }).toList();
    }

    private PublicUserVO toUserVO(User user) {
        return PublicUserVO.from(user);
    }
}
