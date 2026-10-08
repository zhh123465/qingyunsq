package com.campusforum.ai.service;

import com.campusforum.infra.security.SafeHttpClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议（DeepSeek / OpenAI / 其他兼容协议）的 AI 客户端实现。
 *
 * <p><b>禁止作为全局 Spring Bean</b>（对应 bugfix.md 漏洞 12）：</p>
 * <ul>
 *   <li>历史实现持有从 {@code application.yml} 注入的全局 {@code ai.api-key}，
 *       多租户场景下所有租户共用同一个 key，且 key 轮换需要重启进程才能生效。</li>
 *   <li>当前实现把"baseUrl + apiKey + model"作为构造器入参，必须由
 *       {@link TenantAwareAiService} 在 delegate() 中按租户 new 出实例并按
 *       (tenantId, fingerprint) 缓存，配置变更时由
 *       {@code TenantService.updateAiConfig} 主动 evict。</li>
 *   <li>因此本类<b>不再使用</b> {@code @Service} / {@code @ConditionalOnProperty}
 *       注解，构造器也收缩为 package-private——只允许同一包下的
 *       {@code TenantAwareAiService} 实例化，避免任何业务代码再通过
 *       {@code @Autowired OpenAiCompatService} 拿到一个全局凭证 Bean。</li>
 * </ul>
 *
 * <p>该类内部仍然使用 {@link SafeHttpClient} 创建 RestTemplate，提供 SSRF
 * 二次校验 + DNS 重绑定防御；超时配置保持与历史一致（连接 8s、读 30s）。</p>
 */
@Slf4j
public class OpenAiCompatService implements AiService {

    /** 连接超时（毫秒）：避免攻击者通过缓慢 TCP 握手堆积连接耗尽线程。 */
    private static final int CONNECT_TIMEOUT_MS = 8000;
    /** 读超时（毫秒）：与上游响应慢的体验权衡，避免长尾请求长时间占线程。 */
    private static final int READ_TIMEOUT_MS = 30000;

    /** 统一的脱敏错误文案，避免泄漏上游具体错误信息（漏洞 1.18）。 */
    static final String AI_UPSTREAM_ERROR_MESSAGE = "AI 服务暂时不可用，请稍后重试";

    static boolean isUpstreamError(String reply) {
        return AI_UPSTREAM_ERROR_MESSAGE.equals(reply);
    }

    private final RestTemplate restTemplate;
    // ObjectMapper 线程安全且无实例级配置，按类共享单例避免每个租户客户端各建一份
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    /**
     * 构造一个面向特定租户配置的 OpenAI 兼容客户端。
     *
     * <p><b>访问性</b>：包级私有，只允许同一包下的 {@link TenantAwareAiService}
     * 实例化。任何 controller / service 试图直接 {@code new OpenAiCompatService(...)}
     * 都会编译失败，从而阻断"绕过缓存层、绕过 fail-loud 校验"的回归。</p>
     *
     * @param baseUrl 上游 AI 服务的 base URL（必须是公网 https；私网/本机由
     *                {@link com.campusforum.infra.security.PrivateNetworkValidator}
     *                在调用前拒绝）
     * @param apiKey  上游 AI 服务的明文 API Key（已由租户密钥库解密；不会落盘 / 不会进日志）
     * @param model   模型名（如 {@code deepseek-v4-flash}），为空时回退默认值
     */
    OpenAiCompatService(String baseUrl, String apiKey, String model) {
        this.restTemplate = createRestTemplate();
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.apiKey = apiKey;
        this.model = (model == null || model.isBlank()) ? "deepseek-v4-flash" : model;
    }

    @Override
    public String summarize(String content) {
        if (content == null || content.isBlank()) return "";
        String prompt = "请用简洁的语言总结以下内容，不超过150字：\n\n" + content;
        return chatOne(prompt);
    }

