package com.codeforge.plugin.llm.provider.impl;

import com.codeforge.plugin.llm.dto.ChatRequest;
import com.codeforge.plugin.llm.provider.AbstractLlmProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.openapi.diagnostic.Logger;

import java.util.List;
import java.util.Map;

/**
 * DeepSeek 提供商
 * <p>
 * 特殊处理：
 * - deepseek-reasoner 模型不支持 temperature, top_p, tools 等参数
 * - 不支持图片输入，自动降级为纯文本
 * <p>
 * 移植自 claude-api-proxy，去掉 Lombok。
 */
public class DeepSeekProvider extends AbstractLlmProvider {

    private static final Logger LOG = Logger.getInstance(DeepSeekProvider.class);

    private final String baseUrl;
    private final String apiKey;
    private final String defaultModel;

    public DeepSeekProvider(ObjectMapper objectMapper, String baseUrl, String apiKey,
                            String defaultModel, int connectTimeout, int readTimeout) {
        super(objectMapper, connectTimeout, readTimeout);
        this.baseUrl = baseUrl != null ? baseUrl : "https://api.deepseek.com";
        this.apiKey = apiKey;
        this.defaultModel = defaultModel != null ? defaultModel : "deepseek-chat";
    }

    @Override public String getName() { return "deepseek"; }
    @Override public String getDisplayName() { return "DeepSeek"; }
    @Override protected String getBaseUrl() { return baseUrl; }
    @Override protected String getApiKey() { return apiKey; }
    @Override public String getDefaultModel() { return defaultModel; }
    @Override protected String getChatCompletionsPath() { return "/v1/chat/completions"; }

    @Override
    public List<String> getAvailableModels() {
        return List.of(
            "deepseek-chat",
            "deepseek-reasoner"
        );
    }

    /**
     * DeepSeek 特殊处理:
     * - deepseek-reasoner 模型不支持 temperature, top_p, tools 等参数
     */
    @Override
    protected ChatRequest adjustRequest(ChatRequest request) {
        normalizeImagePartsForDeepSeek(request);

        if ("deepseek-reasoner".equals(request.getModel())) {
            request.setTemperature(null);
            request.setTopP(null);
            request.setTools(null);
            request.setToolChoice(null);
            request.setFrequencyPenalty(null);
            request.setPresencePenalty(null);
            LOG.debug("DeepSeek Reasoner: 已移除不支持的参数");
        }
        return request;
    }

    private void normalizeImagePartsForDeepSeek(ChatRequest request) {
        if (request == null || request.getMessages() == null) {
            return;
        }

        for (ChatRequest.ChatMessage message : request.getMessages()) {
            Object content = message.getContent();
            if (!(content instanceof List<?> parts)) {
                continue;
            }

            StringBuilder textOnly = new StringBuilder();
            boolean removedImage = false;
            for (Object part : parts) {
                if (!(part instanceof Map<?, ?> rawMap)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> partMap = (Map<String, Object>) rawMap;
                String type = partMap.get("type") instanceof String ? (String) partMap.get("type") : "";

                if ("text".equals(type) && partMap.get("text") != null) {
                    textOnly.append(partMap.get("text"));
                    continue;
                }

                if ("image_url".equals(type) || "image".equals(type)) {
                    removedImage = true;
                }
            }

            if (removedImage) {
                if (textOnly.length() == 0) {
                    textOnly.append("[用户发送了一张图片，但当前 DeepSeek 模型不支持图片输入，请根据后续文字继续回答。]");
                } else {
                    textOnly.append("\n[附带图片已忽略：当前 DeepSeek 模型不支持图片输入]");
                }
                message.setContent(textOnly.toString());
                LOG.warn("DeepSeek 请求兼容降级: 当前模型不支持图片输入，已将多模态消息降级为纯文本");
            }
        }
    }
}

