package com.campusforum.resource.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campusforum.common.BusinessException;
import com.campusforum.common.ErrorCode;
import com.campusforum.infra.StorageService;
import com.campusforum.post.mapper.ReactionMapper;
import com.campusforum.resource.domain.Resource;
import com.campusforum.resource.dto.ResourcePreviewVO;
import com.campusforum.resource.dto.ResourceVO;
import com.campusforum.resource.dto.UploadResourceRequest;
import com.campusforum.resource.mapper.ResourceMapper;
import com.campusforum.space.domain.Space;
import com.campusforum.space.domain.SpaceMember;
import com.campusforum.space.mapper.SpaceMapper;
import com.campusforum.space.mapper.SpaceMemberMapper;
import com.campusforum.user.domain.User;
import com.campusforum.user.dto.PublicUserVO;
import com.campusforum.user.mapper.UserMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.stream.Collectors;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

@Slf4j
@Service
public class ResourceService {

    private final ResourceMapper resourceMapper;
    private final UserMapper userMapper;
    private final SpaceMapper spaceMapper;
    private final SpaceMemberMapper spaceMemberMapper;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    /** 用于回收站 purge 时级联清理与资源相关的 reactions。 */
    private ReactionMapper reactionMapper;
    /** setter 注入避免破坏现有构造器签名。 */
    @org.springframework.beans.factory.annotation.Autowired
    public void setReactionMapper(ReactionMapper reactionMapper) {
        this.reactionMapper = reactionMapper;
    }

    /** Self reference for AOP self-invocation in batch purge (per-item transaction). */
    private ResourceService selfProxy;
    @org.springframework.beans.factory.annotation.Autowired
    public void setSelfProxy(@Lazy ResourceService selfProxy) {
        this.selfProxy = selfProxy;
    }

    /** 资源搜索索引同步（setter 注入，避免破坏现有构造器签名与测试）。 */
    private com.campusforum.search.service.SearchIndexService searchIndexService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSearchIndexService(com.campusforum.search.service.SearchIndexService searchIndexService) {
        this.searchIndexService = searchIndexService;
    }

    /** 审核流通知器（setter 注入，避免破坏现有构造器签名与测试）。 */
    private com.campusforum.resource.service.ResourceReviewNotifier reviewNotifier;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setReviewNotifier(com.campusforum.resource.service.ResourceReviewNotifier reviewNotifier) {
        this.reviewNotifier = reviewNotifier;
    }

