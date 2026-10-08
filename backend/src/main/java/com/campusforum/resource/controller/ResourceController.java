package com.campusforum.resource.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.campusforum.common.BusinessException;
import com.campusforum.common.ErrorCode;
import com.campusforum.common.R;
import com.campusforum.infra.preview.PreviewProperties;
import com.campusforum.infra.security.SignedUrlService;
import com.campusforum.resource.dto.ResourcePreviewVO;
import com.campusforum.resource.dto.ResourceVO;
import com.campusforum.resource.dto.UploadResourceRequest;
import com.campusforum.resource.service.ResourceService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/resources")
@RequiredArgsConstructor
public class ResourceController {

    private final ResourceService resourceService;
    private final PreviewProperties previewProperties;
    private final SignedUrlService signedUrlService;

    @PostMapping
    public R<ResourceVO> upload(
            @RequestParam("file") MultipartFile file,
            @ModelAttribute UploadResourceRequest req) {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(resourceService.upload(userId, file, req));
    }

    @GetMapping
    public R<List<ResourceVO>> list(
            @RequestParam(required = false) Long spaceId,
            @RequestParam(required = false) String college,
            @RequestParam(required = false) String major,
            @RequestParam(required = false) String course,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return R.ok(resourceService.list(spaceId, college, major, course, cursor, limit));
    }

    /** 我的上传：本人全部状态（含待审核/已驳回）的资源，追踪审核进度用。 */
    @GetMapping("/mine")
    public R<List<ResourceVO>> mine(
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int limit) {
        Long userId = StpUtil.getLoginIdAsLong();
        return R.ok(resourceService.listMine(userId, cursor, limit));
    }

    @GetMapping("/{id}")
    public R<ResourceVO> getById(@PathVariable Long id) {
        return R.ok(resourceService.getById(id));
    }

