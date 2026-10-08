package com.campusforum.search.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campusforum.common.R;
import com.campusforum.post.domain.Post;
import com.campusforum.post.mapper.PostMapper;
import com.campusforum.resource.domain.Resource;
import com.campusforum.resource.mapper.ResourceMapper;
import com.campusforum.search.service.MeiliSearchClient;
import com.campusforum.search.service.SearchIndexService;
import com.campusforum.space.domain.Space;
import com.campusforum.space.mapper.SpaceMapper;
import com.campusforum.user.domain.User;
import com.campusforum.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全量重建搜索索引（posts / users / resources / spaces 四个 MeiliSearch 索引）。
 * 分批扫表（500/批），可见性过滤交给 {@link SearchIndexService}（status!=1 的记录跳过）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/search")
@RequiredArgsConstructor
public class SearchReindexController {

    private final PostMapper postMapper;
    private final ResourceMapper resourceMapper;
    private final UserMapper userMapper;
    private final SpaceMapper spaceMapper;
    private final MeiliSearchClient meiliSearchClient;
    private final SearchIndexService searchIndexService;

    @PostMapping("/reindex")
    @SaCheckPermission("tenant:dashboard")
    public R<Map<String, Integer>> reindex() {
        int posts = 0;
        int resources = 0;
        int users = 0;
        int spaces = 0;

        // 重建帖子索引
        Long lastId = null;
        while (true) {
            LambdaQueryWrapper<Post> qw = new LambdaQueryWrapper<>();
            if (lastId != null) qw.gt(Post::getId, lastId);
            qw.eq(Post::getStatus, 1).orderByAsc(Post::getId).last("LIMIT 500");
            List<Post> batch = postMapper.selectList(qw);
            if (batch.isEmpty()) break;
            for (Post p : batch) {
                Map<String, Object> doc = new HashMap<>();
                doc.put("id", p.getId());
                doc.put("title", p.getTitle());
                doc.put("content", p.getContent());
                doc.put("authorId", p.getAuthorId());
                doc.put("createdAt", p.getCreatedAt());
                doc.put("likeCount", p.getLikeCount());
                doc.put("commentCount", p.getCommentCount());
                doc.put("viewCount", p.getViewCount());
                doc.put("status", p.getStatus());
                doc.put("scope", p.getScope());
                doc.put("type", p.getType());
                doc.put("topics", p.getTopics());
                doc.put("tags", p.getTags());
                meiliSearchClient.indexDocument("posts", doc);
                posts++;
            }
            lastId = batch.get(batch.size() - 1).getId();
        }

        // 重建资源索引
        lastId = null;
        while (true) {
            LambdaQueryWrapper<Resource> qw = new LambdaQueryWrapper<>();
            if (lastId != null) qw.gt(Resource::getId, lastId);
            qw.eq(Resource::getStatus, 1).orderByAsc(Resource::getId).last("LIMIT 500");
            List<Resource> batch = resourceMapper.selectList(qw);
            if (batch.isEmpty()) break;
            for (Resource r : batch) {
                searchIndexService.indexResource(r);
                resources++;
            }
            lastId = batch.get(batch.size() - 1).getId();
        }

        // 重建用户索引（文档不含 email/studentNo，满足漏洞 9 的 PII 约束）
        lastId = null;
        while (true) {
            LambdaQueryWrapper<User> qw = new LambdaQueryWrapper<>();
            if (lastId != null) qw.gt(User::getId, lastId);
            qw.eq(User::getStatus, 1).orderByAsc(User::getId).last("LIMIT 500");
            List<User> batch = userMapper.selectList(qw);
            if (batch.isEmpty()) break;
            for (User u : batch) {
                searchIndexService.indexUser(u);
                users++;
            }
            lastId = batch.get(batch.size() - 1).getId();
        }

        // 重建空间索引
        lastId = null;
        while (true) {
            LambdaQueryWrapper<Space> qw = new LambdaQueryWrapper<>();
            if (lastId != null) qw.gt(Space::getId, lastId);
            qw.eq(Space::getStatus, 1).orderByAsc(Space::getId).last("LIMIT 500");
            List<Space> batch = spaceMapper.selectList(qw);
            if (batch.isEmpty()) break;
            for (Space s : batch) {
                searchIndexService.indexSpace(s);
                spaces++;
            }
            lastId = batch.get(batch.size() - 1).getId();
        }

        int total = posts + resources + users + spaces;
        log.info("Full reindex completed: posts={}, resources={}, users={}, spaces={}, total={}",
                posts, resources, users, spaces, total);
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("posts", posts);
        result.put("resources", resources);
        result.put("users", users);
        result.put("spaces", spaces);
        result.put("indexed", total);
        return R.ok(result);
    }
}
