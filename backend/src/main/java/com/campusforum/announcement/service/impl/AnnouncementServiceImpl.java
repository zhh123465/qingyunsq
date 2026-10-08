package com.campusforum.announcement.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campusforum.admin.dto.PageResult;
import com.campusforum.announcement.domain.Announcement;
import com.campusforum.announcement.dto.AnnouncementCreateRequest;
import com.campusforum.announcement.dto.AnnouncementUpdateRequest;
import com.campusforum.announcement.dto.AnnouncementVO;
import com.campusforum.announcement.mapper.AnnouncementMapper;
import com.campusforum.announcement.service.AnnouncementService;
import com.campusforum.common.BusinessException;
import com.campusforum.common.ErrorCode;
import com.campusforum.infra.sanitize.HtmlSanitizerService;
import com.campusforum.user.domain.User;
import com.campusforum.user.mapper.UserMapper;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementServiceImpl implements AnnouncementService {

    private final AnnouncementMapper announcementMapper;
    private final HtmlSanitizerService htmlSanitizerService;
    private final UserMapper userMapper;

    /**
     * self-reference：批量 purge 时循环调用 {@link #purgeByAdmin(Long)}，
     * 每条走独立事务（{@link Propagation#REQUIRES_NEW}），单条失败不影响其它。
     * 使用 {@code @Lazy} 打破自注入循环。
     */
    @Resource
    @Lazy
    private AnnouncementService self;

    // ===== 前台 =====

    @Override
    public List<AnnouncementVO> listActive() {
        List<Announcement> rows = announcementMapper.selectActive(LocalDateTime.now());
        return toVoList(rows, false);
    }

    @Override
    public PageResult<AnnouncementVO> pagePublic(int page, int size) {
        Page<Announcement> p = new Page<>(page, Math.min(Math.max(size, 1), 100));
        IPage<Announcement> ipage = announcementMapper.selectPublicPage(p, LocalDateTime.now());
        List<AnnouncementVO> vos = toVoList(ipage.getRecords(), false);
        return PageResult.of(vos, ipage.getTotal(), ipage.getCurrent(), ipage.getSize());
    }

    @Override
    public AnnouncementVO getPublic(Long id) {
        Announcement a = announcementMapper.selectById(id);
        if (a == null || a.getDeleted() != null && a.getDeleted() == 1) {
            throw new BusinessException(ErrorCode.ANNOUNCEMENT_NOT_FOUND);
        }
        if (!"published".equals(a.getStatus())) {
            throw new BusinessException(ErrorCode.ANNOUNCEMENT_NOT_FOUND);
        }
        LocalDateTime now = LocalDateTime.now();
        if (a.getPublishTime() != null && a.getPublishTime().isAfter(now)) {
            throw new BusinessException(ErrorCode.ANNOUNCEMENT_NOT_FOUND);
        }
        if (a.getExpireTime() != null && !a.getExpireTime().isAfter(now)) {
            throw new BusinessException(ErrorCode.ANNOUNCEMENT_NOT_FOUND);
        }
        return toVo(a, true);
    }

    // ===== 管理端 =====

    @Override
    @Transactional
    public AnnouncementVO createByAdmin(AnnouncementCreateRequest req) {
        Long publisherId = StpUtil.getLoginIdAsLong();
        Announcement a = new Announcement();
        a.setTitle(req.getTitle().trim());
        a.setSummary(req.getSummary());
        a.setContent(htmlSanitizerService.sanitizePost(req.getContent()));
        a.setLevel(req.getLevel() != null ? req.getLevel() : "info");
        a.setPinned(req.getPinned() != null ? req.getPinned() : 0);
        a.setStatus(req.getStatus() != null ? req.getStatus() : "draft");
        a.setPublisherId(publisherId);
        a.setPublishTime(req.getPublishTime());
        a.setExpireTime(req.getExpireTime());
        announcementMapper.insert(a);
        return toVo(a, true);
    }

    @Override
    @Transactional
    public AnnouncementVO updateByAdmin(Long id, AnnouncementUpdateRequest req) {
        Announcement existing = requireExisting(id);
        if (req.getTitle() != null) existing.setTitle(req.getTitle().trim());
        if (req.getSummary() != null) existing.setSummary(req.getSummary());
        if (req.getContent() != null) existing.setContent(htmlSanitizerService.sanitizePost(req.getContent()));
        if (req.getLevel() != null) existing.setLevel(req.getLevel());
        if (req.getPinned() != null) existing.setPinned(req.getPinned());
        if (req.getStatus() != null) existing.setStatus(req.getStatus());
        if (req.getPublishTime() != null) existing.setPublishTime(req.getPublishTime());
        if (req.getExpireTime() != null) existing.setExpireTime(req.getExpireTime());
        announcementMapper.updateById(existing);
        return toVo(existing, true);
    }

    @Override
    @Transactional
    public void setStatus(Long id, String status) {
        if (!isValidStatus(status)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST);
        }
        Announcement existing = requireExisting(id);
        existing.setStatus(status);
        announcementMapper.updateById(existing);
    }

    @Override
    @Transactional
    public Integer togglePin(Long id) {
        Announcement existing = requireExisting(id);
        Integer next = (existing.getPinned() != null && existing.getPinned() == 1) ? 0 : 1;
        existing.setPinned(next);
        announcementMapper.updateById(existing);
        return next;
    }

    @Override
    @Transactional
    public void deleteByAdmin(Long id) {
        Announcement existing = requireExisting(id);
        announcementMapper.deleteById(existing.getId());
    }

    @Override
    @Transactional
    public void restoreByAdmin(Long id) {
        announcementMapper.restoreById(id);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Map<String, Integer> purgeByAdmin(Long id) {
        int rows = announcementMapper.physicalDeleteById(id);
        Map<String, Integer> counts = new HashMap<>();
        counts.put("announcement", rows);
        return counts;
    }

    @Override
    public PageResult<AnnouncementVO> pageForAdmin(String keyword, String status, String level,
                                                   Boolean trash, int page, int size) {
        int pageSize = Math.min(Math.max(size, 1), 100);
        Page<Announcement> p = new Page<>(page, pageSize);
        LambdaQueryWrapper<Announcement> qw = new LambdaQueryWrapper<>();

        if (keyword != null && !keyword.isBlank()) {
            String kw = "%" + keyword.trim() + "%";
            qw.and(w -> w.like(Announcement::getTitle, kw).or().like(Announcement::getSummary, kw));
        }
        if (level != null && !level.isBlank()) {
            qw.eq(Announcement::getLevel, level);
        }

        IPage<Announcement> ipage;
        if (Boolean.TRUE.equals(trash)) {
            qw.orderByDesc(Announcement::getUpdatedAt);
            ipage = announcementMapper.selectTrashPage(p, qw);
        } else {
            if (status != null && !status.isBlank()) {
                qw.eq(Announcement::getStatus, status);
            }
            qw.orderByDesc(Announcement::getPinned)
                    .orderByDesc(Announcement::getPublishTime)
                    .orderByDesc(Announcement::getId);
            ipage = announcementMapper.selectPage(p, qw);
        }
        List<AnnouncementVO> vos = toVoList(ipage.getRecords(), true);
        return PageResult.of(vos, ipage.getTotal(), ipage.getCurrent(), ipage.getSize());
    }

    // ===== 批量 =====

    @Override
    @Transactional
    public int setStatusBatchForAdmin(List<Long> ids, String status) {
        if (!isValidStatus(status)) throw new BusinessException(ErrorCode.BAD_REQUEST);
        if (ids == null || ids.isEmpty()) return 0;
        return announcementMapper.batchSetStatus(ids, status);
    }

    @Override
    @Transactional
    public int deleteBatchForAdmin(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        return announcementMapper.batchLogicalDelete(ids);
    }

    @Override
    @Transactional
    public int restoreBatchForAdmin(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        return announcementMapper.batchRestore(ids);
    }

    @Override
    public Map<String, Object> purgeBatchForAdmin(List<Long> ids) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        Map<String, Integer> counts = new HashMap<>();
        int success = 0;
        for (Long id : ids) {
            try {
                Map<String, Integer> c = self.purgeByAdmin(id);
                success++;
                c.forEach((k, v) -> counts.merge(k, v, Integer::sum));
            } catch (Exception e) {
                Map<String, Object> item = new HashMap<>();
                item.put("id", id);
                item.put("reason", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                failed.add(item);
                log.warn("purgeByAdmin failed id={}, reason={}", id, e.toString());
            }
        }
        result.put("success", success);
        result.put("failed", failed);
        result.put("counts", counts);
        return result;
    }

    // ===== 内部工具 =====

    private Announcement requireExisting(Long id) {
        Announcement a = announcementMapper.selectById(id);
        if (a == null) {
            throw new BusinessException(ErrorCode.ANNOUNCEMENT_NOT_FOUND);
        }
        return a;
    }

    private boolean isValidStatus(String s) {
        return "draft".equals(s) || "published".equals(s) || "archived".equals(s);
    }

    private List<AnnouncementVO> toVoList(List<Announcement> rows, boolean includeContent) {
        if (rows == null || rows.isEmpty()) return List.of();
        Set<Long> publisherIds = rows.stream()
                .map(Announcement::getPublisherId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        Map<Long, String> nameById = new HashMap<>();
        if (!publisherIds.isEmpty()) {
            List<User> users = userMapper.selectBatchIds(publisherIds);
            for (User u : users) {
                nameById.put(u.getId(), u.getNickname());
            }
        }
        List<AnnouncementVO> out = new ArrayList<>(rows.size());
        for (Announcement a : rows) {
            out.add(toVo(a, includeContent, nameById.get(a.getPublisherId())));
        }
        return out;
    }

    private AnnouncementVO toVo(Announcement a, boolean includeContent) {
        String name = null;
        if (a.getPublisherId() != null) {
            User u = userMapper.selectById(a.getPublisherId());
            if (u != null) name = u.getNickname();
        }
        return toVo(a, includeContent, name);
    }

    private AnnouncementVO toVo(Announcement a, boolean includeContent, String publisherName) {
        return AnnouncementVO.builder()
                .id(a.getId())
                .title(a.getTitle())
                .summary(a.getSummary())
                .content(includeContent ? a.getContent() : null)
                .level(a.getLevel())
                .pinned(a.getPinned())
                .status(a.getStatus())
                .publishTime(a.getPublishTime())
                .expireTime(a.getExpireTime())
                .publisherId(a.getPublisherId())
                .publisherName(publisherName)
                .createdAt(a.getCreatedAt())
                .updatedAt(a.getUpdatedAt())
                .build();
    }
}
