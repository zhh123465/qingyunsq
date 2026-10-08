package com.campusforum.announcement.controller;

import com.campusforum.admin.dto.PageResult;
import com.campusforum.announcement.dto.AnnouncementVO;
import com.campusforum.announcement.service.AnnouncementService;
import com.campusforum.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 前台公告接口（游客/登录用户均可读）。
 * Sa-Token GET 默认放行，无需额外配置。
 */
@RestController
@RequestMapping("/api/v1/announcements")
@RequiredArgsConstructor
public class AnnouncementController {

    private final AnnouncementService announcementService;

    /** 当前生效公告（顶栏 banner 拉取）。 */
    @GetMapping("/active")
    public R<List<AnnouncementVO>> active() {
        return R.ok(announcementService.listActive());
    }

    /** 分页公告列表（前台独立公告页）。 */
    @GetMapping
    public R<PageResult<AnnouncementVO>> list(@RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return R.ok(announcementService.pagePublic(page, size));
    }

    /** 单条公告详情。 */
    @GetMapping("/{id}")
    public R<AnnouncementVO> detail(@PathVariable Long id) {
        return R.ok(announcementService.getPublic(id));
    }
}
