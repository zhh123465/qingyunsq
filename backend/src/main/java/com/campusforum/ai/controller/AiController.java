package com.campusforum.ai.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.campusforum.ai.domain.PostAiCard;
import com.campusforum.ai.dto.AiRequest;
import com.campusforum.ai.dto.AiResponse;
import com.campusforum.ai.dto.PostAiCardVO;
import com.campusforum.ai.service.AiService;
import com.campusforum.ai.service.PostAiCardService;
import com.campusforum.ai.service.RagChatService;
import com.campusforum.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiService aiService;
    private final RagChatService ragChatService;
    private final PostAiCardService postAiCardService;
    private final StringRedisTemplate redisTemplate;

    @PostMapping("/summarize")
    public R<AiResponse> summarize(@RequestBody AiRequest req) {
        String result = aiService.summarize(req.getContent());
        return R.ok(AiResponse.builder().summary(result).build());
    }

    @PostMapping("/moderate")
    public R<AiResponse> moderate(@RequestBody AiRequest req) {
        AiService.RiskResult result = aiService.moderate(req.getContent());
        return R.ok(AiResponse.builder()
                .riskLevel(result.level())
                .riskReason(result.reason())
                .build());
    }

    @PostMapping("/tags")
    public R<AiResponse> recommendTags(@RequestBody AiRequest req) {
        var tags = aiService.recommendTags(req.getTitle(), req.getContent());
        return R.ok(AiResponse.builder().tags(tags).build());
    }

    @PostMapping("/chat")
    public R<AiResponse> chat(@RequestBody AiRequest req) {
        String reply = aiService.chat(req.getMessages(), req.getContext(), req.getModel());
        return R.ok(AiResponse.builder().reply(reply).build());
    }

    @PostMapping("/rag-chat")
    public R<AiResponse> ragChat(@RequestBody AiRequest req) {
        boolean webSearchEnabled = req.getAbilities() == null || req.getAbilities().contains("web-search");
        return R.ok(ragChatService.chat(req.getMessages(), req.getContext(), req.getModel(), webSearchEnabled));
    }

    /**
     * 帖子智能卡片。
     * 默认（passive=false）：缓存不存在或过期则触发 AI 生成。详情页用。
     * passive=true：仅读缓存，不触发生成。列表页用，避免一次浏览触发数十次 LLM 调用。
     * 返回 data=null 表示无缓存（passive 模式）或 AI 生成失败 — 前端均应隐藏卡片。
     */
    @GetMapping("/post-card/{postId}")
    public R<PostAiCardVO> postCard(@PathVariable Long postId,
                                    @RequestParam(value = "passive", defaultValue = "false") boolean passive) {
        PostAiCard card = passive
                ? postAiCardService.getCached(postId)
                : postAiCardService.getOrGenerate(postId);
        return R.ok(toVO(card));
    }

    /**
     * 批量读卡片缓存。列表页一次拉取，避免每个帖子一次 HTTP。
     * 仅返回有缓存的 postId；无缓存的 key 不会出现在 map 中。
     */
    @PostMapping("/post-cards/batch")
    public R<java.util.Map<Long, PostAiCardVO>> postCardsBatch(@RequestBody java.util.List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return R.ok(java.util.Collections.emptyMap());
        }
        // 防止超大批量请求拖慢 DB
        java.util.List<Long> capped = postIds.size() > 100 ? postIds.subList(0, 100) : postIds;
        var cards = postAiCardService.getCachedBatch(capped);
        java.util.Map<Long, PostAiCardVO> result = new java.util.HashMap<>();
        cards.forEach((id, card) -> result.put(id, toVO(card)));
        return R.ok(result);
    }

    private PostAiCardVO toVO(PostAiCard card) {
        if (card == null) return null;
        return PostAiCardVO.builder()
                .tldr(card.getTldr())
                .audience(card.getAudience())
                .valueType(card.getValueType())
                .readMinutes(card.getReadMinutes())
                .commentConsensus(card.getCommentConsensus())
                .commentDisputes(card.getCommentDisputes())
                .hotCommentId(card.getHotCommentId())
                .hotCommentExcerpt(card.getHotCommentExcerpt())
                .highlights(parseHighlights(card.getHighlights()))
                .build();
    }

    @SuppressWarnings("unchecked")
    private java.util.List<String> parseHighlights(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, java.util.List.class);
        } catch (Exception e) {
            return null;
        }
    }

    @GetMapping("/rate-limit-status")
    public R<Map<String, Object>> rateLimitStatus() {
        if (!StpUtil.isLogin()) {
            return R.ok(Map.of("normal", Map.of("limit", 20, "used", 0, "remaining", 20),
                    "pro", Map.of("limit", 10, "used", 0, "remaining", 10)));
        }
        long userId = StpUtil.getLoginIdAsLong();
        Long proUsed = redisTemplate.opsForZSet().zCard("ai_rate:user:" + userId + ":hour:pro");
        Long normalUsed = redisTemplate.opsForZSet().zCard("ai_rate:user:" + userId + ":hour:normal");
        return R.ok(Map.of(
                "normal", Map.of("limit", 20, "used", normalUsed, "remaining", Math.max(0, 20 - normalUsed)),
                "pro", Map.of("limit", 10, "used", proUsed, "remaining", Math.max(0, 10 - proUsed))));
    }
}
