package com.codeforge.plugin.idea.diff

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * 多文件变更确认面板（Swing DialogWrapper）
 *
 * 布局：
 * ┌─────────────────────────────────────────────────────────┐
 * │  ⚡ CodeForge — Agent 完成任务，共修改 N 个文件            │
 * ├─────────────────────────────────────────────────────────┤
 * │  文件名              状态   变更量     操作               │
 * │  📝 Foo.kt          修改   +45 -12  [接受] [拒绝]        │
 * │  🆕 Bar.kt          新建   +120     [接受] [拒绝]        │
 * │  📝 build.gradle    修改   +2 -1    [接受] [拒绝]        │
 * ├─────────────────────────────────────────────────────────┤
 * │              [✅ 接受全部]  [✗ 拒绝全部]  [关闭]          │
 * └─────────────────────────────────────────────────────────┘
 */
object MultiFileDiffPanel {

    // ── 颜色常量 ─────────────────────────────────────────────
    private val COLOR_BG         = Color(0x2B, 0x2D, 0x30)
    private val COLOR_ROW_BG     = Color(0x31, 0x33, 0x36)
    private val COLOR_ROW_ALT    = Color(0x2B, 0x2D, 0x30)
    private val COLOR_BORDER     = Color(0x44, 0x44, 0x44)
    private val COLOR_TEXT       = Color(0xBB, 0xBB, 0xBB)
    private val COLOR_TEXT_DIM   = Color(0x77, 0x77, 0x77)
    private val COLOR_ADD        = Color(0x57, 0xA6, 0x5A)
    private val COLOR_DEL        = Color(0xC0, 0x3B, 0x3B)
    private val COLOR_NEW_BADGE  = Color(0x1A, 0x5C, 0x8A)
    private val COLOR_BTN_ACCEPT = Color(0x2E, 0x7D, 0x32)
    private val COLOR_BTN_REJECT = Color(0xC6, 0x28, 0x28)
    private val COLOR_BTN_VIEW   = Color(0x37, 0x47, 0x5A)

    /**
     * 显示多文件确认对话框
     *
     * @param project       当前项目
     * @param session       多文件 diff session（含所有 patch）
     * @param onAcceptAll   用户点击"接受全部"时的回调
     * @param onRejectAll   用户点击"拒绝全部"时的回调
     * @param onAcceptFile  用户对单文件点击"接受"时的回调，参数为 relativePath
     * @param onRejectFile  用户对单文件点击"拒绝"时的回调，参数为 relativePath
     * @param onViewFile    用户点击文件名查看 Inline Diff 时的回调，参数为 relativePath
     */
    fun show(
        project: Project,
        session: MultiFileDiffManager.MultiFileDiffSession,
        onAcceptAll:  () -> Unit,
        onRejectAll:  () -> Unit,
        onAcceptFile: (String) -> Unit,
        onRejectFile: (String) -> Unit,
        onViewFile:   (String) -> Unit
    ) {
        val dialog = MultiFileDiffDialog(
            project, session,
            onAcceptAll, onRejectAll,
            onAcceptFile, onRejectFile, onViewFile
        )
        dialog.show()
    }

    // ─────────────────────────────────────────────────────────
    // 内部：DialogWrapper 实现
    // ─────────────────────────────────────────────────────────

