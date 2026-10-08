package com.campusforum.security.upload;

import com.campusforum.infra.metrics.SecurityMetrics;
import com.campusforum.infra.security.MimeMismatchException;
import com.campusforum.infra.security.MimeTypeValidator;
import com.campusforum.infra.security.SecurityProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 任务 TPBT.6（部分） / design.md Property 7：MIME 校验"未注册扩展名拒绝"属性。
 *
 * <p>原任务还包含会话状态机部分；会话踢下线已由 T3.2 单测覆盖，此处仅落地
 * "未注册扩展名 → 必拒"这条核心安全不变量的属性测试，避免重复。</p>
 *
 * <p>本测试纯单元，不依赖 Spring / DB；用真实 Tika 检测器嗅探随机字节序列，
 * 但断言始终聚焦在"扩展名未注册时 validate 抛 {@link MimeMismatchException}"
 * 这一最小保证，不做 detected MIME 的具体内容断言。</p>
 */
class MimeBlocklistPropertyTest {

    /** 已注册的扩展名集合（与 MimeTypeValidator.EXT_TO_MIMES keys 保持同步）。 */
    private static final Set<String> REGISTERED_EXTS = Set.of(
            "jpg", "jpeg", "png", "gif", "webp", "bmp",
            "pdf",
            "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "zip",
            "txt", "log", "csv", "json", "xml", "yml", "yaml",
            "sql", "java", "py", "js", "jsx", "ts", "tsx", "vue",
            "css", "scss", "html", "htm",
            "mp4", "webm", "mov", "avi", "mp3", "wav", "m4a", "ogg",
            "md", "markdown"
    );

    private MimeTypeValidator buildValidator() {
        SecurityProperties props = new SecurityProperties();
        props.getUpload().setRealMimeCheck(true);
        SecurityMetrics metrics = new SecurityMetrics(new SimpleMeterRegistry());
        return new MimeTypeValidator(props, metrics);
    }

    /** 生成器：长度 1..6 的小写字母扩展名，过滤掉已注册的若干扩展名。 */
    @Provide
    Arbitrary<String> unregisteredExtensions() {
        return Arbitraries.strings()
                .ofMinLength(1).ofMaxLength(6)
                .alpha()
                .filter(s -> !REGISTERED_EXTS.contains(s.toLowerCase()))
                .map(String::toLowerCase);
    }

    @Property(tries = 100)
    void unregisteredExtension_alwaysRejected(@ForAll("unregisteredExtensions") String ext,
                                              @ForAll byte[] randomBytes) {
        if (randomBytes.length == 0) randomBytes = new byte[]{0};
        MimeTypeValidator validator = buildValidator();
        MockMultipartFile file = new MockMultipartFile(
                "file", "anything." + ext, "application/octet-stream", randomBytes);

        // 任意未注册扩展名都必须直接抛错（漏洞 24 严格化策略）
        assertThatThrownBy(() -> validator.validate(file, ext))
                .as("未注册扩展名 .%s 必须被 MimeMismatchException 拒绝", ext)
                .isInstanceOf(MimeMismatchException.class);
    }

    @Test
    void blankExtension_rejected() {
        MimeTypeValidator v = buildValidator();
        MockMultipartFile file = new MockMultipartFile(
                "file", "x", "application/octet-stream", new byte[]{1, 2, 3});
        assertThatThrownBy(() -> v.validate(file, ""))
                .isInstanceOf(MimeMismatchException.class);
        assertThatThrownBy(() -> v.validate(file, null))
                .isInstanceOf(MimeMismatchException.class);
        assertThatThrownBy(() -> v.validate(file, "   "))
                .isInstanceOf(MimeMismatchException.class);
    }

    @Test
    void phpDisguisedAsPng_rejected() {
        // 黑名单优先于白名单：PHP magic bytes 即使声称是 .png 也必拒
        byte[] phpBytes = "<?php echo \"x\"; ?>".getBytes();
        MimeTypeValidator v = buildValidator();
        MockMultipartFile file = new MockMultipartFile(
                "file", "shell.png", "image/png", phpBytes);
        assertThatThrownBy(() -> v.validate(file, "png"))
                .isInstanceOf(MimeMismatchException.class);
    }

    @Test
    void realMimeCheckDisabled_skipsAll() {
        // real-mime-check=false 时跳过校验（开发联调便利）
        SecurityProperties props = new SecurityProperties();
        props.getUpload().setRealMimeCheck(false);
        SecurityMetrics metrics = new SecurityMetrics(new SimpleMeterRegistry());
        MimeTypeValidator v = new MimeTypeValidator(props, metrics);

        MockMultipartFile file = new MockMultipartFile(
                "file", "any.exe", "application/x-msdownload", new byte[]{0x4D, 0x5A});
        // 不抛错 — 跳过整段校验
        v.validate(file, "exe");
    }
}
