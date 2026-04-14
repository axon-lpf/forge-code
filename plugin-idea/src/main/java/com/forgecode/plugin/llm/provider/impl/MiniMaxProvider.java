package com.forgecode.plugin.llm.provider.impl;

import com.forgecode.plugin.llm.dto.ChatRequest;
import com.forgecode.plugin.llm.provider.AbstractLlmProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.openapi.diagnostic.Logger;
import okhttp3.*;

import java.io.IOException;
import java.util.*;

/**
 * MiniMax 提供商
 * <p>
 * MiniMax 不支持 OpenAI 标准的 image_url Base64 格式。
 * 需要先通过 /v1/files/upload 上传图片获取 file_id，
 * 再把 content 中的 image_url 改为 {"type":"image_url","image_url":{"url":"file_id"}} 格式。
 * <p>
 * 移植自 claude-api-proxy，去掉 Lombok。
 */
public class MiniMaxProvider extends AbstractLlmProvider {

    private static final Logger LOG = Logger.getInstance(MiniMaxProvider.class);

    private final String baseUrl;
    private final String apiKey;
    private final String defaultModel;
    private final List<String> availableModels;
    private final ObjectMapper mapper;

    public MiniMaxProvider(ObjectMapper objectMapper,
                           String baseUrl,
                           String apiKey,
                           String defaultModel,
                           List<String> availableModels,
                           int connectTimeout,
                           int readTimeout) {
        super(objectMapper, connectTimeout, readTimeout);
        this.mapper = objectMapper;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
        this.availableModels = availableModels != null ? availableModels : List.of(defaultModel);
    }

    @Override public String getName() { return "minimax"; }
    @Override public String getDisplayName() { return "MiniMax"; }
    @Override public String getDefaultModel() { return defaultModel; }
    @Override protected String getBaseUrl() { return baseUrl; }
    @Override protected String getApiKey() { return apiKey; }
    @Override protected String getChatCompletionsPath() { return "/v1/text/chatcompletion_v2"; }
    @Override public List<String> getAvailableModels() { return availableModels; }
    @Override protected boolean shouldHandleSseNonEventStreamAsError() { return true; }

