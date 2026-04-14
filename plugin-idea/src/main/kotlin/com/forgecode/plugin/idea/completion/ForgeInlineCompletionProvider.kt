package com.forgecode.plugin.idea.completion

import com.forgecode.plugin.idea.service.LlmService
import com.forgecode.plugin.idea.settings.ForgeSettings
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.InlineCompletionSuggestion
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.openapi.diagnostic.logger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Forge Code 行内代码补全 Provider
 * 兼容 IntelliJ 2024.1（API: InlineCompletionSuggestion.withFlow / .empty()）
 */
class ForgeInlineCompletionProvider : InlineCompletionProvider {

    private val log = logger<ForgeInlineCompletionProvider>()

    override val id: InlineCompletionProviderID
        get() = InlineCompletionProviderID("com.forgecode.inline")

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        if (!ForgeSettings.getInstance().inlineCompletionEnabled) return false
        return event is InlineCompletionEvent.DocumentChange
    }

    override suspend fun getSuggestion(request: InlineCompletionRequest): InlineCompletionSuggestion {
        if (!ForgeSettings.getInstance().inlineCompletionEnabled) {
            return InlineCompletionSuggestion.empty()
        }

        val document = request.document
        val offset = request.endOffset
        if (offset <= 0 || offset > document.textLength) {
            return InlineCompletionSuggestion.empty()
        }

        val fullText = document.text
        val prefix = buildPrefix(fullText, offset)
        val suffix = buildSuffix(fullText, offset)
        if (prefix.isBlank()) return InlineCompletionSuggestion.empty()

        val prompt = buildFimPrompt(prefix, suffix, request.file?.name ?: "")
        log.debug("触发行内补全，前缀长度=${prefix.length}")

        return InlineCompletionSuggestion.withFlow {
            val llmService = LlmService.getInstance()
            val resultBuffer = StringBuilder()
            val done = AtomicBoolean(false)

            val messages = listOf(
                mapOf<String, Any>(
                    "role" to "system",
                    "content" to "你是代码补全助手。直接输出补全内容，不要解释，不要用代码块包裹。"
                ),
                mapOf<String, Any>("role" to "user", "content" to prompt)
            )

            llmService.chatStream(
                messages = messages,
                onToken = { token -> resultBuffer.append(token) },
                onDone = { done.set(true) },
                onError = { done.set(true) }
            )

            // 等待 LLM 返回（最多 5 秒）
            var waited = 0
            while (!done.get() && waited < 5000) {
                kotlinx.coroutines.delay(50)
                waited += 50
            }

            val completion = postProcess(resultBuffer.toString())
            if (completion.isNotBlank()) {
                emit(InlineCompletionGrayTextElement(completion))
            }
        }
    }

    private fun buildPrefix(text: String, offset: Int): String =
        text.substring(0, offset.coerceAtMost(text.length))
            .lines().takeLast(60).joinToString("\n")

    private fun buildSuffix(text: String, offset: Int): String =
        (if (offset < text.length) text.substring(offset) else "")
            .lines().take(20).joinToString("\n")

    private fun buildFimPrompt(prefix: String, suffix: String, fileName: String): String {
        val hint = if (fileName.isNotBlank()) "文件: $fileName\n" else ""
        return "${hint}补全 <FILL> 处的代码（只输出补全内容）:\n\n$prefix<FILL>$suffix"
    }

    private fun postProcess(raw: String): String =
        raw.trim()
            .removePrefix("```").removeSuffix("```").trim()
            .lines().take(10).joinToString("\n")
}