    /**
     * 申请短期下载签名。前端拿到 token 后拼到 download/preview URL 即可使用，
     * 真正的会话 token 不再随 URL 暴露到 access log/Referer/浏览器历史。
     */
    @GetMapping("/{id}/signed-url")
    public R<Map<String, Object>> signedUrl(@PathVariable Long id,
                                            @RequestParam(defaultValue = "download") String action) {
        long userId = StpUtil.getLoginIdAsLong();
        // 服务层会进行可见性校验，签名前先校验访问权限
        resourceService.getById(id);
        if (!"download".equals(action) && !"preview".equals(action)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "action 仅支持 download 或 preview");
        }
        SignedUrlService.SignedToken token = signedUrlService.sign(userId, "RESOURCE", id, action);
        return R.ok(Map.of(
                "token", token.token(),
                "expiresAt", token.expiresAtSeconds()
        ));
    }

    @GetMapping("/{id}/download")
    public void download(@PathVariable Long id,
                         @RequestParam(value = "sig", required = false) String signature,
                         HttpServletResponse response) {
        Long sigUserId = verifySignatureOrLogin(id, "download", signature);
        String fileName = resourceService.getFileName(id, sigUserId);
        InputStream is = resourceService.download(id, sigUserId);

        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded);

        try (OutputStream os = response.getOutputStream()) {
            is.transferTo(os);
        } catch (Exception e) {
            // 不向前端透出堆栈；下载流过程中失败通常是连接断开或 IO 错误
            throw new BusinessException(ErrorCode.STORAGE_ERROR.getCode(), "下载中断");
        }
    }

    @GetMapping("/{id}/preview")
    public void preview(@PathVariable Long id,
                        @RequestParam(value = "sig", required = false) String signature,
                        HttpServletResponse response) {
        Long sigUserId = verifySignatureOrLogin(id, "preview", signature);
        ResourceVO resource = resourceService.getById(id, sigUserId);
        String fileType = resource.getFileType() == null ? "" : resource.getFileType().toLowerCase();

        // 文件大小检查
        if (resource.getFileSize() > previewProperties.getMaxPreviewSize()) {
            response.setStatus(413);
            writeJson(response, "{\"code\":413,\"message\":\"文件过大，无法预览（最大 50MB）\"}");
            return;
        }

        // 判断文件类型
        String contentType = getPreviewContentType(fileType);
        if (contentType == null) {
            // 安全加固（缺陷 1.11）：Office 文档不再由后端 302 跳转到 kkfileview，
            // 而是返回 JSON 描述，由前端在浏览器内自行打开预览服务。
            // 这样后端不再发起到第三方预览服务的可达请求，避免预览服务历史漏洞被 SSRF 利用。
            if (isOfficeFile(fileType)) {
                // 该接口对游客开放（带合法 sig 直链或 PUBLIC 资源），未登录时不能直接
                // getLoginIdAsLong()（会抛 NotLoginException → 500）。游客用 0 作为匿名占位，
                // 与 WsTicketService / TenantBindingCheckInterceptor 的占位约定一致。
                // 审核流：sig 直链场景优先继承一层签名内嵌的 userId，否则上传者预览自己
                // 待审核 office 文档时，二层下载签名会以游客身份被可见性校验拒绝。
                long userId = sigUserId != null ? sigUserId
                        : (StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : 0L);
                SignedUrlService.SignedToken sig = signedUrlService.sign(userId, "RESOURCE", id, "download");
                String downloadPath = "/api/v1/resources/" + id + "/download?sig="
                        + URLEncoder.encode(sig.token(), StandardCharsets.UTF_8);
                Map<String, Object> body = new java.util.LinkedHashMap<>();
                body.put("kind", "office");
                body.put("previewServiceUrl", previewProperties.getOfficeServiceUrl());
                body.put("downloadPath", downloadPath);
                body.put("expiresAt", sig.expiresAtSeconds());
                response.setStatus(200);
                response.setContentType("application/json;charset=UTF-8");
                try {
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .writeValue(response.getWriter(), com.campusforum.common.R.ok(body));
                } catch (Exception e) {
                    response.setStatus(502);
                }
                return;
            }
            // 不支持的文件类型
            response.setStatus(415);
            writeJson(response, "{\"code\":415,\"message\":\"该文件类型不支持在线预览\"}");
            return;
        }

        // PDF 和图片：直接流式返回
        InputStream is = resourceService.preview(id, sigUserId);
        response.setContentType(contentType);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "inline");
        response.setHeader("X-Content-Type-Options", "nosniff");

        try (OutputStream os = response.getOutputStream()) {
            is.transferTo(os);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.STORAGE_ERROR.getCode(), "预览中断");
        }
    }

    @GetMapping("/{id}/preview-text")
    public R<ResourcePreviewVO> previewText(@PathVariable Long id) {
        return R.ok(resourceService.previewText(id));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        Long userId = StpUtil.getLoginIdAsLong();
        resourceService.delete(id, userId);
        return R.ok();
    }

    /**
     * 兼容老前端：若提供了 sig 参数，则用签名校验放行（适合 &lt;a href&gt;/&lt;img src&gt; 直链场景，
     * 此时浏览器不会附带 Authorization 头）；否则要求登录态 + 资源访问权限。
     *
     * <p>安全加固：签名失败时统一抛 {@code RESOURCE_NOT_FOUND}（404），与无权访问场景一致，
     * 避免攻击者通过响应区分"签名过期" vs "资源不存在"。</p>
     *
     * @return 签名直链场景返回 token 内嵌的签发用户 ID（审核流按此做可见性校验，
     *         否则上传者打开自己待审核资源的直链会被按游客拒绝）；登录态场景返回 null，
     *         由 service 层按 StpUtil 上下文校验。
     */
    private Long verifySignatureOrLogin(long resourceId, String action, String signature) {
        if (signature != null && !signature.isBlank()) {
            SignedUrlService.Verified verified = signedUrlService.verify(signature, "RESOURCE", resourceId, action);
            if (verified == null) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            }
            return verified.userId();
        }
        if (!StpUtil.isLogin()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        // 已登录路径仍然需要走 service 的可见性校验，由 download/preview 内部触发
        return null;
    }

    private String getPreviewContentType(String fileType) {
        return switch (fileType) {
            case "pdf" -> "application/pdf";
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            case "mp4" -> "video/mp4";
            case "webm" -> "video/webm";
            case "mov" -> "video/quicktime";
            case "avi" -> "video/x-msvideo";
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "m4a" -> "audio/mp4";
            case "ogg" -> "audio/ogg";
            case "md", "markdown" -> "text/markdown; charset=UTF-8";
            default -> null;
        };
    }

    private boolean isOfficeFile(String fileType) {
        return switch (fileType) {
            case "doc", "docx", "ppt", "pptx", "xls", "xlsx" -> true;
            default -> false;
        };
    }

    private void writeJson(HttpServletResponse response, String json) {
        response.setContentType("application/json;charset=UTF-8");
        try {
            response.getWriter().write(json);
        } catch (Exception ignored) {}
    }
}
