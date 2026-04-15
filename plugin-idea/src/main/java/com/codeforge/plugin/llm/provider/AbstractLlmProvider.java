package com.codeforge.plugin.llm.provider;

import com.codeforge.plugin.llm.dto.ChatRequest;
import com.codeforge.plugin.llm.dto.ChatResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.openapi.diagnostic.Logger;
import okhttp3.*;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * LLM Provider 抽象基类
 * <p>
 * 移植自 claude-api-proxy，去掉 Lombok @Slf4j，改用 IntelliJ Logger。
 */
public abstract class AbstractLlmProvider implements LlmProvider {

    private static final Logger LOG = Logger.getInstance(AbstractLlmProvider.class);

    protected final ObjectMapper objectMapper;
    protected final OkHttpClient httpClient;

    /** 当前选中的模型 (可运行时切换) */
    private volatile String currentModel;

    public AbstractLlmProvider(ObjectMapper objectMapper, int connectTimeout, int readTimeout) {
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(connectTimeout, TimeUnit.SECONDS)
                .readTimeout(readTimeout, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    protected abstract String getBaseUrl();
    protected abstract String getApiKey();

    protected String getChatCompletionsPath() {
        return "/chat/completions";
    }

    protected String getFullUrl() {
        return getBaseUrl() + getChatCompletionsPath();
    }

    protected ChatRequest adjustRequest(ChatRequest request) {
        return request;
    }

    protected Request.Builder buildHttpRequest(String jsonBody) {
        return new Request.Builder()
                .url(getFullUrl())
                .addHeader("Authorization", "Bearer " + getApiKey())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json")));
    }

    protected boolean shouldHandleSseNonEventStreamAsError() {
        return false;
    }

    private boolean isEventStreamContentType(Response response) {
        if (response == null) {
            return false;
        }
        String contentType = response.header("Content-Type", "");
        return contentType != null && contentType.toLowerCase().startsWith("text/event-stream");
    }

    private String safeReadBody(Response response) {
        if (response == null || response.body() == null) {
            return "";
        }
        try {
            return response.body().string();
        } catch (IOException ignored) {
            return "";
        }
    }

    // ==================== 模型管理 ====================

    @Override
    public String getCurrentModel() {
        return currentModel != null ? currentModel : getDefaultModel();
    }

    @Override
    public void setCurrentModel(String model) {
        this.currentModel = model;
        LOG.info("[" + getName() + "] 模型已切换为: " + model);
    }

    /**
     * 子类应覆写此方法，返回该提供商支持的模型列表
     */
    @Override
    public abstract List<String> getAvailableModels();

    // ==================== API 调用 ====================

    @Override
    public ChatResponse chatCompletion(ChatRequest request) throws IOException {
        request = adjustRequest(request);
        request.setStream(false);
        request.setModel(getCurrentModel());

        String requestBody = objectMapper.writeValueAsString(request);
        LOG.debug("[" + getName() + "] 请求 (model=" + getCurrentModel() + "): " + requestBody);

        Request httpRequest = buildHttpRequest(requestBody).build();

        try (Response response = httpClient.newCall(httpRequest).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            LOG.debug("[" + getName() + "] 响应: " + responseBody);

            if (!response.isSuccessful()) {
                LOG.error("[" + getName() + "] API 调用失败: status=" + response.code() + ", body=" + responseBody);
                throw new IOException(getName() + " API error: " + response.code() + " - " + responseBody);
            }

            return objectMapper.readValue(responseBody, ChatResponse.class);
        }
    }

    @Override
    public void chatCompletionStream(ChatRequest request,
                                      Consumer<String> onEvent,
                                      Runnable onComplete,
                                      Consumer<Throwable> onError) {
        request = adjustRequest(request);
        request.setStream(true);
        request.setModel(getCurrentModel());

        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            onError.accept(e);
            return;
        }

        LOG.debug("[" + getName() + "] 流式请求 (model=" + getCurrentModel() + "): " + requestBody);

        Request httpRequest = buildHttpRequest(requestBody)
                .addHeader("Accept", "text/event-stream")
                .build();

        EventSource.Factory factory = EventSources.createFactory(httpClient);
        factory.newEventSource(httpRequest, new EventSourceListener() {
            @Override
            public void onEvent(EventSource eventSource, String id, String type, String data) {
                LOG.trace("[" + getName() + "] SSE 事件: type=" + type + ", data=" + data);
                onEvent.accept(data);
            }

            @Override
            public void onClosed(EventSource eventSource) {
                LOG.debug("[" + getName() + "] SSE 连接关闭");
                onComplete.run();
            }

            @Override
            public void onFailure(EventSource eventSource, Throwable t, Response response) {
                String contentType = response != null ? response.header("Content-Type", "") : "";
                String errorBody = safeReadBody(response);

                if (shouldHandleSseNonEventStreamAsError() && response != null && !isEventStreamContentType(response)) {
                    String message = String.format("%s SSE 非事件流响应: status=%d, contentType=%s, body=%s",
                            getName(), response.code(), contentType, errorBody);
                    LOG.error("[" + getName() + "] " + message, t);
                    onError.accept(new IOException(message, t));
                    return;
                }

                LOG.error("[" + getName() + "] SSE 连接失败: status=" +
                        (response != null ? response.code() : -1) +
                        ", contentType=" + contentType +
                        ", body=" + errorBody, t);
                onError.accept(t != null ? t : new IOException("SSE connection failed: " + errorBody));
            }
        });
    }
}

