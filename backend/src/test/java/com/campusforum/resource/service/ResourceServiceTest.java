package com.campusforum.resource.service;

import com.campusforum.common.BusinessException;
import com.campusforum.resource.dto.ResourceVO;
import com.campusforum.resource.dto.UploadResourceRequest;
import com.campusforum.tenant.TenantContext;
import com.campusforum.user.dto.RegisterRequest;
import com.campusforum.user.dto.UserVO;
import com.campusforum.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.campusforum.test.EmailCodeTestUtils.prepareRegisterCode;
import static org.assertj.core.api.Assertions.*;

/**
 * 资源服务测试。
 *
 * <p>用 {@link TestPropertySource} 只覆盖 MIME 真实探测开关，
 * 让测试用例采用纯文本字节流伪装为 PDF/DOCX/ZIP 等格式（这是早期写测试时的简化）。
 * T4.5 严格校验在生产 profile 下会拒绝这种用例；这里关闭 real-mime-check
 * 跳过 Tika 探测，但保留其他 dev profile 配置（含连接虚拟机的数据库 / Redis）。</p>
 */
@SpringBootTest
@TestPropertySource(properties = "security.upload.real-mime-check=false")
class ResourceServiceTest {

    @Autowired
    private ResourceService resourceService;

    @Autowired
    private UserService userService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private Long userId1;
    private Long userId2;
    private long ts;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(1L);
        ts = System.currentTimeMillis();
        RegisterRequest req = new RegisterRequest();
        req.setEmail("res-user1-" + ts + "@test.com");
        req.setPassword("Test123456");
        req.setNickname("资源上传者");
        prepareRegisterCode(stringRedisTemplate, req);
        userId1 = userService.register(req).getId();

        RegisterRequest req2 = new RegisterRequest();
        req2.setEmail("res-user2-" + ts + "@test.com");
        req2.setPassword("Test123456");
        req2.setNickname("资源下载者");
        prepareRegisterCode(stringRedisTemplate, req2);
        userId2 = userService.register(req2).getId();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void shouldUploadResource() {
        byte[] content = ("PDF content " + ts).getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.pdf", "application/pdf", content);

        UploadResourceRequest req = new UploadResourceRequest();
        req.setCollege("计算机学院");
        req.setMajor("软件工程");
        req.setCourse("Java程序设计");
        req.setDescription("Java学习资料");

        ResourceVO r = resourceService.upload(userId1, file, req);

        assertThat(r.getId()).isNotNull();
        assertThat(r.getFileName()).isEqualTo("test.pdf");
        assertThat(r.getFileType()).isEqualTo("pdf");        assertThat(r.getCollege()).isEqualTo("计算机学院");
        assertThat(r.getMajor()).isEqualTo("软件工程");
        assertThat(r.getDownloadCount()).isEqualTo(0);
        assertThat(r.getUploader().getId()).isEqualTo(userId1);
        // 审核流（2026-07-13）：无登录态（非管理员）上传默认进入待审核
        assertThat(r.getStatus()).isEqualTo(2);
    }

    @Test
    void shouldGetResourceById() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "note.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                ("Word content "+ts).getBytes(StandardCharsets.UTF_8));

        UploadResourceRequest req = new UploadResourceRequest();
        ResourceVO uploaded = resourceService.upload(userId1, file, req);
        // 审核流：待审核资源对无登录态不可见，先审核通过再断言公开可读
        resourceService.approve(uploaded.getId(), userId1);

        ResourceVO found = resourceService.getById(uploaded.getId());
        assertThat(found.getFileName()).isEqualTo("note.docx");
        assertThat(found.getFileSize()).isEqualTo(("Word content " + ts).length());
        assertThat(found.getStatus()).isEqualTo(1);
    }

    @Test
    void shouldIncrementDownloadCount() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "data.pdf", "application/pdf",
                ("pdf data "+ts).getBytes(StandardCharsets.UTF_8));

        UploadResourceRequest req = new UploadResourceRequest();
        ResourceVO uploaded = resourceService.upload(userId1, file, req);
        resourceService.approve(uploaded.getId(), userId1);

        // 下载应增加计数
        InputStream is = resourceService.download(uploaded.getId());
        assertThat(is).isNotNull();

        ResourceVO after = resourceService.getById(uploaded.getId());
        assertThat(after.getDownloadCount()).isEqualTo(1);
    }

    @Test
    void shouldListResources() {
        MockMultipartFile file1 = new MockMultipartFile(
                "file", "a.pdf", "application/pdf",
                ("a "+ts).getBytes(StandardCharsets.UTF_8));

        UploadResourceRequest req = new UploadResourceRequest();
        req.setCollege("数学学院");
        ResourceVO uploaded = resourceService.upload(userId1, file1, req);
        resourceService.approve(uploaded.getId(), userId1);

        List<ResourceVO> list = resourceService.list(null, null, null, null, null, 20);
        assertThat(list).isNotEmpty();

        // 按学院筛选
        List<ResourceVO> filtered = resourceService.list(null, "数学学院", null, null, null, 20);
        assertThat(filtered).isNotEmpty();
        assertThat(filtered.get(0).getCollege()).isEqualTo("数学学院");
    }

    @Test
    void shouldDeleteResource() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "tmp.pdf", "application/pdf",
                ("temp "+ts).getBytes(StandardCharsets.UTF_8));

        UploadResourceRequest req = new UploadResourceRequest();
        ResourceVO uploaded = resourceService.upload(userId1, file, req);

        resourceService.delete(uploaded.getId(), userId1);

        assertThatThrownBy(() -> resourceService.getById(uploaded.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("资源不存在");
    }
}
