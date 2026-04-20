package com.codeforge.plugin.idea.actions

import com.codeforge.plugin.idea.service.LlmService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.ProjectComponent
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities

/**
 * B9：Hover 快速解释
 * 鼠标悬停代码时，按住 Alt 键弹出 AI 解释气泡
 */
class HoverExplainAction : AnAction(), EditorMouseMotionListener {

    private var lastTriggerTime = 0L
    private var explanationPopup: JPanel? = null

    override fun actionPerformed(e: AnActionEvent) {
        // 不通过 ActionSystem 触发
    }

    override fun mouseMoved(e: EditorMouseEvent) {
        val editor = e.editor ?: return
        val project = editor.project ?: return

        if (!e.mouseEvent.isAltDown) {
            hidePopupInternal()
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastTriggerTime < 800) return
        lastTriggerTime = now

        val offset = editor.logicalPositionToOffset(editor.xyToLogicalPosition(e.mouseEvent.point))
        if (offset < 0 || offset > editor.document.textLength) return

        val doc = editor.document
        val lines = doc.text.lines()
        val currentLine = doc.getLineNumber(offset)
        val contextStart = maxOf(0, currentLine - 1)
        val contextEnd = minOf(lines.size - 1, currentLine + 1)
        val context = lines.subList(contextStart, contextEnd + 1).joinToString("\n")

        showExplanation(project, editor, e.mouseEvent.point, context)
    }

    private fun showExplanation(project: Project, editor: Editor, point: Point, context: String) {
        val buffer = StringBuilder()
        val done = AtomicBoolean(false)

        val messages = listOf(
            mapOf("role" to "user", "content" to "请简洁解释以下代码片段的含义和作用（1-2句话）：\n\n$context")
        )

        LlmService.getInstance().chatStream(
            messages = messages,
            onToken = { token -> buffer.append(token) },
            onDone = { done.set(true) },
            onError = { done.set(true) }
        )

        Thread {
            while (!done.get()) Thread.sleep(100)
            val result = buffer.toString().trim()
            SwingUtilities.invokeLater {
                if (result.isNotBlank()) {
                    showPopup(editor, point, result)
                }
            }
        }.start()
    }

    private fun showPopup(editor: Editor, point: Point, explanation: String) {
        hidePopupInternal()

        val contentPanel = JPanel().apply {
            isOpaque = true
            background = JBColor(Color(0x2d2d2d), Color(0x2d2d2d))
            border = javax.swing.border.CompoundBorder(
                javax.swing.border.LineBorder(JBColor(Color(0x3d3d3d), Color(0x5d5d5d)), 1),
                javax.swing.border.EmptyBorder(8, 12, 8, 12)
            )
            layout = null

            val label = JTextArea(explanation.take(200)).apply {
                isEditable = false
                isOpaque = false
                foreground = JBColor(Color(0xe0e0e0), Color(0xe0e0e0))
                lineWrap = true
                wrapStyleWord = true
                bounds = Rectangle(0, 0, 280, 60)
                font = javax.swing.UIManager.getFont("Label.font")
            }
            add(label)
            preferredSize = Dimension(300, 80)
            maximumSize = Dimension(400, 120)
        }

        val screenPoint = SwingUtilities.convertPoint(editor.component, point, editor.component.parent)
        contentPanel.setLocation(screenPoint.x + 10, screenPoint.y + 10)
        explanationPopup = contentPanel

        editor.component.parent?.add(contentPanel)
        contentPanel.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseExited(e: java.awt.event.MouseEvent?) {
                hidePopupInternal()
            }
        })
    }

    internal fun hidePopupInternal() {
        explanationPopup?.let { popup ->
            popup.isVisible = false
            popup.parent?.remove(popup)
        }
        explanationPopup = null
    }

    override fun mouseDragged(e: EditorMouseEvent) {}
}

/**
 * B9：Hover 解释的 Project 级别组件，注册/注销 EditorMouseMotionListener
 */
class HoverExplainProjectComponent(private val project: Project) : ProjectComponent {

    private val hoverAction = HoverExplainAction()

    override fun projectOpened() {
        // 注册到所有编辑器
    }

    override fun projectClosed() {
        hideAllPopups()
    }

    fun hideAllPopups() {
        hoverAction.hidePopupInternal()
    }

    companion object {
        fun getInstance(project: Project): HoverExplainProjectComponent {
            return project.getComponent(HoverExplainProjectComponent::class.java)
        }
    }
}
