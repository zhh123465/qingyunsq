package com.campusforum.announcement.service;

import com.campusforum.admin.dto.PageResult;
import com.campusforum.announcement.dto.AnnouncementCreateRequest;
import com.campusforum.announcement.dto.AnnouncementUpdateRequest;
import com.campusforum.announcement.dto.AnnouncementVO;

import java.util.List;
import java.util.Map;

/**
 * 公告服务。所有方法内部走 MyBatis-Plus 租户插件自动隔离 tenant_id，
 * 不需要显式 WHERE tenant_id。
 */
public interface AnnouncementService {

    // ===== 前台读接口 =====

    /** 当前生效公告（横幅用），已按 pinned/publishTime 排序，最多 10 条。 */
    List<AnnouncementVO> listActive();

    /** 分页列出前台可见公告（published & 未过期），供独立公告页使用。 */
    PageResult<AnnouncementVO> pagePublic(int page, int size);

    /** 单条公告详情（前台）。已删除 / 未发布 / 已过期均视为不存在。 */
    AnnouncementVO getPublic(Long id);

    // ===== 管理端 CRUD =====

    /** 新建。返回创建的 VO。 */
    AnnouncementVO createByAdmin(AnnouncementCreateRequest req);

    /** 编辑。仅覆盖 req 中非 null 字段。 */
    AnnouncementVO updateByAdmin(Long id, AnnouncementUpdateRequest req);

    /** 切换发布状态：draft / published / archived。 */
    void setStatus(Long id, String status);

    /** 切换置顶（toggle）。 */
    Integer togglePin(Long id);

    /** 逻辑删除到回收站。 */
    void deleteByAdmin(Long id);

    /** 从回收站恢复。 */
    void restoreByAdmin(Long id);

    /** 彻底删除（仅允许对回收站中的记录）。 */
    Map<String, Integer> purgeByAdmin(Long id);

    /** 管理端分页（keyword/status/level/pinned + trash 二选一）。 */
    PageResult<AnnouncementVO> pageForAdmin(String keyword, String status, String level,
                                            Boolean trash, int page, int size);

    // ===== 管理端批量 =====

    int setStatusBatchForAdmin(List<Long> ids, String status);

    int deleteBatchForAdmin(List<Long> ids);

    int restoreBatchForAdmin(List<Long> ids);

    /**
     * 批量彻底删除。返回 {success, failed:[{id,reason}], counts:{...}}。
     * 循环调用 {@link #purgeByAdmin(Long)}，单条失败不中断其他条目（AOP self-invocation via @Lazy）。
     */
    Map<String, Object> purgeBatchForAdmin(List<Long> ids);
}