    private class MultiFileDiffDialog(
        project: Project,
        private val session: MultiFileDiffManager.MultiFileDiffSession,
        private val onAcceptAll:  () -> Unit,
        private val onRejectAll:  () -> Unit,
        private val onAcceptFile: (String) -> Unit,
        private val onRejectFile: (String) -> Unit,
        private val onViewFile:   (String) -> Unit
    ) : DialogWrapper(project, true) {

        // 每行的状态标签（用于点击 Accept/Reject 后实时更新）
        private val rowStatusLabels = mutableMapOf<String, JLabel>()
        private val rowAcceptBtns   = mutableMapOf<String, JButton>()
        private val rowRejectBtns   = mutableMapOf<String, JButton>()

        init {
            title = "CodeForge — Agent 文件变更确认"
            isModal = false          // 非模态，允许用户同时看编辑器
            setSize(760, 480)
            init()
        }

        override fun createCenterPanel(): JComponent {
            val root = JPanel(BorderLayout(0, 0))
            root.background = COLOR_BG
            root.border = EmptyBorder(0, 0, 0, 0)

            // ── 顶部标题栏 ──────────────────────────────────────
            root.add(buildHeader(), BorderLayout.NORTH)

            // ── 中部文件列表 ────────────────────────────────────
            root.add(buildFileList(), BorderLayout.CENTER)

            return root
        }

        override fun createSouthPanel(): JComponent {
            val panel = JPanel(FlowLayout(FlowLayout.CENTER, 12, 10))
            panel.background = COLOR_BG
            panel.border = BorderFactory.createMatteBorder(1, 0, 0, 0, COLOR_BORDER)

            val acceptAllBtn = buildBtn("✅  接受全部 (${session.patches.size})", COLOR_BTN_ACCEPT, 14f)
            acceptAllBtn.addActionListener {
                onAcceptAll()
                close(OK_EXIT_CODE)
            }

            val rejectAllBtn = buildBtn("✗  拒绝全部", COLOR_BTN_REJECT, 14f)
            rejectAllBtn.addActionListener {
                onRejectAll()
                close(CANCEL_EXIT_CODE)
            }

            val closeBtn = buildBtn("关闭", COLOR_BTN_VIEW, 13f)
            closeBtn.addActionListener { close(CANCEL_EXIT_CODE) }

            panel.add(acceptAllBtn)
            panel.add(rejectAllBtn)
            panel.add(Box.createHorizontalStrut(20))
            panel.add(closeBtn)

            return panel
        }

        override fun createActions() = emptyArray<Action>()   // 用自定义按钮

        // ── 顶部标题 ────────────────────────────────────────────

        private fun buildHeader(): JPanel {
            val p = JPanel(FlowLayout(FlowLayout.LEFT, 16, 12))
            p.background = Color(0x23, 0x25, 0x27)
            p.border = BorderFactory.createMatteBorder(0, 0, 1, 0, COLOR_BORDER)

            val icon = JLabel("⚡")
            icon.font = icon.font.deriveFont(18f)
            p.add(icon)

            val title = JLabel("Agent 完成任务，共变更 ${session.patches.size} 个文件")
            title.font = title.font.deriveFont(Font.BOLD, 14f)
            title.foreground = Color(0xDD, 0xDD, 0xDD)
            p.add(title)

            val hint = JLabel("（点击文件名可预览 Inline Diff，点击接受/拒绝按钮可逐文件决策）")
            hint.font = hint.font.deriveFont(11f)
            hint.foreground = COLOR_TEXT_DIM
            p.add(hint)

            return p
        }

        // ── 文件列表 ─────────────────────────────────────────────

        private fun buildFileList(): JScrollPane {
            val listPanel = JPanel()
            listPanel.layout = BoxLayout(listPanel, BoxLayout.Y_AXIS)
            listPanel.background = COLOR_BG

            // 列表头
            listPanel.add(buildListHeader())

            // 每个文件一行
            session.patches.forEachIndexed { idx, patch ->
                listPanel.add(buildFileRow(patch, idx % 2 == 0))
            }
            listPanel.add(Box.createVerticalGlue())

            val scroll = JBScrollPane(listPanel)
            scroll.border = BorderFactory.createEmptyBorder()
            scroll.background = COLOR_BG
            return scroll
        }

        private fun buildListHeader(): JPanel {
            val p = JPanel(GridBagLayout())
            p.background = Color(0x23, 0x25, 0x27)
            p.border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, COLOR_BORDER),
                EmptyBorder(6, 12, 6, 12)
            )
            p.maximumSize = Dimension(Int.MAX_VALUE, 36)

