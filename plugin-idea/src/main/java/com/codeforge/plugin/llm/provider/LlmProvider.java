package com.codeforge.plugin.llm.provider;

import com.codeforge.plugin.llm.dto.ChatRequest;
import com.codeforge.plugin.llm.dto.ChatResponse;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/**
 * LLM 模型提供商统一接口
 * <p>
 * 移植自 claude-api-proxy。
 */
public interface LlmProvider {

    /** 提供商名称 (如: deepseek, qwen, openai) */
    String getName();

    /** 提供商显示名称 */
    String getDisplayName();

    /** 获取默认模型名 */
    String getDefaultModel();

    /** 获取当前正在使用的模型名 */
    String getCurrentModel();

    /** 设置当前使用的模型名 (运行时切换) */
    void setCurrentModel(String model);

    /** 获取该提供商支持的模型列表 */
    List<String> getAvailableModels();

    /** 非流式调用 */
    ChatResponse chatCompletion(ChatRequest request) throws IOException;

    /** 流式调用 */
    void chatCompletionStream(ChatRequest request,
                               Consumer<String> onEvent,
                               Runnable onComplete,
                               Consumer<Throwable> onError);
}

