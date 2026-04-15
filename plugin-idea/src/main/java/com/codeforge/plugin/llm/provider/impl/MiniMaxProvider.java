package com.codeforge.plugin.llm.provider.impl;

import com.codeforge.plugin.llm.dto.ChatRequest;
import com.codeforge.plugin.llm.provider.AbstractLlmProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.openapi.diagnostic.Logger;

import java.util.List;

/**
 * MiniMax 提供商（OpenAI 兼容接口）
 * https://platform.minimaxi.com/docs/api-reference/text-openai-api
 *
 * base_url : https://api.minimaxi.chat
 * path     : /v1/chat/completions
 * 认证      : Authorization: Bearer {apiKey}
 * SSE 格式  : 标准 OpenAI，choices[0].delta.content
 */
public class MiniMaxProvider extends AbstractLlmProvider {

    private static final Logger LOG = Logger.getInstance(MiniMaxProvider.class);
    private static final String DEFAULT_BASE_URL = "https://api.minimaxi.chat";

    private final String baseUrl;
    private final String apiKey;
    private final String defaultModel;
    private final List<String> availableModels;

    public MiniMaxProvider(ObjectMapper objectMapper,
                           String baseUrl,
                           String apiKey,
                           String defaultModel,
                           List<String> availableModels,
                           int connectTimeout,
                           int readTimeout) {
        super(objectMapper, connectTimeout, readTimeout);
        this.baseUrl    = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : DEFAULT_BASE_URL;
        this.apiKey     = apiKey;
        this.defaultModel   = defaultModel;
        this.availableModels = availableModels != null ? availableModels : List.of(defaultModel);
    }

    @Override public String getName()          { return "minimax"; }
    @Override public String getDisplayName()   { return "MiniMax"; }
    @Override public String getDefaultModel()  { return defaultModel; }
    @Override protected String getBaseUrl()    { return baseUrl; }
    @Override protected String getApiKey()     { return apiKey; }
    @Override protected String getChatCompletionsPath() { return "/v1/chat/completions"; }
    @Override public List<String> getAvailableModels() { return availableModels; }
    // MiniMax Content-Type 可能不是标准 text/event-stream，不报错
    @Override protected boolean shouldHandleSseNonEventStreamAsError() { return false; }

    /**
     * MiniMax 参数兼容处理（根据官方注意事项）：
     * 1. temperature 必须在 (0.0, 1.0]，超出范围会报错
     * 2. presence_penalty / frequency_penalty / logit_bias 会被忽略，直接清除避免干扰
     * 3. tools 是支持的，不需要移除
     * 4. function_call 旧字段废弃，清除
     */
    @Override
    protected ChatRequest adjustRequest(ChatRequest request) {
        if (request == null) return null;

        // temperature 限制在 (0.0, 1.0]
        if (request.getTemperature() != null) {
            double t = request.getTemperature();
            if (t <= 0.0 || t > 1.0) {
                LOG.debug("[minimax] temperature=" + t + " 超出范围，修正为 1.0");
                request.setTemperature(1.0);
            }
        }

        // 清理 MiniMax 会忽略/不支持的字段
        request.setPresencePenalty(null);
        request.setFrequencyPenalty(null);

        // 清理空的 tool_choice
        Object tc = request.getToolChoice();
        if (tc instanceof String s && s.isBlank()) {
            request.setToolChoice(null);
        }
        if (request.getTools() != null && request.getTools().isEmpty()) {
            request.setTools(null);
            request.setToolChoice(null);
        }

        return request;
    }
}

