package com.campusforum.security.upload;

import com.campusforum.infra.metrics.SecurityMetrics;
import com.campusforum.infra.security.MimeMismatchException;
import com.campusforum.infra.security.MimeTypeValidator;
import com.campusforum.infra.security.SecurityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 单元测试：{@link MimeTypeValidator} 严格化（黑名单 + 拒绝未注册扩展名 + 不传 Tika 文件名 hint）。
 *
 * <p>对应任务 T4.5，关联 bugfix.md 漏洞 24（MimeTypeValidator 静默放行未注册扩展名 +
 * Tika 文件名 hint 干扰判断）。</p>
 *
 * <p>测试策略：</p>
 * <ul>
 *   <li>不启动 Spring 容器，使用 Mockito 直接构造 {@link MimeTypeValidator}；</li>
 *   <li>通过 {@link MockMultipartFile} 提供真实 magic bytes 内容（{@code <?php}、
 *       {@code MZ...} 等），让真实的 Tika 检测器嗅探 — 这样 Tika 文件名 hint
 *       是否被使用会反映在 detected 结果上；</li>
 *   <li>断言关键路径：黑名单命中、未注册扩展名、Tika 不依赖文件名 hint、监控埋点调用。</li>
 * </ul>
 */
class MimeTypeValidatorBlocklistTest {

    private MimeTypeValidator validator;
    private SecurityProperties securityProperties;
    private SecurityMetrics securityMetrics;

    @BeforeEach
    void setUp() {
        securityProperties = mock(SecurityProperties.class);
        securityMetrics = mock(SecurityMetrics.class);

        // 构造 SecurityProperties.Upload，开启真实 MIME 校验，黑名单留空（依赖代码内置 BLOCKED_MIMES）
        SecurityProperties.Upload upload = new SecurityProperties.Upload();
        upload.setRealMimeCheck(true);
        upload.setBlockedMimeTypes(List.of());
        when(securityProperties.getUpload()).thenReturn(upload);

        validator = new MimeTypeValidator(securityProperties, securityMetrics);
    }

    @Test
    @DisplayName("PHP 文件改名为 .png 上传：Tika 嗅探出 PHP 类型（application/x-php 或 text/x-php），黑名单优先拒绝 + 监控埋点")
    void php_renamed_to_png_isRejected() {
        // PHP 木马最简形态：以 <?php 起始即可被 Tika 识别为 PHP 类型
        // 注意：Tika 对裸 <?php ... ?> 内联代码通常返回 text/x-php；
        //      对带完整 PHP-CGI 元数据的文件可能返回 application/x-php。
        // 两者都在 BLOCKED_MIMES 中，黑名单分支均会拒绝。
        byte[] phpBytes = "<?php echo \"hello\"; ?>".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "shell.png", "image/png", phpBytes);

        assertThatThrownBy(() -> validator.validate(file, "png"))
                .isInstanceOf(MimeMismatchException.class)
                .hasMessageContaining("禁止上传");

