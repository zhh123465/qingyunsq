package com.campusforum.infra.security;

import com.campusforum.infra.metrics.SecurityMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.detect.Detector;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 上传文件 MIME 类型校验器（对应 bugfix.md 漏洞 24，T4.5 严格化）。
 *
 * <p>原本仅按 {@code originalFilename} 后缀判断文件类型，攻击者可以将 PHP/HTML/EICAR
 * 等危险文件改名为 {@code .png} 上传绕过白名单。本组件用 Apache Tika 嗅探真实 MIME，
 * 与扩展名做交叉验证，不一致直接拒绝。</p>
 *
 * <p>T4.5 严格化策略（bugfix.md 漏洞 24 加固）：</p>
 * <ol>
 *   <li><b>未注册扩展名直接拒绝</b>（替代原"静默放行"策略）：早期实现对未在
 *       {@link #EXT_TO_MIMES} 中显式声明的扩展名直接 {@code return} 跳过校验，
 *       让 {@code .txt} / {@code .ini} 等任意类型可绕过 Tika 嗅探。改为抛
 *       {@link MimeMismatchException}，新增扩展名时强制运维同步白名单。</li>
 *   <li><b>黑名单优先于白名单</b>：先按 {@link #BLOCKED_MIMES} 与
 *       {@link SecurityProperties.Upload#getBlockedMimeTypes()} 双重比对，
 *       只要 Tika 实测出的真实 MIME 命中即拒绝（哪怕该 MIME 也意外出现在
 *       某个扩展名的允许集合里）。代码内置 {@link #BLOCKED_MIMES} 作为兜底，
 *       避免运维清空配置后失去防御。</li>
 *   <li><b>不再向 Tika 传文件名 hint</b>：删除原来的
 *       {@code metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, originalFilename)}，
 *       仅依赖 magic bytes 检测，避免恶意 originalFilename 误导嗅探结果
 *       （例如把 PHP 木马命名为 {@code shell.png}，文件名 hint 会让 Tika 倾向于
 *       返回 {@code image/png} 而非真实的 {@code application/x-php}）。</li>
 *   <li><b>命中黑名单 / 不一致都埋点</b>：调用 {@link SecurityMetrics#mimeMismatch(String, String)}
 *       让运维通过 Prometheus 监控"伪造扩展名"的尝试速率（漏洞 32）。</li>
 * </ol>
 *
 * <p>设计权衡：</p>
 * <ul>
 *   <li>{@code .docx/.xlsx/.pptx} 实际是 ZIP 容器，Tika 检测出的 MIME 形如
 *       {@code application/x-tika-ooxml} / {@code application/zip}，因此扩展名 → MIME
 *       的映射使用集合表达 "可接受多种"；</li>
 *   <li>原来的 {@code allowed == null → 跳过校验} 路径已被改为抛异常，
 *       因此调用方必须保证 declaredExt 已经在更早的扩展名白名单层（如
 *       {@code ResourceService} 的 allowed-extensions 配置）做过校验。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MimeTypeValidator {

    private final SecurityProperties securityProperties;

    /** 安全监控埋点（漏洞 32）：黑名单 / 不一致都累加 mime_mismatch_total。 */
    private final SecurityMetrics securityMetrics;

    private final Detector detector = TikaConfig.getDefaultConfig().getDetector();

    /** 扩展名 → 允许的 MIME 集合。未注册扩展名（map 中不存在）将被直接拒绝。 */
    private static final Map<String, Set<String>> EXT_TO_MIMES = Map.ofEntries(
            // 图片
            Map.entry("jpg",  Set.of("image/jpeg")),
            Map.entry("jpeg", Set.of("image/jpeg")),
            Map.entry("png",  Set.of("image/png")),
            Map.entry("gif",  Set.of("image/gif")),
            Map.entry("webp", Set.of("image/webp")),
            Map.entry("bmp",  Set.of("image/bmp", "image/x-ms-bmp")),
            // PDF
            Map.entry("pdf",  Set.of("application/pdf")),
            // OOXML（实际是 ZIP）
            Map.entry("docx", Set.of(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/x-tika-ooxml",
                    "application/zip")),
            Map.entry("xlsx", Set.of(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/x-tika-ooxml",
                    "application/zip")),
            Map.entry("pptx", Set.of(
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "application/x-tika-ooxml",
                    "application/zip")),
            // Legacy Office: Tika often reports OLE2 compound document for doc/xls/ppt.
            Map.entry("doc", Set.of(
                    "application/msword",
                    "application/x-tika-msoffice",
                    "application/x-ole-storage",
                    "application/octet-stream")),
            Map.entry("xls", Set.of(
                    "application/vnd.ms-excel",
                    "application/x-tika-msoffice",
                    "application/x-ole-storage",
                    "application/octet-stream")),
            Map.entry("ppt", Set.of(
                    "application/vnd.ms-powerpoint",
                    "application/x-tika-msoffice",
                    "application/x-ole-storage",
                    "application/octet-stream")),
            // Text / code files
            Map.entry("txt",  Set.of("text/plain", "application/octet-stream")),
            Map.entry("log",  Set.of("text/plain", "application/octet-stream")),
            Map.entry("csv",  Set.of("text/plain", "text/csv", "application/csv", "application/vnd.ms-excel")),
            Map.entry("json", Set.of("application/json", "text/plain")),
            Map.entry("xml",  Set.of("application/xml", "text/xml", "text/plain")),
            Map.entry("yml",  Set.of("text/plain", "application/x-yaml", "text/x-yaml")),
            Map.entry("yaml", Set.of("text/plain", "application/x-yaml", "text/x-yaml")),
            Map.entry("sql",  Set.of("text/plain", "text/x-sql", "application/sql")),
            Map.entry("java", Set.of("text/plain", "text/x-java-source")),
            Map.entry("py",   Set.of("text/plain", "text/x-python")),
            Map.entry("js",   Set.of("text/plain", "text/javascript", "application/javascript", "application/x-javascript")),
            Map.entry("jsx",  Set.of("text/plain", "text/javascript", "application/javascript", "application/x-javascript")),
            Map.entry("ts",   Set.of("text/plain", "text/x-typescript", "application/x-typescript")),
            Map.entry("tsx",  Set.of("text/plain", "text/x-typescript", "application/x-typescript")),
            Map.entry("vue",  Set.of("text/plain", "text/html", "application/xml", "text/xml")),
            Map.entry("css",  Set.of("text/plain", "text/css")),
            Map.entry("scss", Set.of("text/plain", "text/x-scss")),
            Map.entry("html", Set.of("text/plain", "text/html")),
            Map.entry("htm",  Set.of("text/plain", "text/html")),
            // Browser-previewable media
            Map.entry("mp4",  Set.of("video/mp4", "application/mp4", "application/octet-stream")),
            Map.entry("webm", Set.of("video/webm", "application/octet-stream")),
            Map.entry("mov",  Set.of("video/quicktime", "application/octet-stream")),
            Map.entry("avi",  Set.of("video/x-msvideo", "video/avi", "application/octet-stream")),
            Map.entry("mp3",  Set.of("audio/mpeg", "audio/mp3", "application/octet-stream")),
            Map.entry("wav",  Set.of("audio/wav", "audio/x-wav", "audio/vnd.wave", "application/octet-stream")),
            Map.entry("m4a",  Set.of("audio/mp4", "audio/x-m4a", "application/octet-stream")),
            Map.entry("ogg",  Set.of("audio/ogg", "application/ogg", "video/ogg")),
            // 压缩包
            Map.entry("zip", Set.of("application/zip", "application/x-zip-compressed")),
            // Markdown / 纯文本
            Map.entry("md",       Set.of("text/plain", "text/x-markdown", "text/markdown")),
            Map.entry("markdown", Set.of("text/plain", "text/x-markdown", "text/markdown"))
    );

    /**
     * 全局 MIME 黑名单（代码内置兜底）。
     *
     * <p>覆盖常见可执行 / 脚本 / 宏类型；与
     * {@link SecurityProperties.Upload#getBlockedMimeTypes()} 配置项语义一致，
     * 但作为代码常量存在，避免运维误清空 application.yml 后丢失防御能力。</p>
     *
     * <p>命中即拒绝，<b>优先级高于白名单</b>。例如即使将来某个扩展名意外把
     * {@code application/x-php} 加入允许集合，本黑名单仍会先一步拒绝。</p>
     */
    private static final Set<String> BLOCKED_MIMES = Set.of(
            "application/x-php",
            "application/x-msdownload",
            "application/x-msdos-program",
            "application/x-bat",
            "application/x-sh",
            "text/x-php",
            "text/x-script.python"
    );

    /**
     * 校验上传文件的真实 MIME 与扩展名是否一致。
     *
     * <p>当 {@code security.upload.real-mime-check=false} 时跳过校验（开发联调便利）。
     * 其余路径见类注释中描述的"严格化策略"。</p>
     *
     * @param file        上传的 MultipartFile（必须可重复读取 inputStream）
     * @param declaredExt 调用方声明的扩展名（小写，无 . 前缀），例如 {@code "png"}
     * @throws MimeMismatchException 当扩展名未注册、命中黑名单或与实测 MIME 不一致时抛出
     */
    public void validate(MultipartFile file, String declaredExt) {
        if (!securityProperties.getUpload().isRealMimeCheck()) return;
        boolean allowAny = securityProperties.getUpload().isAllowAnyExtension();
        if (declaredExt == null || declaredExt.isBlank()) {
            if (allowAny) {
                // 审核流（2026-07-13）：无扩展名文件放行进待审队列，由人工审核甄别
                return;
            }
            // 严格化（漏洞 24）：早期对空扩展名 return 跳过；现在直接拒绝
            throw new MimeMismatchException("不支持的扩展名：(空)");
        }
        String ext = declaredExt.toLowerCase(Locale.ROOT);
        Set<String> allowed = EXT_TO_MIMES.get(ext);
        if (allowed == null) {
            if (allowAny) {
                // 审核流（2026-07-13）：未注册扩展名（exe/apk/zip/jar 等）放行进待审队列。
                // 刻意不做 MIME 黑名单检查——黑名单含 application/x-msdownload 等可执行类型，
                // 而"支持上传可运行程序"正是业务需求；甄别责任转移给人工审核，
                // 且这些文件只能以 attachment 下载、无在线预览/服务端执行面。
                // 已注册扩展名（下方）仍走严格交叉验证，防"PHP 改名 .png"伪装。
                return;
            }
            // 严格化（漏洞 24）：未注册扩展名直接拒绝（替代原"静默放行"策略）。
            // 这样一来新增允许扩展名时，必须同步更新 EXT_TO_MIMES 否则不会通过校验。
            throw new MimeMismatchException("不支持的扩展名：." + ext);
        }

        String detected;
        try (InputStream raw = file.getInputStream();
             TikaInputStream tis = TikaInputStream.get(raw)) {
            // 严格化（漏洞 24）：不再调用 metadata.set(RESOURCE_NAME_KEY, originalFilename)。
            // 文件名 hint 会让 Tika 偏向"按扩展名推测的 MIME"，恶意 originalFilename
            // 可能掩盖真实 magic bytes 类型，因此这里只用 magic bytes 嗅探。
            Metadata meta = new Metadata();
            detected = detector.detect(tis, meta).toString().toLowerCase(Locale.ROOT);
        } catch (IOException e) {
            log.warn("MimeTypeValidator failed to read upload stream: {}", e.getMessage());
            throw new MimeMismatchException("无法识别文件类型");
        }

        // 黑名单优先于白名单（漏洞 24）：代码常量 + 配置项双重比对
        if (isBlocked(detected)) {
            log.warn("MIME blocked: ext=.{}, detected={}", ext, detected);
            securityMetrics.mimeMismatch(ext, detected);
            throw new MimeMismatchException("禁止上传 " + detected + " 类型文件");
        }

        if (!allowed.contains(detected)) {
            log.warn("MIME mismatch: ext=.{}, detected={}", ext, detected);
            securityMetrics.mimeMismatch(ext, detected);
            throw new MimeMismatchException(
                    "文件扩展名 ." + ext + " 与实际类型 " + detected + " 不一致");
        }
    }

    /**
     * 黑名单命中判定：代码常量 {@link #BLOCKED_MIMES} 与配置项
     * {@link SecurityProperties.Upload#getBlockedMimeTypes()} 取并集。
     *
     * <p>配置项允许运维通过 application.yml / ENV 追加项目特定的恶意 MIME，
     * 代码常量则作为兜底：即使运维误清空配置项，防御也不会失效。</p>
     */
    private boolean isBlocked(String detected) {
        if (detected == null) return false;
        if (BLOCKED_MIMES.contains(detected)) return true;
        var configured = securityProperties.getUpload().getBlockedMimeTypes();
        if (configured == null || configured.isEmpty()) return false;
        for (String item : configured) {
            if (item != null && detected.equalsIgnoreCase(item)) return true;
        }
        return false;
    }
}