    /** 上传安全配置（setter 注入）：allow-any-extension 开关与扩展名黑名单。 */
    private com.campusforum.infra.security.SecurityProperties securityProperties;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSecurityProperties(com.campusforum.infra.security.SecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    /** 索引同步 best-effort：任何异常都不影响主业务流程。 */
    private void syncResourceIndex(Resource r) {
        if (searchIndexService == null || r == null) return;
        try {
            searchIndexService.indexResource(r);
        } catch (Exception e) {
            log.debug("Resource search index sync failed for id={}: {}", r.getId(), e.getMessage());
        }
    }

    private void removeResourceIndex(Long id) {
        if (searchIndexService == null || id == null) return;
        try {
            searchIndexService.deleteResource(id);
        } catch (Exception e) {
            log.debug("Resource search index delete failed for id={}: {}", id, e.getMessage());
        }
    }

    // SEC-03: 从配置文件读取文件扩展名白名单
    private final Set<String> allowedExtensions;
    /** 真实 MIME 校验器（缺陷 1.22）。 */
    private final com.campusforum.infra.security.MimeTypeValidator mimeTypeValidator;
    private static final int MAX_TEXT_PREVIEW_CHARS = 200_000;
    private static final Set<String> TEXT_PREVIEW_TYPES = Set.of(
            "txt", "log", "csv", "json", "xml", "yml", "yaml",
            "sql", "java", "py", "js", "jsx", "ts", "tsx", "vue",
            "css", "scss", "html", "htm"
    );

    public ResourceService(ResourceMapper resourceMapper, UserMapper userMapper,
                           SpaceMapper spaceMapper, SpaceMemberMapper spaceMemberMapper,
                           StorageService storageService, ObjectMapper objectMapper,
                           com.campusforum.infra.security.MimeTypeValidator mimeTypeValidator,
                           @Value("${upload.allowed-extensions:pdf,doc,docx,ppt,pptx,xls,xlsx,txt,log,csv,json,xml,yml,yaml,sql,java,py,js,jsx,ts,tsx,vue,css,scss,html,htm,jpg,jpeg,png,gif,webp,bmp,mp4,webm,mov,avi,mp3,wav,m4a,ogg,md,markdown}") String allowedExtensionsConfig) {
        this.resourceMapper = resourceMapper;
        this.userMapper = userMapper;
        this.spaceMapper = spaceMapper;
        this.spaceMemberMapper = spaceMemberMapper;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
        this.mimeTypeValidator = mimeTypeValidator;
        this.allowedExtensions = Arrays.stream(allowedExtensionsConfig.split(","))
                .map(String::trim).map(String::toLowerCase)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Transactional
    public ResourceVO upload(Long userId, MultipartFile file, UploadResourceRequest req) {
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "文件名为空");
        }

        int dot = originalName.lastIndexOf('.');
        String ext = dot >= 0 ? originalName.substring(dot + 1).toLowerCase() : "";
        if (ext.length() > 32) {
            // fileType 列 VARCHAR(32)；超长"扩展名"按无类型处理，交人工审核甄别
            ext = "";
        }
        // 审核流（2026-07-13）：allow-any-extension 开启时跳过扩展名白名单，
        // 任意类型（含可执行程序）进入待审核队列由人工甄别；黑名单配置仍生效。
        if (!allowAnyExtension()) {
            // SEC-03: 校验文件扩展名白名单，防止上传可执行/危险文件
            if (ext.isBlank() || !allowedExtensions.contains(ext)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(),
                        "不支持的文件类型：." + ext + "，允许的类型：" + String.join(", ", allowedExtensions));
            }
        } else if (securityProperties != null
                && securityProperties.getUpload().getBlockedExtensions().contains(ext)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "禁止上传 ." + ext + " 类型文件");
        }

        // 安全加固（缺陷 1.22）：检测真实 MIME 类型，与扩展名做交叉验证。
        // 即使攻击者把 PHP 改名为 .png 也会在此被拦截。
        mimeTypeValidator.validate(file, ext);

        // 安全加固（缺陷 1.13 + 1.32）：
        // - 流式 SHA-256 计算 + 上传，避免 file.getBytes() 把整个 50MB 文件读进堆
        // - 使用 SHA-256 替代 MD5 做指纹，避免抗碰撞失效带来的去重歧义
        String storageKey;
        String sha256Hex;
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream in = file.getInputStream();
                 DigestInputStream dis = new DigestInputStream(in, sha256)) {
                // 漏洞 6（bugfix.md）：必须传 file.getSize() 而非 dis.available()。
                // available() 仅返回当前 buffer 内已就绪字节数（通常 ~8KB），
                // 在 MinIO 实现下会被当作 size 上限，导致大文件被截断。
                storageKey = storageService.upload(dis, originalName, file.getContentType(), file.getSize());
            }
            sha256Hex = HexFormat.of().formatHex(sha256.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new BusinessException(ErrorCode.STORAGE_ERROR);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.STORAGE_ERROR);
        }

        // 去重：优先匹配 SHA-256；同一文件已存在时回收新上传的对象
        Resource existing = resourceMapper.selectOne(new LambdaQueryWrapper<Resource>()
                .eq(Resource::getFileSha256, sha256Hex)
                .eq(Resource::getStatus, 1)
                .last("LIMIT 1"));
        if (existing != null) {
            log.info("Duplicate file detected by SHA-256, reusing existing resource {}", existing.getId());
            // 删除刚上传到存储的副本，避免重复占用空间
            try { storageService.delete(storageKey); } catch (Exception ignored) {}
            return toVO(existing);
        }

        Resource resource = new Resource();
        resource.setUploaderId(userId);
        resource.setSpaceId(req.getSpaceId());
        resource.setFileName(originalName);
        resource.setFileSize(file.getSize());
        resource.setFileType(ext);
        resource.setStorageKey(storageKey);
        resource.setFileSha256(sha256Hex);
        // 历史 file_md5 列保持 NULL；后续清理迁移完成后从实体与表中删除
        resource.setVisibility(req.getVisibility() != null ? req.getVisibility() : "PUBLIC");
        resource.setCollege(req.getCollege());
        resource.setMajor(req.getMajor());
        resource.setCourse(req.getCourse());
        resource.setSemester(req.getSemester());
        if (req.getTags() != null && !req.getTags().isEmpty()) {
            try {
                resource.setTags(objectMapper.writeValueAsString(req.getTags()));
            } catch (JsonProcessingException e) {
                resource.setTags("[]");
            }
        }
        resource.setDescription(req.getDescription());
        resource.setDownloadCount(0);
        resource.setCollectCount(0);
        // 审核流（2026-07-13）：普通用户上传落待审核（status=2），管理员上传免审直接发布
        String role = currentRoleOrNull();
        boolean isAdmin = "TENANT_ADMIN".equals(role) || "SUPER_ADMIN".equals(role);
        resource.setStatus(isAdmin ? 1 : 2);

        resourceMapper.insert(resource);
        syncResourceIndex(resource);
        log.info("Resource uploaded: id={}, fileName={}, status={}", resource.getId(), originalName, resource.getStatus());
        if (!isAdmin && reviewNotifier != null) {
            try {
                reviewNotifier.onResourcePending(resource);
            } catch (Exception e) {
                log.warn("Resource review notify failed for id={}: {}", resource.getId(), e.getMessage());
            }
        }
        return toVO(resource);
    }

    private boolean allowAnyExtension() {
        return securityProperties != null && securityProperties.getUpload().isAllowAnyExtension();
    }

    public ResourceVO getById(Long resourceId) {
        return getById(resourceId, null);
    }

    /**
     * @param sigUserId 签名直链场景下 token 内嵌的签发用户 ID（浏览器直链不带 Authorization 头，
     *                  StpUtil 上下文为游客）。null = 用当前登录态；0 = 游客占位。
     *                  没有它，上传者打开自己待审核/私有资源的预览、下载直链会被按游客拒绝（404）。
     */
    public ResourceVO getById(Long resourceId, Long sigUserId) {
        Resource resource = resourceMapper.selectById(resourceId);
        if (resource == null || resource.getDeleted() == 1) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (sigUserId != null) ensureCanAccessAs(resource, sigUserId);
        else ensureCanAccess(resource);
        return toVO(resource);
    }

    public List<ResourceVO> list(Long spaceId, String college, String major, String course, Long cursor, int limit) {
        int size = Math.min(limit, 50);
        LambdaQueryWrapper<Resource> qw = new LambdaQueryWrapper<>();
        qw.eq(Resource::getStatus, 1);
        if (spaceId != null) {
            qw.eq(Resource::getSpaceId, spaceId);
        }
        if (college != null && !college.isBlank()) {
            qw.eq(Resource::getCollege, college);
        }
        if (major != null && !major.isBlank()) {
            qw.eq(Resource::getMajor, major);
        }
        if (course != null && !course.isBlank()) {
            qw.like(Resource::getCourse, course);
        }
        if (cursor != null) {
            qw.lt(Resource::getId, cursor);
        }
        qw.orderByDesc(Resource::getId);
        qw.last("LIMIT " + size);

        // 列表层面也按可见性过滤，避免 PRIVATE / 仅空间成员可见的资源在列表中泄漏
        Long currentUserId = currentUserIdOrNull();
        String currentRole = currentRoleOrNull();
        return resourceMapper.selectList(qw).stream()
                .filter(r -> canAccess(r, currentUserId, currentRole))
                .map(this::toVO)
                .toList();
    }

    @Transactional
    public InputStream download(Long resourceId) {
        return download(resourceId, null);
    }

    @Transactional
    public InputStream download(Long resourceId, Long sigUserId) {
        Resource resource = resourceMapper.selectById(resourceId);
        if (resource == null || resource.getDeleted() == 1) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (sigUserId != null) ensureCanAccessAs(resource, sigUserId);
        else ensureCanAccess(resource);

        // 用 SQL 原子自增计数，避免并发下载时的丢失更新
        resourceMapper.incrementDownloadCount(resourceId);

        return storageService.download(resource.getStorageKey());
    }

    public InputStream preview(Long resourceId) {
        return preview(resourceId, null);
    }

    public InputStream preview(Long resourceId, Long sigUserId) {
        Resource resource = getActiveResource(resourceId);
        if (sigUserId != null) ensureCanAccessAs(resource, sigUserId);
        else ensureCanAccess(resource);
        return downloadFromStorageOrThrow(resource);
    }

    public ResourcePreviewVO previewText(Long resourceId) {
        Resource resource = getActiveResource(resourceId);
        ensureCanAccess(resource);
        String fileType = resource.getFileType() == null ? "" : resource.getFileType().toLowerCase();

        try (InputStream is = downloadFromStorageOrThrow(resource)) {
            String content;
            if ("md".equals(fileType) || "markdown".equals(fileType)) {
                content = readUtf8Text(is);
            } else if ("docx".equals(fileType)) {
                content = extractDocxText(is);
            } else if ("pptx".equals(fileType)) {
                content = extractPptxText(is);
            } else if ("xlsx".equals(fileType)) {
                content = extractXlsxText(is);
            } else if (TEXT_PREVIEW_TYPES.contains(fileType)) {
                content = readUtf8Text(is);
            } else {
                throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "该文件类型暂不支持文本预览");
            }

            return ResourcePreviewVO.builder()
                    .id(resource.getId())
                    .fileName(resource.getFileName())
                    .fileType(resource.getFileType())
                    .content(clipPreviewText(content))
                    .build();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.STORAGE_ERROR);
        }
    }

    public String getFileName(Long resourceId) {
        return getFileName(resourceId, null);
    }

    public String getFileName(Long resourceId, Long sigUserId) {
        Resource resource = getActiveResource(resourceId);
        if (sigUserId != null) ensureCanAccessAs(resource, sigUserId);
        else ensureCanAccess(resource);
        return resource.getFileName();
    }

    private InputStream downloadFromStorageOrThrow(Resource resource) {
        try {
            return storageService.download(resource.getStorageKey());
        } catch (BusinessException e) {
            if (e.getCode() == ErrorCode.NOT_FOUND.getCode() || e.getCode() == ErrorCode.RESOURCE_NOT_FOUND.getCode()) {
                log.warn("Resource storage object missing: resourceId={}, storageKey={}",
                        resource.getId(), resource.getStorageKey());
                throw new BusinessException(ErrorCode.STORAGE_ERROR.getCode(), "源文件不存在或对象存储不可读，请重新上传资源");
            }
            throw e;
        }
    }

    public String getFileType(Long resourceId) {
        Resource resource = getActiveResource(resourceId);
        ensureCanAccess(resource);
        return resource.getFileType();
    }

    @Transactional
    public void delete(Long resourceId, Long userId) {
        Resource resource = resourceMapper.selectById(resourceId);
        if (resource == null || resource.getDeleted() == 1) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (!resource.getUploaderId().equals(userId)) {
            String role = (String) StpUtil.getSession().get("role");
            if (!"TENANT_ADMIN".equals(role) && !"SUPER_ADMIN".equals(role)) {
                throw new BusinessException(ErrorCode.FORBIDDEN);
            }
        }

        storageService.delete(resource.getStorageKey());
        resourceMapper.deleteById(resourceId);
        removeResourceIndex(resourceId);
        log.info("Resource deleted: id={}", resourceId);
    }

    /** 我的上传：本人全部状态（含待审核/已驳回/隐藏）的资源，供上传者追踪审核进度。 */
    public List<ResourceVO> listMine(Long userId, Long cursor, int limit) {
        int size = Math.min(limit, 50);
        LambdaQueryWrapper<Resource> qw = new LambdaQueryWrapper<>();
        qw.eq(Resource::getUploaderId, userId);
        if (cursor != null) {
            qw.lt(Resource::getId, cursor);
        }
        qw.orderByDesc(Resource::getId);
        qw.last("LIMIT " + size);
        return resourceMapper.selectList(qw).stream().map(this::toVO).toList();
    }

    // === 审核流（2026-07-13）：通过 / 驳回 / 批量通过 ===

    /** 审核通过：待审核(2)或已驳回(3) → 已发布(1)，入搜索索引并站内通知上传者。 */
    @Transactional
    public void approve(Long resourceId, Long reviewerId) {
        Resource r = resourceMapper.selectById(resourceId);
        if (r == null || r.getDeleted() == 1) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (r.getStatus() != null && r.getStatus() == 1) {
            return; // 幂等：已发布不重复处理
        }
        r.setStatus(1);
        r.setReviewReason(null);
        r.setReviewedBy(reviewerId);
        r.setReviewedAt(java.time.LocalDateTime.now());
        resourceMapper.updateById(r);
        syncResourceIndex(r);
        if (reviewNotifier != null) {
            try {
                reviewNotifier.onReviewResult(r, true, null, reviewerId);
            } catch (Exception e) {
                log.warn("Resource approve notify failed for id={}: {}", resourceId, e.getMessage());
            }
        }
        log.info("Resource approved: id={}, reviewer={}", resourceId, reviewerId);
    }

    /** 审核驳回：→ 已驳回(3)，记录原因并站内通知上传者（含原因）。 */
    @Transactional
    public void reject(Long resourceId, Long reviewerId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "驳回原因不能为空");
        }
        Resource r = resourceMapper.selectById(resourceId);
        if (r == null || r.getDeleted() == 1) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        r.setStatus(3);
        r.setReviewReason(reason.length() > 255 ? reason.substring(0, 255) : reason);
        r.setReviewedBy(reviewerId);
        r.setReviewedAt(java.time.LocalDateTime.now());
        resourceMapper.updateById(r);
        removeResourceIndex(resourceId);
        if (reviewNotifier != null) {
            try {
                reviewNotifier.onReviewResult(r, false, r.getReviewReason(), reviewerId);
            } catch (Exception e) {
                log.warn("Resource reject notify failed for id={}: {}", resourceId, e.getMessage());
            }
        }
        log.info("Resource rejected: id={}, reviewer={}", resourceId, reviewerId);
    }

    /** 批量审核通过：逐条走 approve 保证通知与索引同步，失败的记录 id 返回。 */
    public Map<String, Object> approveBatch(List<Long> ids, Long reviewerId) {
        int success = 0;
        List<Long> failed = new ArrayList<>();
        for (Long id : ids) {
            try {
                selfProxy.approve(id, reviewerId);
                success++;
            } catch (Exception e) {
                log.warn("batch approve failed: id={}, msg={}", id, e.getMessage());
                failed.add(id);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put("failed", failed);
        return result;
    }

    // === 管理端：列表 / 隐藏 / 逻辑删 / 回收站恢复 / 彻底删除 ===
    public IPage<ResourceVO> listForAdminPaged(String keyword, String visibility, Integer status,
                                               long pageNum, long pageSize) {
        long size = Math.min(pageSize, 100);
        Page<Resource> page = new Page<>(pageNum, size);
        LambdaQueryWrapper<Resource> qw = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like(Resource::getFileName, keyword)
                    .or().like(Resource::getDescription, keyword));
        }
        if (visibility != null && !visibility.isBlank()) qw.eq(Resource::getVisibility, visibility);
        if (status != null) qw.eq(Resource::getStatus, status);
        qw.orderByDesc(Resource::getId);
        IPage<Resource> res = resourceMapper.selectPage(page, qw);
        return res.convert(this::toVO);
    }

    public IPage<ResourceVO> listTrashForAdminPaged(String keyword, long pageNum, long pageSize) {
        long size = Math.min(pageSize, 100);
        Page<Resource> page = new Page<>(pageNum, size);
        QueryWrapper<Resource> qw = new QueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            qw.lambda().and(w -> w.like(Resource::getFileName, keyword)
                    .or().like(Resource::getDescription, keyword));
        }
        qw.lambda().orderByDesc(Resource::getId);
        IPage<Resource> res = resourceMapper.selectTrashPage(page, qw);
        return res.convert(this::toVO);
    }

    @Transactional
    public void setStatusForAdmin(Long resourceId, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "无效的状态值");
        }
        Resource r = resourceMapper.selectById(resourceId);
        if (r == null || r.getDeleted() == 1) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        r.setStatus(status);
        resourceMapper.updateById(r);
        // status=1 重新入索引，status=0 从索引删除（indexResource 内部按可见性判断）
        syncResourceIndex(r);
    }

    /**
     * 管理员逻辑删除：不删 OSS 文件（留给 purge），只把 deleted 置 1，进入回收站。
     */
    @Transactional
    public void deleteByAdmin(Long resourceId) {
        Resource r = resourceMapper.selectById(resourceId);
        if (r == null || r.getDeleted() == 1) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        resourceMapper.deleteById(resourceId);
        removeResourceIndex(resourceId);
    }

    @Transactional
    public void restoreForAdmin(Long resourceId) {
        int rows = resourceMapper.restoreById(resourceId);
        if (rows == 0) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        syncResourceIndex(resourceMapper.selectById(resourceId));
    }

    /** 彻底删除：OSS 文件 + reactions 级联 + 记录本体。 */
    @Transactional
    public Map<String, Integer> purgeForAdmin(Long resourceId) {
        // 用 selectTrash 而不是 selectById，因为已删除记录被 deleted=0 过滤掉了
        QueryWrapper<Resource> qw = new QueryWrapper<>();
        qw.lambda().eq(Resource::getId, resourceId);
        List<Resource> found = resourceMapper.selectTrash(qw);
        if (found.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND.getCode(),
                    "资源不在回收站或已被清理");
        }
        Resource r = found.get(0);
        try {
            storageService.delete(r.getStorageKey());
        } catch (Exception e) {
            log.warn("Purge resource {} but OSS delete failed: {}", resourceId, e.getMessage());
        }
        int reactions = reactionMapper == null ? 0
                : reactionMapper.physicalDeleteByTarget("RESOURCE", resourceId);
        int self = resourceMapper.physicalDeleteById(resourceId);
        if (self == 0) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND.getCode(),
                    "资源已被清理");
        }
        removeResourceIndex(resourceId);
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("reactions", reactions);
        return counts;
    }

    // === 批量管理端操作 ===
    public int setStatusBatchForAdmin(List<Long> ids, int status) {
        int rows = resourceMapper.batchSetStatus(ids, status);
        ids.forEach(id -> syncResourceIndex(resourceMapper.selectById(id)));
        return rows;
    }

    public int deleteBatchForAdmin(List<Long> ids) {
        int rows = resourceMapper.batchLogicalDelete(ids);
        ids.forEach(this::removeResourceIndex);
        return rows;
    }

    public int restoreBatchForAdmin(List<Long> ids) {
        int rows = resourceMapper.batchRestore(ids);
        ids.forEach(id -> syncResourceIndex(resourceMapper.selectById(id)));
        return rows;
    }

    public Map<String, Object> purgeBatchForAdmin(List<Long> ids) {
        int success = 0;
        List<Map<String, Object>> failed = new ArrayList<>();
        Map<String, Integer> totalCounts = new LinkedHashMap<>();
        for (Long id : ids) {
            try {
                Map<String, Integer> c = selfProxy.purgeForAdmin(id);
                success++;
                c.forEach((k, v) -> totalCounts.merge(k, v, Integer::sum));
            } catch (BusinessException e) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("id", id);
                f.put("reason", e.getMessage() != null ? e.getMessage() : "PURGE_FAILED");
                failed.add(f);
            } catch (Exception e) {
                log.warn("purge resource failed: id={}, msg={}", id, e.getMessage());
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("id", id);
                f.put("reason", "PURGE_FAILED");
                failed.add(f);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put("failed", failed);
        result.put("counts", totalCounts);
        return result;
    }

    private ResourceVO toVO(Resource r) {
        User uploader = userMapper.selectById(r.getUploaderId());
        PublicUserVO uploaderVO = PublicUserVO.from(uploader);

        List<String> tagList = Collections.emptyList();
        if (r.getTags() != null && !r.getTags().isEmpty() && !"[]".equals(r.getTags())) {
            try {
                tagList = objectMapper.readValue(r.getTags(), List.class);
            } catch (Exception ignored) {
            }
        }

        return ResourceVO.builder()
                .id(r.getId())
                .uploaderId(r.getUploaderId())
                .uploader(uploaderVO)
                .spaceId(r.getSpaceId())
                .fileName(r.getFileName())
                .fileSize(r.getFileSize())
                .fileType(r.getFileType())
                .visibility(r.getVisibility())
                .college(r.getCollege())
                .major(r.getMajor())
                .course(r.getCourse())
                .semester(r.getSemester())
                .tags(tagList)
                .downloadCount(r.getDownloadCount())
                .collectCount(r.getCollectCount())
                .version(r.getVersion())
                .description(r.getDescription())
                .status(r.getStatus())
                .reviewReason(r.getReviewReason())
                .reviewedAt(r.getReviewedAt())
                .createdAt(r.getCreatedAt())
                .build();
    }

    private Resource getActiveResource(Long resourceId) {
        Resource resource = resourceMapper.selectById(resourceId);
        if (resource == null || resource.getDeleted() == 1) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return resource;
    }

    /**
     * 资源访问可见性校验。规则：
     * <ul>
     *   <li>上传者本人或租户/超管：始终可访问；</li>
     *   <li>visibility = PUBLIC：登录用户均可访问；</li>
     *   <li>visibility = PRIVATE：仅上传者可访问（管理员除外）；</li>
     *   <li>visibility = SPACE 且资源属于某个空间：仅该空间成员可访问；</li>
     *   <li>其他未知值按 PRIVATE 处理。</li>
     * </ul>
     *
     * <p>安全加固（缺陷 1.12）：无权访问时统一返回 {@code RESOURCE_NOT_FOUND}（404），
     * 与"资源不存在"响应一致，避免攻击者通过错误码差异枚举本租户所有资源 ID。</p>
     */
    private void ensureCanAccess(Resource resource) {
        Long currentUserId = currentUserIdOrNull();
        String role = currentRoleOrNull();
        if (!canAccess(resource, currentUserId, role)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    /**
     * 按签名 token 内嵌的用户 ID 做可见性校验（签名直链请求无登录上下文）。
     * userId≤0 视为游客；角色从库中实时取（管理员通过直链访问隐藏资源时仍放行）。
     */
    private void ensureCanAccessAs(Resource resource, Long sigUserId) {
        Long actingUserId = (sigUserId != null && sigUserId > 0) ? sigUserId : null;
        String role = null;
        if (actingUserId != null) {
            User u = userMapper.selectById(actingUserId);
            role = u != null ? u.getRole() : null;
        }
        if (!canAccess(resource, actingUserId, role)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    private boolean canAccess(Resource resource, Long currentUserId, String role) {
        if ("TENANT_ADMIN".equals(role) || "SUPER_ADMIN".equals(role)) {
            return true;
        }
        if (currentUserId != null && currentUserId.equals(resource.getUploaderId())) {
            return true;
        }
        // 审核流收口（2026-07-13）：未发布资源（隐藏/待审核/已驳回）仅上传者与管理员可见。
        // 此前 download/preview/getById 只查 deleted 不查 status，隐藏资源可被登录用户直链下载。
        if (resource.getStatus() == null || resource.getStatus() != 1) {
            return false;
        }
        String visibility = resource.getVisibility() == null ? "PUBLIC" : resource.getVisibility().toUpperCase();
        return switch (visibility) {
            // PUBLIC 资源对站内所有人可见，包含未登录访问的预览/封面场景
            case "PUBLIC" -> true;
            case "SPACE" -> currentUserId != null && resource.getSpaceId() != null
                    && isSpaceMember(resource.getSpaceId(), currentUserId);
            // PRIVATE 或未知值：默认仅上传者本人，已在前面 return 过
            default -> false;
        };
    }

    private boolean isSpaceMember(Long spaceId, Long userId) {
        Space space = spaceMapper.selectById(spaceId);
        if (space == null || space.getDeleted() == 1) {
            return false;
        }
        // 空间所有者一定是成员
        if (userId.equals(space.getOwnerId())) return true;
        SpaceMember member = spaceMemberMapper.selectOne(new LambdaQueryWrapper<SpaceMember>()
                .eq(SpaceMember::getSpaceId, spaceId)
                .eq(SpaceMember::getUserId, userId)
                .eq(SpaceMember::getStatus, 1)
                .last("LIMIT 1"));
        return member != null;
    }

    private static Long currentUserIdOrNull() {
        try {
            return StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String currentRoleOrNull() {
        try {
            if (!StpUtil.isLogin()) return null;
            return (String) StpUtil.getSession().get("role");
        } catch (Exception e) {
            return null;
        }
    }

    private static String readUtf8Text(InputStream is) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        is.transferTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String clipPreviewText(String content) {
        if (content == null || content.length() <= MAX_TEXT_PREVIEW_CHARS) {
            return content;
        }
        return content.substring(0, MAX_TEXT_PREVIEW_CHARS)
                + "\n\n[预览内容已截断，仅显示前 " + MAX_TEXT_PREVIEW_CHARS + " 个字符]";
    }

    private static String extractDocxText(InputStream is) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(is)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    byte[] xml = zip.readAllBytes();
                    return parseDocxDocumentXml(xml);
                }
            }
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "DOCX 文件内容不完整，无法预览");
    }

    private static String parseDocxDocumentXml(byte[] xml) {
        try {
            Document document = parseSafeXml(xml);
            NodeList paragraphs = document.getElementsByTagNameNS("*", "p");
            StringBuilder text = new StringBuilder();

            // DOCX 正文按段落和文本节点存储，预览时保留段落换行，避免直接拼接成一整行。
            for (int i = 0; i < paragraphs.getLength(); i++) {
                NodeList nodes = paragraphs.item(i).getChildNodes();
                appendDocxTextNodes(nodes, text);
                text.append('\n');
            }
            return text.toString().trim();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "DOCX 文件解析失败，无法预览");
        }
    }

    private static String extractPptxText(InputStream is) throws IOException {
        Map<String, byte[]> slides = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(is)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.startsWith("ppt/slides/slide") && name.endsWith(".xml")) {
                    slides.put(name, zip.readAllBytes());
                }
            }
        }
        if (slides.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "PPTX 文件内容不完整，无法预览");
        }

        StringBuilder text = new StringBuilder();
        List<String> names = new ArrayList<>(slides.keySet());
        names.sort(Comparator.naturalOrder());
        int index = 1;
        for (String name : names) {
            String slideText = extractXmlTextNodes(slides.get(name)).trim();
            if (!slideText.isBlank()) {
                if (!text.isEmpty()) text.append("\n\n");
                text.append("第 ").append(index).append(" 页\n").append(slideText);
            }
            index++;
        }
        return text.toString().trim();
    }

    private static String extractXlsxText(InputStream is) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(is)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if ("xl/sharedStrings.xml".equals(name)
                        || (name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml"))) {
                    entries.put(name, zip.readAllBytes());
                }
            }
        }
        List<String> sharedStrings = parseXlsxSharedStrings(entries.get("xl/sharedStrings.xml"));
        List<String> sheets = entries.keySet().stream()
                .filter(name -> name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml"))
                .sorted()
                .toList();
        if (sheets.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "XLSX 文件内容不完整，无法预览");
        }

        StringBuilder text = new StringBuilder();
        int index = 1;
        for (String sheetName : sheets) {
            String sheetText = parseXlsxSheet(entries.get(sheetName), sharedStrings).trim();
            if (!sheetText.isBlank()) {
                if (!text.isEmpty()) text.append("\n\n");
                text.append("工作表 ").append(index).append("\n").append(sheetText);
            }
            index++;
        }
        return text.toString().trim();
    }

    private static List<String> parseXlsxSharedStrings(byte[] xml) {
        if (xml == null || xml.length == 0) return List.of();
        try {
            Document document = parseSafeXml(xml);
            NodeList items = document.getElementsByTagNameNS("*", "si");
            List<String> result = new ArrayList<>();
            for (int i = 0; i < items.getLength(); i++) {
                StringBuilder itemText = new StringBuilder();
                appendDocxTextNodes(items.item(i).getChildNodes(), itemText);
                result.add(itemText.toString());
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String parseXlsxSheet(byte[] xml, List<String> sharedStrings) {
        try {
            Document document = parseSafeXml(xml);
            NodeList rows = document.getElementsByTagNameNS("*", "row");
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < rows.getLength(); i++) {
                NodeList cells = rows.item(i).getChildNodes();
                List<String> values = new ArrayList<>();
                for (int j = 0; j < cells.getLength(); j++) {
                    if (!"c".equals(cells.item(j).getLocalName())) continue;
                    String value = parseXlsxCellValue(cells.item(j), sharedStrings);
                    values.add(value);
                }
                if (!values.isEmpty()) {
                    text.append(String.join("\t", values)).append('\n');
                }
            }
            return text.toString();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "XLSX 文件解析失败，无法预览");
        }
    }

    private static String parseXlsxCellValue(org.w3c.dom.Node cell, List<String> sharedStrings) {
        String type = cell.getAttributes() != null && cell.getAttributes().getNamedItem("t") != null
                ? cell.getAttributes().getNamedItem("t").getNodeValue()
                : "";
        String rawValue = "";
        NodeList children = cell.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            String localName = children.item(i).getLocalName();
            if ("v".equals(localName) || "t".equals(localName)) {
                rawValue = children.item(i).getTextContent();
                break;
            }
            if ("is".equals(localName)) {
                StringBuilder inline = new StringBuilder();
                appendDocxTextNodes(children.item(i).getChildNodes(), inline);
                rawValue = inline.toString();
                break;
            }
        }
        if ("s".equals(type)) {
            try {
                int index = Integer.parseInt(rawValue);
                return index >= 0 && index < sharedStrings.size() ? sharedStrings.get(index) : rawValue;
            } catch (NumberFormatException ignored) {
                return rawValue;
            }
        }
        return rawValue;
    }

    private static String extractXmlTextNodes(byte[] xml) {
        try {
            Document document = parseSafeXml(xml);
            NodeList nodes = document.getElementsByTagNameNS("*", "t");
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < nodes.getLength(); i++) {
                String value = nodes.item(i).getTextContent();
                if (value != null && !value.isBlank()) {
                    if (!text.isEmpty()) text.append('\n');
                    text.append(value.trim());
                }
            }
            return text.toString();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "Office 文件解析失败，无法预览");
        }
    }

    private static Document parseSafeXml(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        // 防 XXE：禁用 DOCTYPE 与外部实体加载，避免上传文档触发 SSRF/任意文件读取
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    private static void appendDocxTextNodes(NodeList nodes, StringBuilder text) {
        for (int i = 0; i < nodes.getLength(); i++) {
            String localName = nodes.item(i).getLocalName();
            if ("t".equals(localName)) {
                text.append(nodes.item(i).getTextContent());
            } else if ("tab".equals(localName)) {
                text.append('\t');
            } else if ("br".equals(localName)) {
                text.append('\n');
            }
            appendDocxTextNodes(nodes.item(i).getChildNodes(), text);
        }
    }

    private static String md5Hex(byte[] data) {
        // @deprecated 仅供历史代码引用；新写入路径已经切换为流式 SHA-256。
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(data);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 not available", e);
        }
    }
}
