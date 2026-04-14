package com.forgecode.plugin.llm;

import com.forgecode.plugin.llm.provider.LlmProvider;
import com.forgecode.plugin.llm.provider.ProviderRegistry;
import com.forgecode.plugin.llm.provider.ProviderRegistry.ProviderMeta;
import com.forgecode.plugin.llm.provider.impl.DeepSeekProvider;
import com.forgecode.plugin.llm.provider.impl.GenericOpenAIProvider;
import com.forgecode.plugin.llm.provider.impl.MiniMaxProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.openapi.diagnostic.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM Provider 工厂/管理器
 * <p>
 * 根据用户配置动态创建和管理 Provider 实例。
 * 支持运行时增删改 Provider，无需重启插件。
 * <p>
 * 移植自 claude-api-proxy 的 LlmProviderFactory，
 * 去掉 Spring 依赖，改为手动初始化。
 */
public class ProviderManager {

    private static final Logger LOG = Logger.getInstance(ProviderManager.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 所有已初始化的 Provider 实例 */
    private final Map<String, LlmProvider> providers = new ConcurrentHashMap<>();

    /** 当前激活的提供商名称 */
    private volatile String activeProviderName;

    /** 当前激活的模型名称 */
    private volatile String activeModelName;

    /**
     * 根据用户配置初始化所有 Provider
     *
     * @param configs        各提供商配置
     * @param activeProvider 当前激活的提供商
     * @param activeModel    当前激活的模型
     */
    public void init(Map<String, ProviderConfig> configs,
                     String activeProvider, String activeModel) {
        providers.clear();
        this.activeProviderName = activeProvider;
        this.activeModelName = activeModel;

        if (configs == null || configs.isEmpty()) {
            LOG.warn("⚠️ 尚未配置任何提供商，请在设置页配置 API Key");
            return;
        }

        for (Map.Entry<String, ProviderConfig> entry : configs.entrySet()) {
            String name = entry.getKey();
            ProviderConfig pc = entry.getValue();

            if (!pc.enabled) {
                LOG.info("提供商 [" + name + "] 已禁用, 跳过");
                continue;
            }
            if (pc.apiKey == null || pc.apiKey.isBlank()) {
                LOG.info("提供商 [" + name + "] 未配置 API Key, 跳过");
                continue;
            }

            try {
                LlmProvider provider = createProvider(name, pc);
                providers.put(name, provider);
                LOG.info("✅ 提供商 [" + name + "] (" + provider.getDisplayName() +
                        ") 初始化成功, 模型: " + provider.getCurrentModel());
            } catch (Exception e) {
                LOG.error("❌ 提供商 [" + name + "] 初始化失败: " + e.getMessage(), e);
            }
        }

        // 检查激活的提供商是否可用
        if (activeProvider != null && !activeProvider.isEmpty() && providers.containsKey(activeProvider)) {
            LOG.info("🚀 当前激活提供商: [" + activeProvider + "] (" +
                    providers.get(activeProvider).getDisplayName() + ")");
        } else if (!providers.isEmpty()) {
            // activeProvider 为空或不可用，自动选择第一个可用的
            String firstAvailable = providers.keySet().iterator().next();
            this.activeProviderName = firstAvailable;
            this.activeModelName = providers.get(firstAvailable).getCurrentModel();
            LOG.info("🚀 自动选择第一个可用提供商: [" + firstAvailable + "] (" +
                    providers.get(firstAvailable).getDisplayName() + "), 模型: " + activeModelName);
        } else {
            LOG.warn("⚠️ 尚未配置任何可用的提供商");
        }
    }

    /**
     * 重新加载单个 Provider（配置变更时调用）
     */
    public void reloadProvider(String name, ProviderConfig pc) {
        providers.remove(name);

        if (pc == null || !pc.enabled || pc.apiKey == null || pc.apiKey.isBlank()) {
            LOG.info("提供商 [" + name + "] 已移除或未配置");
            return;
        }

        try {
            LlmProvider provider = createProvider(name, pc);
            providers.put(name, provider);
            LOG.info("🔄 提供商 [" + name + "] (" + provider.getDisplayName() +
                    ") 已重新加载, 模型: " + provider.getCurrentModel());
        } catch (Exception e) {
            LOG.error("❌ 提供商 [" + name + "] 重新加载失败: " + e.getMessage(), e);
        }
    }

    /**
     * 移除单个 Provider
     */
    public void removeProvider(String name) {
        providers.remove(name);
        LOG.info("🗑️ 提供商 [" + name + "] 已移除");
    }

    // ==================== 查询 ====================

    /**
     * 获取当前激活的 Provider
     *
     * @return 当前激活的 Provider，如果未配置或不可用则返回 null
     */
    public LlmProvider getActiveProvider() {
        if (activeProviderName == null || activeProviderName.isEmpty()) {
            return null;
        }
        return providers.get(activeProviderName);
    }

    /**
     * 获取当前激活的提供商名称
     */
    public String getActiveProviderName() {
        return activeProviderName;
    }

    /**
     * 获取当前激活的模型名称
     */
    public String getActiveModelName() {
        return activeModelName;
    }

    /**
     * 根据名称获取 Provider
     */
    public LlmProvider getProvider(String name) {
        return providers.get(name);
    }

    /**
     * 获取所有可用的 Provider
     */
    public Map<String, LlmProvider> getAllProviders() {
        return Map.copyOf(providers);
    }

    /**
     * 切换激活的提供商和模型
     */
    public void switchProvider(String providerName, String model) {
        if (!providers.containsKey(providerName)) {
            throw new IllegalArgumentException("提供商 [" + providerName + "] 不可用");
        }
        LlmProvider provider = providers.get(providerName);
        if (model != null && !model.isBlank()) {
            provider.setCurrentModel(model);
        }
        this.activeProviderName = providerName;
        this.activeModelName = provider.getCurrentModel();
        LOG.info("🔄 已切换到: [" + providerName + "] / " + provider.getCurrentModel());
    }

    // ==================== 创建 Provider ====================

    /**
     * 根据名称和配置创建 Provider 实例
     */
    private LlmProvider createProvider(String name, ProviderConfig pc) {
        ProviderMeta meta = ProviderRegistry.get(name);

        // 有特殊逻辑的内置提供商
        if (meta != null && meta.hasSpecialLogic()) {
            return createSpecialProvider(name, meta, pc);
        }

        // 内置提供商（通用 OpenAI 兼容）
        if (meta != null) {
            return GenericOpenAIProvider.fromMeta(
                    objectMapper, meta,
                    pc.apiKey,
                    pc.baseUrl,
                    pc.currentModel,
                    pc.connectTimeout,
                    pc.readTimeout
            );
        }

        // 自定义提供商
        return GenericOpenAIProvider.custom(
                objectMapper, name,
                pc.displayName,
                pc.baseUrl,
                pc.apiKey,
                pc.chatPath,
                pc.currentModel != null ? pc.currentModel : "default",
                pc.models,
                pc.connectTimeout,
                pc.readTimeout
        );
    }

    /**
     * 创建有特殊逻辑的 Provider
     */
    private LlmProvider createSpecialProvider(String name, ProviderMeta meta, ProviderConfig pc) {
        String baseUrl = pc.baseUrl != null ? pc.baseUrl : meta.defaultBaseUrl();
        String model = pc.currentModel != null ? pc.currentModel : meta.defaultModel();

        return switch (name) {
            case "deepseek" -> {
                DeepSeekProvider provider = new DeepSeekProvider(
                        objectMapper, baseUrl, pc.apiKey,
                        meta.defaultModel(),
                        pc.connectTimeout, pc.readTimeout
                );
                provider.setCurrentModel(model);
                yield provider;
            }
            case "minimax" -> {
                MiniMaxProvider provider = new MiniMaxProvider(
                        objectMapper, baseUrl, pc.apiKey,
                        meta.defaultModel(), meta.models(),
                        pc.connectTimeout, pc.readTimeout
                );
                provider.setCurrentModel(model);
                yield provider;
            }
            default -> GenericOpenAIProvider.fromMeta(
                    objectMapper, meta, pc.apiKey,
                    pc.baseUrl, model,
                    pc.connectTimeout, pc.readTimeout
            );
        };
    }

    // ==================== Provider 配置数据类 ====================

    /**
     * 单个提供商的配置信息
     * <p>
     * 由 Kotlin 层的 ForgeSettings 传入，简化版本（公开字段，无 Lombok）。
     */
    public static class ProviderConfig {
        /** 是否启用 */
        public boolean enabled = true;
        /** API Key */
        public String apiKey;
        /** 自定义 Base URL（为 null 则使用内置默认值） */
        public String baseUrl;
        /** 当前选中的模型 */
        public String currentModel;
        /** 自定义显示名（仅自定义提供商使用） */
        public String displayName;
        /** 自定义模型列表（仅自定义提供商使用） */
        public List<String> models;
        /** 自定义 chat 路径（仅自定义提供商使用） */
        public String chatPath;
        /** 连接超时(秒) */
        public int connectTimeout = 30;
        /** 读取超时(秒) */
        public int readTimeout = 120;

        public ProviderConfig() {
        }

        public ProviderConfig(String apiKey) {
            this.apiKey = apiKey;
        }
    }
}
