package com.forgecode.plugin.llm.provider;

import java.util.*;

/**
 * 内置提供商元信息注册表
 * <p>
 * 定义所有预设提供商的名称、默认 URL、支持模型等。
 * 纯静态数据，不包含用户配置（API Key 等）。
 * <p>
 * 移植自 claude-api-proxy，原样保留。
 */
public final class ProviderRegistry {

    private ProviderRegistry() {}

    /**
     * 提供商元信息
     */
    public record ProviderMeta(
            String name,            // 唯一标识，如 "deepseek"
            String displayName,     // 显示名
            String region,          // "cn" 国内 / "global" 国外
            String defaultBaseUrl,  // 默认 API 地址
            String chatPath,        // Chat completions 路径
            String defaultModel,    // 默认模型
            List<String> models,    // 支持的模型列表
            String website,         // 官网/开放平台地址
            String description,     // 简短描述
            boolean hasSpecialLogic, // 是否有特殊请求处理逻辑
            boolean supportsVision  // 是否支持图片输入（多模态视觉）
    ) {}

    /** 所有内置提供商（有序，国内优先） */
    private static final LinkedHashMap<String, ProviderMeta> REGISTRY = new LinkedHashMap<>();

    static {
        // ============================================================
        // 🇨🇳 国内模型（优先）
        // ============================================================

        register(new ProviderMeta(
                "deepseek", "DeepSeek", "cn",
                "https://api.deepseek.com", "/v1/chat/completions",
                "deepseek-chat",
                List.of("deepseek-chat", "deepseek-reasoner",
                         "deepseek-chat-v3-0324", "deepseek-r1-0528"),
                "https://platform.deepseek.com",
                "深度求索，支持深度推理模型 R1",
                true, false
        ));

        register(new ProviderMeta(
                "qwen", "通义千问 (Qwen)", "cn",
                "https://dashscope.aliyuncs.com/compatible-mode", "/v1/chat/completions",
                "qwen-max",
                List.of("qwen-max", "qwen-max-latest", "qwen-plus", "qwen-plus-latest",
                         "qwen-turbo", "qwen-turbo-latest", "qwen-long",
                         "qwen3-235b-a22b", "qwen3-32b", "qwen3-14b", "qwen3-8b",
                         "qwen2.5-omni-7b", "qwen2.5-vl-72b-instruct",
                         "qwen2.5-72b-instruct", "qwen2.5-32b-instruct", "qwen2.5-coder-32b-instruct",
                         "qwen-vl-max", "qwen-vl-max-latest", "qwen-vl-plus"),
                "https://dashscope.console.aliyun.com",
                "阿里通义千问，支持超长上下文 · qwen-vl/qwen2.5-vl 系列支持图片",
                false, true
        ));

        register(new ProviderMeta(
                "glm", "智谱 GLM", "cn",
                "https://open.bigmodel.cn/api/paas", "/v4/chat/completions",
                "glm-4-plus",
                List.of("glm-4-plus", "glm-4", "glm-4-flash", "glm-4-flash-250414",
                         "glm-4-long", "glm-4-air", "glm-4-airx", "glm-4-flashx",
                         "glm-4v", "glm-4v-plus", "glm-4v-flash",
                         "codegeex-4", "charglm-4", "emohaa"),
                "https://open.bigmodel.cn",
                "智谱 AI，GLM 系列 · glm-4v 系列支持图片 · glm-4-flash 免费",
                false, true
        ));

        register(new ProviderMeta(
                "kimi", "Kimi (Moonshot)", "cn",
                "https://api.moonshot.cn", "/v1/chat/completions",
                "moonshot-v1-128k",
                List.of("moonshot-v1-128k", "moonshot-v1-32k", "moonshot-v1-8k",
                         "kimi-latest", "k1"),
                "https://platform.moonshot.cn",
                "月之暗面 Kimi，超长上下文 128K",
                false, false
        ));

        register(new ProviderMeta(
                "minimax", "MiniMax", "cn",
                "https://api.minimax.chat", "/v1/text/chatcompletion_v2",
                "MiniMax-M2.7",
                List.of("MiniMax-M2.7", "MiniMax-M2.5", "MiniMax-Text-01",
                         "abab6.5s-chat", "abab6.5t-chat", "abab5.5-chat"),
                "https://platform.minimaxi.com",
                "MiniMax，支持超长文本和语音 · 图片自动通过文件接口上传",
                true, true
        ));

        register(new ProviderMeta(
                "ernie", "文心一言 (Ernie)", "cn",
                "https://qianfan.baidubce.com/v2", "/chat/completions",
                "ernie-4.0-8k",
                List.of("ernie-4.0-8k", "ernie-4.0-turbo-8k", "ernie-4.0-turbo-128k",
                         "ernie-3.5-8k", "ernie-3.5-128k",
                         "ernie-speed-8k", "ernie-speed-128k", "ernie-speed-pro-128k",
                         "ernie-lite-8k", "ernie-lite-pro-128k",
                         "ernie-tiny-8k", "ernie-char-8k"),
                "https://console.bce.baidu.com/qianfan",
                "百度文心大模型，ERNIE 系列 · 4.0 系列支持图片",
                false, true
        ));

        register(new ProviderMeta(
                "doubao", "豆包 (Doubao)", "cn",
                "https://ark.cn-beijing.volces.com/api", "/v3/chat/completions",
                "ep-your-endpoint-id",
                List.of("ep-your-endpoint-id"),
                "https://console.volcengine.com/ark",
                "字节跳动豆包大模型（火山引擎）· 模型名需填方舟平台的接入点ID (ep-xxx)",
                false, true
        ));

        register(new ProviderMeta(
                "yi", "零一万物 (01.AI)", "cn",
                "https://api.lingyiwanwu.com", "/v1/chat/completions",
                "yi-lightning",
                List.of("yi-lightning", "yi-large", "yi-large-turbo",
                         "yi-medium", "yi-spark", "yi-vision"),
                "https://platform.lingyiwanwu.com",
                "零一万物 Yi 系列 · yi-vision 支持图片",
                false, true
        ));

        register(new ProviderMeta(
                "baichuan", "百川 (Baichuan)", "cn",
                "https://api.baichuan-ai.com", "/v1/chat/completions",
                "Baichuan4",
                List.of("Baichuan4", "Baichuan3-Turbo", "Baichuan3-Turbo-128k",
                         "Baichuan2-Turbo"),
                "https://platform.baichuan-ai.com",
                "百川智能，Baichuan 系列模型",
                false, false
        ));

        register(new ProviderMeta(
                "stepfun", "阶跃星辰 (StepFun)", "cn",
                "https://api.stepfun.com", "/v1/chat/completions",
                "step-2-16k",
                List.of("step-2-16k", "step-2-16k-exp", "step-1-256k", "step-1-128k", "step-1-32k",
                         "step-1-8k", "step-1-flash", "step-1v-8k", "step-1v-32k",
                         "step-1x-medium"),
                "https://platform.stepfun.com",
                "阶跃星辰 Step 系列 · step-1v 支持图片",
                false, true
        ));

        // ============================================================
        // 🌍 国外模型
        // ============================================================

        register(new ProviderMeta(
                "openai", "OpenAI", "global",
                "https://api.openai.com", "/v1/chat/completions",
                "gpt-4o",
                List.of("gpt-4o", "gpt-4o-mini", "gpt-4.1", "gpt-4.1-mini", "gpt-4.1-nano",
                         "gpt-4-turbo", "gpt-4",
                         "o4-mini", "o3", "o3-mini", "o1", "o1-mini",
                         "chatgpt-4o-latest"),
                "https://platform.openai.com",
                "OpenAI GPT/o 系列，全球领先 · GPT-4o 支持图片",
                true, true
        ));

        register(new ProviderMeta(
                "anthropic", "Anthropic (Claude)", "global",
                "https://api.anthropic.com", "/v1/messages",
                "claude-sonnet-4-20250514",
                List.of("claude-sonnet-4-20250514", "claude-opus-4-20250514",
                         "claude-3-7-sonnet-20250219", "claude-3-5-sonnet-20241022",
                         "claude-3-5-haiku-20241022", "claude-3-opus-20240229"),
                "https://console.anthropic.com",
                "Anthropic Claude 直连 · 全系列支持图片",
                true, true
        ));

        register(new ProviderMeta(
                "gemini", "Google Gemini", "global",
                "https://generativelanguage.googleapis.com", "/v1beta/chat/completions",
                "gemini-2.5-pro",
                List.of("gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.5-flash-lite-preview",
                         "gemini-2.0-flash", "gemini-2.0-flash-lite",
                         "gemini-1.5-pro", "gemini-1.5-flash"),
                "https://aistudio.google.com",
                "Google Gemini 系列，多模态强大 · 全系列支持图片",
                false, true
        ));

        register(new ProviderMeta(
                "mistral", "Mistral AI", "global",
                "https://api.mistral.ai", "/v1/chat/completions",
                "mistral-large-latest",
                List.of("mistral-large-latest", "mistral-medium-latest",
                         "mistral-small-latest", "codestral-latest",
                         "open-mistral-nemo", "open-mixtral-8x22b",
                         "pixtral-large-latest", "pixtral-12b-2409",
                         "mistral-embed"),
                "https://console.mistral.ai",
                "法国 Mistral AI · pixtral 系列支持图片 · codestral 编程专用",
                false, true
        ));

        register(new ProviderMeta(
                "groq", "Groq", "global",
                "https://api.groq.com/openai", "/v1/chat/completions",
                "llama-3.3-70b-versatile",
                List.of("llama-3.3-70b-versatile", "llama-3.1-70b-versatile",
                         "llama-3.1-8b-instant", "llama-3.2-90b-vision-preview",
                         "llama-3.2-11b-vision-preview", "llama-3.2-3b-preview",
                         "mixtral-8x7b-32768", "gemma2-9b-it",
                         "deepseek-r1-distill-llama-70b",
                         "llava-v1.5-7b-4096-preview"),
                "https://console.groq.com",
                "Groq 超高速推理 · llama-3.2-vision/llava 支持图片",
                false, true
        ));

        register(new ProviderMeta(
                "xai", "xAI (Grok)", "global",
                "https://api.x.ai", "/v1/chat/completions",
                "grok-3",
                List.of("grok-3", "grok-3-fast", "grok-3-mini", "grok-3-mini-fast",
                         "grok-2", "grok-2-mini",
                         "grok-2-vision-1212", "grok-beta"),
                "https://console.x.ai",
                "Elon Musk 的 xAI · Grok-3 最新旗舰 · grok-2-vision 支持图片",
                false, true
        ));
    }

