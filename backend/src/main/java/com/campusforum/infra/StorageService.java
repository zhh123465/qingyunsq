package com.campusforum.infra;

import java.io.InputStream;

/**
 * 对象存储抽象接口（bugfix.md 漏洞 6 / 15 / 24 修复重点）。
 *
 * <p>当前实现仅有两种：{@link OssStorageService}（阿里云 OSS，生产默认）与
 * {@link LocalStorageService}（本地磁盘，仅 dev / 测试）。早期的 MinIO 实现已移除，
 * 下文涉及 size 校验的约定对 OSS / Local 同样适用。</p>
 *
 * <p>本接口在 T4.1 阶段做了破坏性扩展：</p>
 * <ul>
 *   <li>新增 4 参 {@link #upload(InputStream, String, String, long)}：调用方必须显式传入文件总字节数，
 *       彻底杜绝早期版本误用 {@code inputStream.available()} 估算 size 导致大文件被截断的问题（漏洞 6）。</li>
 *   <li>新增 {@link #issuePublicGetUrl(String)}：颁发短期公开下载 URL，用于头像 / 封面等
 *       公开访问场景。OSS 走 presigned GET；Local 走站内代理路径（仅 dev / 测试用）。
 *       该方法替代了早期 {@code UserController#uploadProfileAsset} 自行拼接 {@code /uploads/<key>}
 *       的硬编码逻辑（漏洞 15：local 模式头像 404、对象存储模式直接返回 storageKey 字面量）。</li>
 *   <li>旧 3 参 {@link #upload(InputStream, String, String)} 被标记 {@code @Deprecated(forRemoval = true)}：
 *       仅作为兼容 shim，default 实现以 {@code -1} 为 size 透传到 4 参版本，
 *       Local / OSS 实现将 {@code -1} 视为"跳过 size 校验"。新代码必须使用 4 参版本。</li>
 * </ul>
 */
public interface StorageService {

    /**
     * 上传文件到对象存储（推荐签名）。
     *
     * <p>所有新代码必须使用本方法并传入显式的 {@code size}（来自 {@code MultipartFile#getSize()}），
     * 严禁通过 {@code InputStream#available()} 估算大小——后者只反映 buffer 内已就绪字节数，
     * 通常等于 {@code BufferedInputStream} 的默认 buffer (~8KB)，并不是文件总长。</p>
     *
     * @param inputStream  上传文件的字节流，本接口不负责关闭，由调用方在外层 try-with-resources 处理
     * @param originalName 原始文件名，用于提取扩展名作为对象 key 后缀
     * @param contentType  HTTP Content-Type，缺省时由实现回退为 {@code application/octet-stream}
     * @param size         文件总字节数（来自 {@code MultipartFile#getSize()}）。
     *                     Local / OSS 实现将 {@code size < 0} 视为"跳过 size 校验"
     * @return 存储 key（可用于后续 {@link #download(String)} / {@link #delete(String)} /
     *         {@link #issuePublicGetUrl(String)}）
     */
    String upload(InputStream inputStream, String originalName, String contentType, long size);

    /**
     * 兼容旧 3 参调用方的 default 实现，内部以 {@code -1L} 透传到 4 参版本。
     *
     * <p>新代码必须直接调用 4 参版本；本兼容方法以 {@code -1} 透传 size，
     * Local / OSS 实现将其视为"跳过 size 校验"，不应在新上传路径中使用
     * （bugfix.md 漏洞 6：避免"未知 size 流式上传"导致大文件被截断）。</p>
     */
    @Deprecated(forRemoval = true)
    default String upload(InputStream inputStream, String originalName, String contentType) {
        return upload(inputStream, originalName, contentType, -1L);
    }

    /**
     * 下载对象内容字节流，调用方负责关闭。
     */
    InputStream download(String storageKey);

    /**
     * 删除对象。失败仅记日志，不抛异常，避免影响主业务回滚。
     */
    void delete(String storageKey);

    /**
     * 颁发短期公开访问 URL，主要用于头像 / 封面等"无需登录即可短期访问"的资源。
     *
     * <p>实现约定：</p>
     * <ul>
     *   <li>OSS：调用 SDK 的 presigned GET，TTL 取
     *       {@code SecurityProperties.signedUrlTtlSeconds × 5}，比下载场景宽松一档；</li>
     *   <li>Local：返回站内代理路径 {@code /api/v1/local-storage/<storageKey>}，
     *       仅供 dev / 测试环境使用；prod 必须切到 OSS。</li>
     * </ul>
     */
    String issuePublicGetUrl(String storageKey);
}