    /**
     * 拦截请求，将 Base64 image_url 转为 MiniMax file_id
     */
    @Override
    protected ChatRequest adjustRequest(ChatRequest request) {
        normalizeRequestForMiniMax(request);
        if (request.getMessages() == null) return request;

        for (ChatRequest.ChatMessage msg : request.getMessages()) {
            Object content = msg.getContent();
            if (content instanceof List<?> parts) {
                List<Map<String, Object>> newParts = new ArrayList<>();
                for (Object part : parts) {
                    if (part instanceof Map<?, ?> partMap) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> p = (Map<String, Object>) partMap;
                        String type = (String) p.get("type");

                        if ("image_url".equals(type)) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> imageUrl = (Map<String, Object>) p.get("image_url");
                            if (imageUrl != null) {
                                String url = (String) imageUrl.get("url");
                                if (url != null && url.startsWith("data:")) {
                                    // Base64 data URL → 上传到 MiniMax 获取 file_id
                                    try {
                                        String fileId = uploadBase64Image(url);
                                        Map<String, Object> newPart = new LinkedHashMap<>();
                                        newPart.put("type", "image_url");
                                        newPart.put("image_url", Map.of("url", fileId));
                                        newParts.add(newPart);
                                        LOG.info("[minimax] 图片已上传, file_id=" + fileId);
                                        continue;
                                    } catch (Exception e) {
                                        LOG.error("[minimax] 图片上传失败: " + e.getMessage());
                                        // 上传失败，转为文字提示
                                        Map<String, Object> textPart = new LinkedHashMap<>();
                                        textPart.put("type", "text");
                                        textPart.put("text", "[图片上传失败: " + e.getMessage() + "]");
                                        newParts.add(textPart);
                                        continue;
                                    }
                                }
                            }
                        }
                        newParts.add(p);
                    }
                }
                msg.setContent(newParts);
            }
        }
        return request;
    }

    private void normalizeRequestForMiniMax(ChatRequest request) {
        if (request == null) {
            return;
        }

        if (request.getTools() != null && request.getTools().isEmpty()) {
            request.setTools(null);
            LOG.debug("[minimax] 请求清洗: tools 为空数组，已移除");
        }

        Object toolChoice = request.getToolChoice();
        if (toolChoice instanceof String s && s.isBlank()) {
            request.setToolChoice(null);
            LOG.debug("[minimax] 请求清洗: tool_choice 为空字符串，已移除");
        }

        if (request.getTools() == null && request.getToolChoice() != null) {
            LOG.warn("[minimax] 请求修正: tools 缺失但 tool_choice 存在，已移除 tool_choice=" + request.getToolChoice());
            request.setToolChoice(null);
        }

        if (request.getTools() != null) {
            LOG.warn("[minimax] 请求兼容修正: 当前模型不稳定支持 Codex tools，已移除 tools/tool_choice/temperature/top_p 以避免 invalid chat setting");
            request.setTools(null);
            request.setToolChoice(null);
            request.setTemperature(null);
            request.setTopP(null);
            request.setFrequencyPenalty(null);
            request.setPresencePenalty(null);
        }

        if (Boolean.TRUE.equals(request.getStream())) {
            request.setStream(false);
        }
    }

    /**
     * 将 Base64 data URL 上传到 MiniMax 文件接口
     *
     * @param dataUrl 格式: data:image/png;base64,iVBOR...
     * @return file_id
     */
    private String uploadBase64Image(String dataUrl) throws IOException {
        // 解析 data URL
        int commaIndex = dataUrl.indexOf(',');
        if (commaIndex < 0) throw new IOException("Invalid data URL format");

        String meta = dataUrl.substring(0, commaIndex);
        String base64Data = dataUrl.substring(commaIndex + 1);

        // 提取 MIME 类型
        String mimeType = "image/png";
        if (meta.contains(":") && meta.contains(";")) {
            mimeType = meta.substring(meta.indexOf(':') + 1, meta.indexOf(';'));
        }

        // 确定文件扩展名
        String ext = switch (mimeType) {
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            default -> ".png";
        };

        // Base64 解码为字节
        byte[] imageBytes = Base64.getDecoder().decode(base64Data);

        LOG.debug("[minimax] 上传图片: type=" + mimeType + ", size=" + (imageBytes.length / 1024) + "KB");

        // 构建 multipart 请求
        RequestBody fileBody = RequestBody.create(imageBytes, MediaType.parse(mimeType));
        MultipartBody multipartBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "image" + ext, fileBody)
                .addFormDataPart("purpose", "vision")
                .build();

        Request uploadRequest = new Request.Builder()
                .url(baseUrl + "/v1/files/upload")
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(multipartBody)
                .build();

        try (Response response = httpClient.newCall(uploadRequest).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            LOG.debug("[minimax] 文件上传响应: " + responseBody);

            if (!response.isSuccessful()) {
                throw new IOException("MiniMax file upload failed: " + response.code() + " - " + responseBody);
            }

            // 解析响应获取 file_id
            @SuppressWarnings("unchecked")
            Map<String, Object> result = mapper.readValue(responseBody, Map.class);

            @SuppressWarnings("unchecked")
            Map<String, Object> fileInfo = (Map<String, Object>) result.get("file");
            if (fileInfo == null) {
                throw new IOException("MiniMax file upload response missing 'file' field: " + responseBody);
            }

            String fileId = (String) fileInfo.get("file_id");
            if (fileId == null || fileId.isBlank()) {
                throw new IOException("MiniMax file upload response missing 'file_id': " + responseBody);
            }

            return fileId;
        }
    }
}
