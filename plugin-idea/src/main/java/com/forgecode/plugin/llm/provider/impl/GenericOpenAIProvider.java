package com.forgecode.plugin.llm.provider.impl;

import com.forgecode.plugin.llm.provider.AbstractLlmProvider;
import com.forgecode.plugin.llm.provider.ProviderRegistry.ProviderMeta;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.openapi.diagnostic.Logger;

import java.util.List;

/**
 * 通用 OpenAI 兼容提供商
 * <p>
 * 适用于所有遵循 OpenAI Chat Completions 接口规范的提供商。
 * 由 ProviderMeta + 用户配置驱动，无需为每个提供商创建独立类。
 * <p>
 * 移植自 claude-api-proxy，去掉 Lombok。
 */
public class GenericOpenAIProvider extends AbstractLlmProvider {

    private static final Logger LOG = Logger.getInstance(GenericOpenAIProvider.class);

    private final String name;
    private final String displayName;
    private final String baseUrl;
    private final String apiKey;
    private final String chatPath;
    private final String defaultModel;
    private final List<String> availableModels;

    public GenericOpenAIProvider(ObjectMapper objectMapper,
                                 String name,
                                 String displayName,
                                 String baseUrl,
                                 String apiKey,
                                 String chatPath,
                                 String defaultModel,
                                 List<String> availableModels,
                                 int connectTimeout,
                                 int readTimeout) {
        super(objectMapper, connectTimeout, readTimeout);
        this.name = name;
        this.displayName = displayName;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.chatPath = chatPath != null ? chatPath : "/v1/chat/completions";
        this.defaultModel = defaultModel;
        this.availableModels = availableModels != null ? availableModels : List.of(defaultModel);
    }

    /**
     * 从 ProviderMeta + 用户配置创建实例
     */
    public static GenericOpenAIProvider fromMeta(ObjectMapper objectMapper,
                                                  ProviderMeta meta,
                                                  String apiKey,
                                                  String customBaseUrl,
                                                  String currentModel,
                                                  int connectTimeout,
                                                  int readTimeout) {
        String baseUrl = customBaseUrl != null ? customBaseUrl : meta.defaultBaseUrl();
        String model = currentModel != null ? currentModel : meta.defaultModel();

        GenericOpenAIProvider provider = new GenericOpenAIProvider(
                objectMapper, meta.name(), meta.displayName(),
                baseUrl, apiKey, meta.chatPath(),
                meta.defaultModel(), meta.models(),
                connectTimeout, readTimeout
        );
        provider.setCurrentModel(model);
        return provider;
    }

    /**
     * 创建自定义提供商实例（无 ProviderMeta）
     */
    public static GenericOpenAIProvider custom(ObjectMapper objectMapper,
                                               String name,
                                               String displayName,
                                               String baseUrl,
                                               String apiKey,
                                               String chatPath,
                                               String defaultModel,
                                               List<String> models,
                                               int connectTimeout,
                                               int readTimeout) {
        return new GenericOpenAIProvider(
                objectMapper, name,
                displayName != null ? displayName : name,
                baseUrl, apiKey, chatPath,
                defaultModel, models,
                connectTimeout, readTimeout
        );
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String getDefaultModel() {
        return defaultModel;
    }

    @Override
    protected String getBaseUrl() {
        return baseUrl;
    }

    @Override
    protected String getApiKey() {
        return apiKey;
    }

    @Override
    protected String getChatCompletionsPath() {
        return chatPath;
    }

    @Override
    public List<String> getAvailableModels() {
        return availableModels;
    }
}