        // 黑名单命中必须埋点：ext=png, detected ∈ {application/x-php, text/x-php}
        org.mockito.ArgumentCaptor<String> detectedCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(securityMetrics, times(1)).mimeMismatch(eq("png"), detectedCaptor.capture());
        assertThat(detectedCaptor.getValue())
                .as("Tika 应嗅探出 PHP 类型而非 image/png（证明文件名 hint 未被使用）")
                .isIn("application/x-php", "text/x-php");
    }

    @Test
    @DisplayName("未注册扩展名：直接抛 MimeMismatchException（替代原静默放行策略）")
    void unregistered_ext_isRejected() {
        // 内容为合法纯文本，但 .abc 扩展名未在 EXT_TO_MIMES 中注册
        byte[] txtBytes = "plain text content".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "note.abc", "text/plain", txtBytes);

        assertThatThrownBy(() -> validator.validate(file, "abc"))
                .isInstanceOf(MimeMismatchException.class)
                .hasMessageContaining("不支持的扩展名");

        // 未走到 Tika 嗅探阶段，因此不应有 mimeMismatch 埋点
        verify(securityMetrics, never()).mimeMismatch(eq("abc"), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("Tika 不被文件名 hint 误导：Windows PE 字节伪装成 image.png 仍被检测为 x-msdownload")
    void tika_doesNotUseFilenameHint() {
        // Windows PE / MZ 头部 + 一些填充字节，让 Tika 通过 magic bytes 识别为 application/x-msdownload
        // MZ = 0x4D 0x5A，是 DOS / Windows 可执行文件的签名头
        byte[] peBytes = new byte[64];
        peBytes[0] = 0x4D; // 'M'
        peBytes[1] = 0x5A; // 'Z'
        // 其余字节保持为 0，Tika 仍能识别 MZ 头
        MockMultipartFile file = new MockMultipartFile(
                "file", "image.png", "image/png", peBytes);

        // 关键断言：尽管 originalFilename 是 image.png 且 contentType 是 image/png，
        // Tika 因为我们不再传 RESOURCE_NAME_KEY 文件名 hint，必须按 magic bytes 嗅出可执行类型。
        // 该可执行类型属于黑名单，因此抛 MimeMismatchException("禁止上传 ...")。
        assertThatThrownBy(() -> validator.validate(file, "png"))
                .isInstanceOf(MimeMismatchException.class)
                .hasMessageContaining("禁止上传");

        // 进一步断言：埋点的 detected 字段必须是黑名单中的可执行类型而非 image/png；
        // 这正是"Tika 不被文件名 hint 误导"的强证据。
        org.mockito.ArgumentCaptor<String> detectedCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(securityMetrics, times(1)).mimeMismatch(eq("png"), detectedCaptor.capture());
        String detected = detectedCaptor.getValue();
        assertThat(detected)
                .as("Tika 必须按 magic bytes 嗅探，detected 不应被文件名 hint 拉成 image/png")
                .isNotEqualTo("image/png");
        // MZ 头通常会被 Tika 识别为 application/x-msdownload 或 application/x-dosexec
        // 我们只断言"是已知黑名单/可执行类型"即可
        assertThat(detected).contains("ms");
    }

    @Test
    @DisplayName("MIME 不一致（非黑名单）：抛异常 + 监控埋点 mimeMismatch(ext, detected)")
    void metrics_recorded_on_mismatch() {
        // 构造一个 PDF 字节流（%PDF-1.4 头），但声明扩展名为 .png
        // Tika 会嗅出 application/pdf，与 png 的允许集合 {image/png} 不匹配。
        // application/pdf 不在黑名单内，因此走"白名单不一致"分支。
        byte[] pdfBytes = new byte[] {
                '%', 'P', 'D', 'F', '-', '1', '.', '4', '\n',
                '%', (byte) 0xE2, (byte) 0xE3, (byte) 0xCF, (byte) 0xD3, '\n'
        };
        MockMultipartFile file = new MockMultipartFile(
                "file", "trick.png", "image/png", pdfBytes);

        assertThatThrownBy(() -> validator.validate(file, "png"))
                .isInstanceOf(MimeMismatchException.class);

        // 必须埋点：ext=png, detected=application/pdf
        verify(securityMetrics, times(1)).mimeMismatch(eq("png"), eq("application/pdf"));
    }

    @Test
    @DisplayName("real-mime-check 关闭：不做任何嗅探，未注册扩展名也不拒绝（开发联调便利）")
    void realMimeCheck_disabled_skipsAll() {
        SecurityProperties.Upload upload = new SecurityProperties.Upload();
        upload.setRealMimeCheck(false);
        when(securityProperties.getUpload()).thenReturn(upload);

        // 即便扩展名未注册（.txt）+ 内容不合法，real-mime-check=false 时整体跳过
        MockMultipartFile file = new MockMultipartFile(
                "file", "note.txt", "text/plain", "anything".getBytes());

        // 不应抛任何异常
        validator.validate(file, "txt");

        verify(securityMetrics, never()).mimeMismatch(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }
}
