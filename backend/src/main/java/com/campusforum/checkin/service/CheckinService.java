package com.campusforum.checkin.service;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campusforum.checkin.domain.CheckinChallenge;
import com.campusforum.checkin.domain.CheckinRecord;
import com.campusforum.checkin.dto.*;
import com.campusforum.checkin.mapper.CheckinChallengeMapper;
import com.campusforum.checkin.mapper.CheckinRecordMapper;
import com.campusforum.common.BusinessException;
import com.campusforum.common.ErrorCode;
import com.campusforum.achievement.service.AchievementService;
import com.campusforum.ai.service.AiService;
import com.campusforum.post.domain.Post;
import com.campusforum.post.mapper.PostMapper;
import com.campusforum.user.domain.User;
import com.campusforum.user.dto.PublicUserVO;
import com.campusforum.user.mapper.UserMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CheckinService {

    private final CheckinChallengeMapper challengeMapper;
    private final CheckinRecordMapper recordMapper;
    private final UserMapper userMapper;
    private final AchievementService achievementService;
    private final PostMapper postMapper;
    private final ObjectMapper objectMapper;
    private final AiService aiService;

    /** 隐藏状态：前台不可见，管理页仍可见（区别于回收站 status=0）。 */
    private static final int STATUS_HIDDEN = 2;

    /** Self reference for AOP self-invocation (per-item transaction in batch purge). */
    @Resource
    @Lazy
    private CheckinService selfProxy;

    @Transactional
    public CheckinChallengeVO create(Long userId, CreateCheckinChallengeRequest req) {
        CheckinChallenge challenge = new CheckinChallenge();
        challenge.setCreatorId(userId);
        challenge.setName(req.getName());
        challenge.setDescription(req.getDescription());
        challenge.setSpaceId(req.getSpaceId());
        challenge.setStartDate(req.getStartDate());
        challenge.setEndDate(req.getEndDate());
        challenge.setRule(req.getRule());
        challenge.setMemberCount(0);
        challenge.setStatus(1);

        challengeMapper.insert(challenge);
        log.info("Checkin challenge created: id={}, name={}", challenge.getId(), challenge.getName());
        return toVO(challenge, userId, false, 0, 0);
    }

    public CheckinChallengeVO getById(Long challengeId) {
        CheckinChallenge challenge = challengeMapper.selectById(challengeId);
        if (challenge == null || challenge.getStatus() == 0) {
            throw new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND);
        }

        Long currentUserId = StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null;
        boolean isMember = false;
        int totalDays = 0;
        int streak = 0;

        if (currentUserId != null) {
            List<CheckinRecord> records = recordMapper.selectList(new LambdaQueryWrapper<CheckinRecord>()
                    .eq(CheckinRecord::getChallengeId, challengeId)
                    .eq(CheckinRecord::getUserId, currentUserId));
            if (!records.isEmpty()) {
                isMember = true;
                totalDays = records.size();
                streak = calcStreak(records);
            }
        }

        return toVO(challenge, currentUserId, isMember, totalDays, streak);
    }

    public List<CheckinChallengeVO> list(Long spaceId, Long cursor, int limit) {
        int size = Math.min(limit, 50);
        LambdaQueryWrapper<CheckinChallenge> qw = new LambdaQueryWrapper<>();
        qw.eq(CheckinChallenge::getStatus, 1);
        if (spaceId != null) {
            qw.eq(CheckinChallenge::getSpaceId, spaceId);
        }
        if (cursor != null) {
            qw.lt(CheckinChallenge::getId, cursor);
        }
        qw.orderByDesc(CheckinChallenge::getMemberCount, CheckinChallenge::getId);
        qw.last("LIMIT " + size);

        List<CheckinChallenge> challenges = challengeMapper.selectList(qw);
        Long currentUserId = StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null;
        return challenges.stream().map(c -> toVO(c, currentUserId, false, 0, 0)).toList();
    }

    // === 管理端：列表 / 隐藏 / 删除 / 恢复 / 彻底删除 ===
    // checkin_challenges 表没有 deleted 字段。语义映射：
    //   status=1 正常   / status=2 隐藏 / status=0 回收站
    // 隐藏与恢复走 setStatusForAdmin(id, 1|2)；软删（进回收站）走 deleteByAdmin(id) → status=0；
    // 回收站恢复 → status=1；彻底删除 → 物理删除 + 级联清 records。

    public IPage<CheckinChallengeVO> listForAdminPaged(String keyword, Integer status,
                                                       long pageNum, long pageSize) {
        long size = Math.min(pageSize, 100);
        Page<CheckinChallenge> page = new Page<>(pageNum, size);
        LambdaQueryWrapper<CheckinChallenge> qw = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) qw.like(CheckinChallenge::getName, keyword);
        if (status != null) qw.eq(CheckinChallenge::getStatus, status);
        else qw.ne(CheckinChallenge::getStatus, 0); // 默认排除回收站（status=0）
        qw.orderByDesc(CheckinChallenge::getId);
        IPage<CheckinChallenge> res = challengeMapper.selectPage(page, qw);
        Long currentUserId = StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null;
        return res.convert(c -> toVO(c, currentUserId, false, 0, 0));
    }

    public IPage<CheckinChallengeVO> listTrashForAdminPaged(String keyword,
                                                            long pageNum, long pageSize) {
        long size = Math.min(pageSize, 100);
        Page<CheckinChallenge> page = new Page<>(pageNum, size);
        QueryWrapper<CheckinChallenge> qw = new QueryWrapper<>();
        qw.lambda().like(keyword != null && !keyword.isBlank(), CheckinChallenge::getName, keyword)
                .orderByDesc(CheckinChallenge::getId);
        IPage<CheckinChallenge> res = challengeMapper.selectTrashPage(page, qw);
        Long currentUserId = StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null;
        return res.convert(c -> toVO(c, currentUserId, false, 0, 0));
    }

    /** status: 1 正常，2 隐藏。 */
    @Transactional
    public void setStatusForAdmin(Long challengeId, Integer status) {
        if (status == null || (status != 1 && status != STATUS_HIDDEN)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "无效的状态值（仅允许 1 或 2）");
        }
        CheckinChallenge c = challengeMapper.selectById(challengeId);
        if (c == null || c.getStatus() == 0) {
            throw new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND);
        }
        c.setStatus(status);
        challengeMapper.updateById(c);
    }

    /** 软删除进入回收站：status=0。 */
    @Transactional
    public void deleteByAdmin(Long challengeId) {
        CheckinChallenge c = challengeMapper.selectById(challengeId);
        if (c == null || c.getStatus() == 0) {
            throw new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND);
        }
        c.setStatus(0);
        challengeMapper.updateById(c);
    }

    @Transactional
    public void restoreForAdmin(Long challengeId) {
        int rows = challengeMapper.restoreById(challengeId);
        if (rows == 0) {
            throw new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND);
        }
    }

    /** 彻底删除挑战 + 打卡记录。 */
    @Transactional
    public Map<String, Integer> purgeForAdmin(Long challengeId) {
        int records = recordMapper.physicalDeleteByChallengeId(challengeId);
        int self = challengeMapper.physicalDeleteById(challengeId);
        if (self == 0) {
            throw new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND.getCode(),
                    "挑战不在回收站或已被清理");
        }
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        counts.put("records", records);
        return counts;
    }

    // === 批量管理端操作 ===
    public int setStatusBatchForAdmin(List<Long> ids, int status) {
        if (status != 1 && status != STATUS_HIDDEN) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "无效的状态值（仅允许 1 或 2）");
        }
        return challengeMapper.batchSetStatus(ids, status);
    }

    public int deleteBatchForAdmin(List<Long> ids) {
        return challengeMapper.batchLogicalDelete(ids);
    }

    public int restoreBatchForAdmin(List<Long> ids) {
        return challengeMapper.batchRestore(ids);
    }

    public Map<String, Object> purgeBatchForAdmin(List<Long> ids) {
        int success = 0;
        List<Map<String, Object>> failed = new java.util.ArrayList<>();
        Map<String, Integer> totalCounts = new java.util.LinkedHashMap<>();
        for (Long id : ids) {
            try {
                Map<String, Integer> c = selfProxy.purgeForAdmin(id);
                success++;
                c.forEach((k, v) -> totalCounts.merge(k, v, Integer::sum));
            } catch (BusinessException e) {
                Map<String, Object> f = new java.util.LinkedHashMap<>();
                f.put("id", id);
                f.put("reason", e.getMessage() != null ? e.getMessage() : "PURGE_FAILED");
                failed.add(f);
            } catch (Exception e) {
                log.warn("purge checkin challenge failed: id={}, msg={}", id, e.getMessage());
                Map<String, Object> f = new java.util.LinkedHashMap<>();
                f.put("id", id);
                f.put("reason", "PURGE_FAILED");
                failed.add(f);
            }
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("success", success);
        result.put("failed", failed);
        result.put("counts", totalCounts);
        return result;
    }

    @Transactional
    public CheckinChallengeVO update(Long challengeId, Long userId, CreateCheckinChallengeRequest req) {
        CheckinChallenge challenge = challengeMapper.selectById(challengeId);
        if (challenge == null || challenge.getStatus() == 0) {
            throw new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND);
        }
        if (!challenge.getCreatorId().equals(userId)) {
            String role = (String) StpUtil.getSession().get("role");
            if (!"TENANT_ADMIN".equals(role) && !"SUPER_ADMIN".equals(role)) {
                throw new BusinessException(ErrorCode.FORBIDDEN);
            }
        }

        if (req.getName() != null) challenge.setName(req.getName());
        if (req.getDescription() != null) challenge.setDescription(req.getDescription());
        if (req.getStartDate() != null) challenge.setStartDate(req.getStartDate());
        if (req.getEndDate() != null) challenge.setEndDate(req.getEndDate());
        if (req.getRule() != null) challenge.setRule(req.getRule());

        challengeMapper.updateById(challenge);
        return getById(challengeId);
    }

    @Transactional
    public CheckinRecordVO checkin(Long challengeId, Long userId, CreateCheckinRecordRequest req) {
        CheckinChallenge challenge = challengeMapper.selectById(challengeId);
        if (challenge == null || challenge.getStatus() == 0) {
            throw new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND);
        }

        LocalDate today = LocalDate.now();
        if (today.isBefore(challenge.getStartDate()) || today.isAfter(challenge.getEndDate())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "挑战未开始或已结束");
        }

        // 检查今日是否已打卡
        CheckinRecord existing = recordMapper.selectOne(new LambdaQueryWrapper<CheckinRecord>()
                .eq(CheckinRecord::getChallengeId, challengeId)
                .eq(CheckinRecord::getUserId, userId)
                .eq(CheckinRecord::getCheckinDate, today));
        if (existing != null) {
            throw new BusinessException(ErrorCode.ALREADY_CHECKED_IN);
        }

        // 判断是否首次打卡（用于 member_count）
        boolean isFirst = !recordMapper.exists(new LambdaQueryWrapper<CheckinRecord>()
                .eq(CheckinRecord::getChallengeId, challengeId)
                .eq(CheckinRecord::getUserId, userId));

        CheckinRecord record = new CheckinRecord();
        record.setChallengeId(challengeId);
        record.setUserId(userId);
        record.setCheckinDate(today);
        record.setContent(req.getContent());
        if (req.getImageUrls() != null && !req.getImageUrls().isEmpty()) {
            try {
                record.setImageUrls(objectMapper.writeValueAsString(req.getImageUrls()));
            } catch (JsonProcessingException e) {
                record.setImageUrls("[]");
            }
        }
        // AI 检测打卡内容是否符合挑战主题
        String theme = challenge.getName();
        if (challenge.getDescription() != null && !challenge.getDescription().isBlank()) {
            theme += " " + challenge.getDescription();
        }
        boolean relevant = aiService.checkRelevance(theme,
                req.getContent() != null ? req.getContent() : "");
        record.setAiCheck(relevant ? 1 : 0);

        // Bug fix 1.11: 依赖数据库唯一约束防止并发重复打卡
        try {
            recordMapper.insert(record);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.ALREADY_CHECKED_IN);
        }

        // 首次打卡增加成员数
        if (isFirst) {
            challenge.setMemberCount(challenge.getMemberCount() + 1);
            challengeMapper.updateById(challenge);
        }

        achievementService.onCheckin(userId);

        log.info("User {} checked in to challenge {}", userId, challengeId);
        return toRecordVO(record, userId);
    }

    public List<CheckinRecordVO> getRecords(Long challengeId, Long cursor, int limit) {
        int size = Math.min(limit, 50);
        LambdaQueryWrapper<CheckinRecord> qw = new LambdaQueryWrapper<>();
        qw.eq(CheckinRecord::getChallengeId, challengeId);
        if (cursor != null) {
            qw.lt(CheckinRecord::getId, cursor);
        }
        qw.orderByDesc(CheckinRecord::getId);
        qw.last("LIMIT " + size);

        return recordMapper.selectList(qw).stream()
                .map(r -> toRecordVO(r, r.getUserId()))
                .toList();
    }

    public List<LeaderboardEntry> getLeaderboard(Long challengeId) {
        List<CheckinRecord> records = recordMapper.selectList(new LambdaQueryWrapper<CheckinRecord>()
                .eq(CheckinRecord::getChallengeId, challengeId));

        // 按 user 分组
        Map<Long, List<CheckinRecord>> grouped = records.stream()
                .collect(Collectors.groupingBy(CheckinRecord::getUserId));

        List<LeaderboardEntry> entries = new ArrayList<>();
        for (Map.Entry<Long, List<CheckinRecord>> e : grouped.entrySet()) {
            Long uid = e.getKey();
            List<CheckinRecord> userRecords = e.getValue();
            int total = userRecords.size();
            int streak = calcStreak(userRecords);

            User user = userMapper.selectById(uid);
            entries.add(LeaderboardEntry.builder()
                    .userId(uid)
                    .userName(user != null ? user.getNickname() : "未知")
                    .avatarUrl(user != null ? user.getAvatarUrl() : null)
                    .totalDays(total)
                    .currentStreak(streak)
                    .build());
        }

        entries.sort((a, b) -> {
            int cmp = b.getTotalDays().compareTo(a.getTotalDays());
            if (cmp != 0) return cmp;
            return b.getCurrentStreak().compareTo(a.getCurrentStreak());
        });

        return entries;
    }

    @Transactional
    public void delete(Long challengeId, Long userId) {
        CheckinChallenge challenge = challengeMapper.selectById(challengeId);
        if (challenge == null || challenge.getStatus() == 0) {
            throw new BusinessException(ErrorCode.CHALLENGE_NOT_FOUND);
        }
        if (!challenge.getCreatorId().equals(userId)) {
            String role = (String) StpUtil.getSession().get("role");
            if (!"TENANT_ADMIN".equals(role) && !"SUPER_ADMIN".equals(role)) {
                throw new BusinessException(ErrorCode.FORBIDDEN);
            }
        }

        challenge.setStatus(0);
        challengeMapper.updateById(challenge);
        log.info("Checkin challenge deleted: id={}", challengeId);
    }

    @Transactional
    public Post shareToSquare(Long recordId, Long userId) {
        CheckinRecord record = recordMapper.selectById(recordId);
        if (record == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (!record.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        CheckinChallenge challenge = challengeMapper.selectById(record.getChallengeId());
        String challengeName = challenge != null ? challenge.getName() : "打卡挑战";

        String content = "[打卡分享] " + challengeName + " — " + record.getCheckinDate() + "\n\n";
        if (record.getContent() != null && !record.getContent().isBlank()) {
            content += record.getContent() + "\n\n";
        }
        
        if (record.getImageUrls() != null && !record.getImageUrls().isBlank() && !"[]".equals(record.getImageUrls())) {
            try {
                List<String> urls = objectMapper.readValue(record.getImageUrls(), List.class);
                for (String url : urls) {
                    content += "![打卡图片](" + url + ")\n";
                }
            } catch (Exception e) {
                log.warn("Failed to parse imageUrls for checkin record {}", recordId);
            }
        }

        Post post = new Post();
        post.setAuthorId(userId);
        post.setScope("SQUARE");
        post.setType("CHECKIN");
        post.setTitle("[打卡] " + challengeName);
        post.setContent(content);
        post.setViewCount(0);
        post.setLikeCount(0);
        post.setCommentCount(0);
        post.setIsPinned(0);
        post.setIsEssence(0);
        post.setStatus(1);

        postMapper.insert(post);
        log.info("Checkin record {} shared to square as post {}", recordId, post.getId());
        return post;
    }

    private int calcStreak(List<CheckinRecord> records) {
        if (records.isEmpty()) return 0;

        List<LocalDate> dates = records.stream()
                .map(CheckinRecord::getCheckinDate)
                .distinct()
                .sorted(Comparator.reverseOrder())
                .toList();

        LocalDate today = LocalDate.now();
        // 连续打卡必须包含今天或昨天
        LocalDate latest = dates.get(0);
        if (!latest.equals(today) && !latest.equals(today.minusDays(1))) {
            return 0;
        }

        int streak = 1;
        for (int i = 0; i < dates.size() - 1; i++) {
            long gap = ChronoUnit.DAYS.between(dates.get(i + 1), dates.get(i));
            if (gap == 1) {
                streak++;
            } else {
                break;
            }
        }
        return streak;
    }

    private CheckinChallengeVO toVO(CheckinChallenge c, Long currentUserId, boolean isMember, int totalDays, int streak) {
        User creator = userMapper.selectById(c.getCreatorId());
        PublicUserVO creatorVO = PublicUserVO.from(creator);

        return CheckinChallengeVO.builder()
                .id(c.getId())
                .spaceId(c.getSpaceId())
                .creatorId(c.getCreatorId())
                .creator(creatorVO)
                .name(c.getName())
                .description(c.getDescription())
                .startDate(c.getStartDate())
                .endDate(c.getEndDate())
                .rule(c.getRule())
                .memberCount(c.getMemberCount())
                .status(c.getStatus())
                .isMember(isMember)
                .myTotalDays(totalDays)
                .myConsecutiveDays(streak)
                .createdAt(c.getCreatedAt())
                .build();
    }

    private CheckinRecordVO toRecordVO(CheckinRecord r, Long userId) {
        User user = userMapper.selectById(userId);
        PublicUserVO userVO = PublicUserVO.from(user);

        List<String> urls = Collections.emptyList();
        if (r.getImageUrls() != null && !r.getImageUrls().isEmpty() && !"[]".equals(r.getImageUrls())) {
            try {
                urls = objectMapper.readValue(r.getImageUrls(), List.class);
            } catch (Exception ignored) {
            }
        }

        return CheckinRecordVO.builder()
                .id(r.getId())
                .challengeId(r.getChallengeId())
                .userId(userId)
                .user(userVO)
                .checkinDate(r.getCheckinDate())
                .content(r.getContent())
                .imageUrls(urls)
                .aiCheck(r.getAiCheck())
                .createdAt(r.getCreatedAt())
                .build();
    }
}