    @Override
    public RiskResult moderate(String content) {
        if (content == null || content.isBlank()) return new RiskResult(0, "");
        String prompt = "请审核以下内容，判断是否包含违规信息（如暴力、色情、诈骗、人身攻击等）。请回复JSON格式：{\"level\": 0-2, \"reason\": \"原因\"}，其中0=安全，1=疑似，2=违规。\n\n内容：" + content;
        String resp = chatOne(prompt);
        try {
            Map<String, Object> map = objectMapper.readValue(resp.replaceAll("```json|```", "").trim(), Map.class);
            int level = map.get("level") instanceof Integer ? (int) map.get("level") : 1;
            String reason = (String) map.getOrDefault("reason", "AI审核");
            return new RiskResult(Math.max(0, Math.min(2, level)), reason);
        } catch (Exception e) {
            return new RiskResult(0, "");
        }
    }

    @Override
    public List<String> recommendTags(String title, String content) {
        String text = (title != null ? title + " " : "") + (content != null ? content : "");
        if (text.isBlank()) return List.of();
        String prompt = "请为以下内容推荐3-5个简短的中文标签（逗号分隔，每个标签2-4字）：\n\n" + text;
        String resp = chatOne(prompt);
        if (resp.isBlank()) return List.of();
        List<String> tags = new ArrayList<>();
        for (String tag : resp.split("[,，]")) {
            String t = tag.trim().replaceAll("[^\\u4e00-\\u9fa5a-zA-Z0-9]", "");
            if (!t.isBlank() && t.length() <= 6) tags.add(t);
        }
        return tags.size() <= 5 ? tags : tags.subList(0, 5);
    }

    @Override
    public String chat(List<ChatMessage> messages, String context) {
        List<Map<String, String>> chatMessages = new ArrayList<>();
        if (context != null && !context.isBlank()) {
            chatMessages.add(Map.of("role", "system", "content", context));
        }
        chatMessages.add(Map.of("role", "system", "content",
                "你叫小青，是青云阁网站的 AI 助手。青云阁是当前高校学习交流社区网站的名称，不是你的名字，也不要把它解释成其他类型的网站。请友好、简洁地回答用户问题；当用户询问你的名字或身份时，明确回答你是小青。"));
        if (messages != null) {
            for (ChatMessage msg : messages) {
                chatMessages.add(Map.of("role", msg.role(), "content", msg.content()));
            }
        }
        return chatCompletion(chatMessages);
    }

    private String chatOne(String prompt) {
        List<Map<String, String>> messages = List.of(
                Map.of("role", "user", "content", prompt)
        );
        return chatCompletion(messages);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private String chatCompletion(List<Map<String, String>> messages) {
        if (apiKey == null || apiKey.isBlank()) {
            // 不向用户暴露"未配置 API Key"细节，统一脱敏错误
            return AI_UPSTREAM_ERROR_MESSAGE;
        }
        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "messages", messages,
                    "temperature", 0.3,
                    "max_tokens", 500
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            ResponseEntity<Map> resp = restTemplate.exchange(
                    baseUrl + "/chat/completions",
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    Map.class);

            if (resp.getBody() != null) {
                List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.getBody().get("choices");
                if (choices != null && !choices.isEmpty()) {
                    Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                    if (message != null) {
                        return (String) message.getOrDefault("content", "");
                    }
                }
            }
        } catch (RestClientResponseException e) {
            // 安全加固（缺陷 1.18）：不再向客户端回传上游 HTTP 状态码，
            // 避免攻击者借响应区分"401 API Key 无效"、"402 余额不足"、"429 限流"等。
            log.warn("OpenAI API call rejected: status={}", e.getStatusCode().value());
            return AI_UPSTREAM_ERROR_MESSAGE;
        } catch (ResourceAccessException e) {
            log.warn("OpenAI API connection failed: {}", e.getClass().getSimpleName());
            return AI_UPSTREAM_ERROR_MESSAGE;
        } catch (Exception e) {
            log.warn("OpenAI API call failed: {}", e.getClass().getSimpleName());
            return AI_UPSTREAM_ERROR_MESSAGE;
        }
        return AI_UPSTREAM_ERROR_MESSAGE;
    }

    private static RestTemplate createRestTemplate() {
        // 使用 SafeHttpClient 防 SSRF / DNS 重绑定：连接阶段二次校验目标 IP，命中私网即终止
        return SafeHttpClient.build(CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
    }

    private static String normalizeBaseUrl(String value) {
        if (value == null || value.isBlank()) {
            return "https://api.deepseek.com/v1";
        }
        String trimmed = value.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.endsWith("/v1") ? trimmed : trimmed + "/v1";
    }
}
