package com.campusforum.infra;

import com.campusforum.common.BusinessException;
import com.campusforum.common.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 本地磁盘存储实现，仅供 dev / 单元测试使用，prod 必须切到 OSS。
 *
 * <p>对应 bugfix.md 漏洞 6 / 15 的接口扩展（T4.3）：</p>
 * <ul>
 *   <li>实现新的 4 参 {@code upload}：当 {@code size ≥ 0} 时校验
 *       {@code Files.size(targetFile) == size}，不一致则删除文件并抛 {@link BusinessException}；
 *       {@code size = -1}（旧调用方过渡期）允许跳过 size 校验，写入完成即返回。</li>
 *   <li>实现 {@code issuePublicGetUrl}：返回站内代理路径
 *       {@code /api/v1/local-storage/<storageKey>}。当前没有真正的 controller 处理该路径，
 *       仅供 dev 调试使用，避免对象存储模式下 {@code UserController} 拼接出 404 的硬编码 URL。
 *       prod 部署若仍走 local 模式将形成"头像无法访问"，符合 design.md 主题 4 的明确权衡。</li>
 * </ul>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "storage.type", havingValue = "local")
public class LocalStorageService implements StorageService {

    private final Path basePath;

    public LocalStorageService(@Value("${storage.local.path:./uploads}") String path) {
        this.basePath = Paths.get(path).toAbsolutePath().normalize();
        try {
            Files.createDirectories(basePath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create storage directory: " + basePath, e);
        }
        log.info("LocalStorageService initialized at {}", basePath);
    }

    @Override
    public String upload(InputStream inputStream, String originalName, String contentType, long size) {
        String ext = "";
        if (originalName != null) {
            int dot = originalName.lastIndexOf('.');
            if (dot >= 0) {
                ext = originalName.substring(dot);
            }
        }

        String dateDir = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        String filename = UUID.randomUUID() + ext;
        String storageKey = dateDir + "/" + filename;

        try {
            Path targetDir = basePath.resolve(dateDir);
            Files.createDirectories(targetDir);
            Path targetFile = targetDir.resolve(filename);
            Files.copy(inputStream, targetFile, StandardCopyOption.REPLACE_EXISTING);

            // size ≥ 0 时进行字节级一致性校验：写入文件大小必须严格匹配调用方声明的 size
            // size = -1 表示旧调用方过渡期，允许跳过校验
            if (size >= 0) {
                long actual = Files.size(targetFile);
                if (actual != size) {
                    log.error("Local 存储字节数不匹配 expected={}, actual={}, key={}",
                            size, actual, storageKey);
                    try {
                        Files.deleteIfExists(targetFile);
                    } catch (IOException deleteEx) {
                        log.warn("回滚截断文件失败 key={}: {}", storageKey, deleteEx.getMessage());
                    }
                    throw new BusinessException(ErrorCode.STORAGE_ERROR);
                }
            }

            log.info("File stored: {} ({} bytes)", storageKey, size);
            return storageKey;
        } catch (BusinessException e) {
            // 业务异常透传，避免被下面的 IOException 捕获重新包装
            throw e;
        } catch (IOException e) {
            log.error("Failed to store file", e);
            throw new BusinessException(ErrorCode.STORAGE_ERROR);
        }
    }

    @Override
    public InputStream download(String storageKey) {
        try {
            Path file = basePath.resolve(storageKey).normalize();
            if (!file.startsWith(basePath)) {
                throw new BusinessException(ErrorCode.FORBIDDEN);
            }
            return Files.newInputStream(file);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            Path file = basePath.resolve(storageKey).normalize();
            if (!file.startsWith(basePath)) {
                return;
            }
            Files.deleteIfExists(file);
            log.info("File deleted: {}", storageKey);
        } catch (IOException e) {
            log.warn("Failed to delete file: {}", storageKey, e);
        }
    }

    @Override
    public String issuePublicGetUrl(String storageKey) {
        // TODO(prod): local 模式仅供 dev / 单元测试使用；prod 必须切换到 OSS，
        //  否则该 URL 没有真实 controller 处理（WebMvcConfig 已显式删除 /uploads/** 静态映射）。
        //  设计上不在 local 模式接入 SignedUrlService 是为了避免给生产部署留下"看似可用"的捷径——
        //  让 prod 部署在跑通整个上传链路时立刻发现 404，反向倒逼运维切到对象存储。
        return "/api/v1/local-storage/" + storageKey;
    }
}