            val gbc = GridBagConstraints().apply {
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(0, 4, 0, 4)
            }

            fun header(text: String, weightX: Double, anchor: Int = GridBagConstraints.WEST): JLabel {
                val l = JLabel(text)
                l.font = l.font.deriveFont(Font.BOLD, 11f)
                l.foreground = COLOR_TEXT_DIM
                return l.also {
                    gbc.weightx = weightX
                    gbc.anchor  = anchor
                    p.add(it, gbc)
                }
            }

            gbc.gridx = 0; header("文件名", 1.0)
            gbc.gridx = 1; header("状态", 0.0)
            gbc.gridx = 2; header("变更量", 0.0)
            gbc.gridx = 3; header("操作", 0.0, GridBagConstraints.EAST)

            return p
        }

        private fun buildFileRow(
            patch: MultiFileDiffManager.FilePatch,
            altBg: Boolean
        ): JPanel {
            val rowBg = if (altBg) COLOR_ROW_ALT else COLOR_ROW_BG
            val p = JPanel(GridBagLayout())
            p.background = rowBg
            p.border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, COLOR_BORDER),
                EmptyBorder(8, 12, 8, 12)
            )
            p.maximumSize = Dimension(Int.MAX_VALUE, 48)

            val gbc = GridBagConstraints().apply {
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(0, 4, 0, 4)
                anchor = GridBagConstraints.CENTER
            }

            // 文件名（可点击查看 Inline Diff）
            val fileIcon = if (patch.isNewFile) "🆕" else "📝"
            val fileName = patch.relativePath.substringAfterLast('/')
            val fileNameLabel = JLabel("$fileIcon  $fileName")
            fileNameLabel.font = fileNameLabel.font.deriveFont(12f)
            fileNameLabel.foreground = Color(0x5A, 0xAA, 0xF5)
            fileNameLabel.toolTipText = patch.relativePath
            fileNameLabel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            fileNameLabel.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    onViewFile(patch.relativePath)
                }
                override fun mouseEntered(e: java.awt.event.MouseEvent) {
                    fileNameLabel.foreground = Color(0x79, 0xC0, 0xFF)
                }
                override fun mouseExited(e: java.awt.event.MouseEvent) {
                    fileNameLabel.foreground = Color(0x5A, 0xAA, 0xF5)
                }
            })
            gbc.gridx = 0; gbc.weightx = 1.0; gbc.anchor = GridBagConstraints.WEST
            p.add(fileNameLabel, gbc)

            // 状态标签（PENDING / ACCEPTED / REJECTED）
            val statusLabel = buildStatusLabel(
                session.decisions[patch.relativePath]
                    ?: MultiFileDiffManager.PatchDecision.PENDING
            )
            rowStatusLabels[patch.relativePath] = statusLabel
            gbc.gridx = 1; gbc.weightx = 0.0; gbc.anchor = GridBagConstraints.CENTER
            p.add(statusLabel, gbc)

            // 变更量 +N -M
            val (added, deleted) = patch.diffStats()
            val statsLabel = JLabel(buildStatsText(added, deleted, patch.isNewFile))
            statsLabel.font = statsLabel.font.deriveFont(Font.BOLD, 11f)
            gbc.gridx = 2
            p.add(statsLabel, gbc)

            // 操作按钮区
            val btnPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
            btnPanel.background = rowBg

            val acceptBtn = buildSmallBtn("接受", COLOR_BTN_ACCEPT)
            val rejectBtn = buildSmallBtn("拒绝", COLOR_BTN_REJECT)

            acceptBtn.addActionListener {
                onAcceptFile(patch.relativePath)
                updateRowStatus(patch.relativePath, MultiFileDiffManager.PatchDecision.ACCEPTED)
                acceptBtn.isEnabled = false
                rejectBtn.isEnabled = false
            }
            rejectBtn.addActionListener {
                onRejectFile(patch.relativePath)
                updateRowStatus(patch.relativePath, MultiFileDiffManager.PatchDecision.REJECTED)
                acceptBtn.isEnabled = false
                rejectBtn.isEnabled = false
            }

            rowAcceptBtns[patch.relativePath] = acceptBtn
            rowRejectBtns[patch.relativePath] = rejectBtn

            btnPanel.add(acceptBtn)
            btnPanel.add(rejectBtn)

            gbc.gridx = 3; gbc.anchor = GridBagConstraints.EAST
            p.add(btnPanel, gbc)

            return p
        }

        // ── 辅助：状态标签 ───────────────────────────────────────

        private fun buildStatusLabel(decision: MultiFileDiffManager.PatchDecision): JLabel {
            val (text, fg, bg) = when (decision) {
                MultiFileDiffManager.PatchDecision.PENDING  -> Triple("待确认", Color(0xBB, 0xBB, 0x00), Color(0x33, 0x33, 0x00))
                MultiFileDiffManager.PatchDecision.ACCEPTED -> Triple("✅ 已接受", Color(0x57, 0xA6, 0x5A), Color(0x1A, 0x33, 0x1A))
                MultiFileDiffManager.PatchDecision.REJECTED -> Triple("✗ 已拒绝", Color(0xC0, 0x3B, 0x3B), Color(0x33, 0x1A, 0x1A))
            }
            val l = JLabel(text)
            l.font = l.font.deriveFont(Font.BOLD, 10f)
            l.foreground = fg
            l.background = bg
            l.isOpaque = true
            l.border = EmptyBorder(2, 8, 2, 8)
            return l
        }

        private fun updateRowStatus(relativePath: String, decision: MultiFileDiffManager.PatchDecision) {
            val label = rowStatusLabels[relativePath] ?: return
            val (text, fg, bg) = when (decision) {
                MultiFileDiffManager.PatchDecision.PENDING  -> Triple("待确认", Color(0xBB, 0xBB, 0x00), Color(0x33, 0x33, 0x00))
                MultiFileDiffManager.PatchDecision.ACCEPTED -> Triple("✅ 已接受", Color(0x57, 0xA6, 0x5A), Color(0x1A, 0x33, 0x1A))
                MultiFileDiffManager.PatchDecision.REJECTED -> Triple("✗ 已拒绝", Color(0xC0, 0x3B, 0x3B), Color(0x33, 0x1A, 0x1A))
            }
            label.text = text
            label.foreground = fg
            label.background = bg
            label.repaint()
        }

        private fun buildStatsText(added: Int, deleted: Int, isNewFile: Boolean): String {
            return if (isNewFile) {
                "<html><font color='#57A65A'>+$added</font></html>"
            } else {
                val addPart = "<font color='#57A65A'>+$added</font>"
                val delPart = "<font color='#C03B3B'>-$deleted</font>"
                "<html>$addPart &nbsp; $delPart</html>"
            }
        }

        // ── 辅助：按钮构造 ───────────────────────────────────────

        private fun buildBtn(text: String, bg: Color, fontSize: Float): JButton {
            val btn = JButton(text)
            btn.font = btn.font.deriveFont(Font.BOLD, fontSize)
            btn.background = bg
            btn.foreground = Color.WHITE
            btn.isFocusPainted = false
            btn.border = EmptyBorder(8, 20, 8, 20)
            btn.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            return btn
        }

        private fun buildSmallBtn(text: String, bg: Color): JButton {
            val btn = JButton(text)
            btn.font = btn.font.deriveFont(Font.PLAIN, 11f)
            btn.background = bg
            btn.foreground = Color.WHITE
            btn.isFocusPainted = false
            btn.border = EmptyBorder(3, 10, 3, 10)
            btn.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            return btn
        }
    }
}