    private static void register(ProviderMeta meta) {
        REGISTRY.put(meta.name(), meta);
    }

    // ==================== 查询接口 ====================

    /**
     * 获取所有内置提供商元信息（有序）
     */
    public static LinkedHashMap<String, ProviderMeta> getAll() {
        return new LinkedHashMap<>(REGISTRY);
    }

    /**
     * 根据名称获取提供商元信息
     */
    public static ProviderMeta get(String name) {
        return REGISTRY.get(name);
    }

    /**
     * 判断是否为内置提供商
     */
    public static boolean isBuiltIn(String name) {
        return REGISTRY.containsKey(name);
    }

    /**
     * 获取国内提供商列表
     */
    public static List<ProviderMeta> getCnProviders() {
        return REGISTRY.values().stream()
                .filter(m -> "cn".equals(m.region()))
                .toList();
    }

    /**
     * 获取国外提供商列表
     */
    public static List<ProviderMeta> getGlobalProviders() {
        return REGISTRY.values().stream()
                .filter(m -> "global".equals(m.region()))
                .toList();
    }

    // ==================== 自定义 Provider 动态注册 ====================

    /** 自定义 Provider 名称前缀，用于区分内置与自定义 */
    public static final String CUSTOM_PREFIX = "custom_";

    /**
     * 注册/更新自定义 OpenAI 兼容 Provider
     *
     * @param id        唯一标识（自动加 custom_ 前缀）
     * @param name      显示名称（如 "My Ollama"）
     * @param baseUrl   API 地址（如 http://localhost:11434/v1）
     * @param models    模型列表（逗号分隔或直接传 List）
     */
    public static void registerCustom(String id, String name, String baseUrl, List<String> models) {
        String key = id.startsWith(CUSTOM_PREFIX) ? id : CUSTOM_PREFIX + id;
        String defaultModel = models.isEmpty() ? "custom" : models.get(0);
        ProviderMeta meta = new ProviderMeta(
                key, name, "custom",
                baseUrl, "/v1/chat/completions",
                defaultModel, models,
                "", "自定义 OpenAI 兼容 API",
                false, false
        );
        REGISTRY.put(key, meta);
    }

    /**
     * 删除自定义 Provider
     */
    public static void removeCustom(String id) {
        String key = id.startsWith(CUSTOM_PREFIX) ? id : CUSTOM_PREFIX + id;
        REGISTRY.remove(key);
    }

    /**
     * 获取所有自定义 Provider
     */
    public static List<ProviderMeta> getCustomProviders() {
        return REGISTRY.values().stream()
                .filter(m -> "custom".equals(m.region()))
                .toList();
    }

    /**
     * 是否为自定义 Provider
     */
    public static boolean isCustom(String name) {
        return name != null && name.startsWith(CUSTOM_PREFIX);
    }
}
