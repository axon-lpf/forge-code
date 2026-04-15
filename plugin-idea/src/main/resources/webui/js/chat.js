/**
 * chat.js — 聊天核心逻辑
 */

// ==================== 状态 ====================
const state = {
    currentMode: 'vibe',       // vibe / spec
    currentSessionId: null,
    isStreaming: false,
    messages: [],              // 当前会话消息历史
    activeProvider: '',
    activeModel: '',
    suggestionsVisible: true,
    // @文件引用
    ctxFiles: [],              // 已引用文件列表 [{path, name, content}]
    atSearching: false,        // 是否正在 @ 搜索
    atStartPos: -1             // @ 符号在输入框中的位置
};

// ==================== 初始化回调（由 Kotlin 调用）====================

/**
 * 插件初始化完成后，Kotlin 推送初始数据
 */
window.onInit = function(data) {
    console.log('[Chat] onInit:', data);
    if (!data.backendOnline) {
        document.getElementById('offline-banner').style.display = 'flex';
    }
    if (data.projectName) {
        document.getElementById('project-name').textContent = data.projectName;
    }
    state.userName = data.userName || 'You';
    if (data.activeModel) {
        state.activeProvider = data.activeProvider || '';
        state.activeModel = data.activeModel || '';
        updateModelDisplay(data.activeProvider, data.activeModel);
    }
    if (data.defaultMode) {
        selectMode(data.defaultMode);
    }
};

// ==================== 流式输出回调 ====================

/** 流开始（可由 Kotlin 显式调用，也会在首个 token 时自动触发） */
window.onStreamStart = function() {
    if (state.isStreaming) return; // 防止重复触发
    state.isStreaming = true;
    document.getElementById('send-btn').style.display = 'none';
    document.getElementById('stop-btn').style.display = 'flex';
    // 移除 thinking 动画，添加 AI 消息占位
    removeThinking();
    addAiMessagePlaceholder();
};

/** 追加一个 token */
window.appendToken = function(token) {
    // 首个 token 到达时自动触发 StreamStart
    if (!state.isStreaming) {
        window.onStreamStart();
    }
    appendToLastAiMessage(token);
};

/** 流结束 */
window.onStreamDone = function() {
    if (!state.isStreaming) return; // 防止重复
    state.isStreaming = false;
    document.getElementById('send-btn').style.display = 'flex';
    document.getElementById('stop-btn').style.display = 'none';
    removeThinking();
    finalizeLastAiMessage();
};

/** 错误 */
window.onError = function(message) {
    state.isStreaming = false;
    document.getElementById('send-btn').style.display = 'flex';
    document.getElementById('stop-btn').style.display = 'none';
    removeThinking();
    appendErrorMessage(message);
};

/** 自动重试通知 — 模型失败后自动切换到备选模型 */
window.onAutoRetry = function(failedProvider, newProvider, newModel) {
    removeThinking();
    // 在聊天界面显示切换提示
    const msgEl = document.createElement('div');
    msgEl.className = 'message system-message';
    msgEl.innerHTML = `
        <div class="retry-notice">
            <span class="retry-icon">🔄</span>
            <span><strong>${failedProvider}</strong> 不可用，已自动切换到 <strong>${newProvider} · ${newModel}</strong> 重试中...</span>
        </div>`;
    document.getElementById('messages').appendChild(msgEl);
    // 更新底部模型显示
    updateModelDisplay(newProvider, newModel);
    // 显示新的 thinking
    showThinking();
    scrollToBottom();
};

/** 模型切换成功 */
window.onModelSwitched = function(provider, model) {
    updateModelDisplay(provider, model);
};

/** 新建会话 — Kotlin 创建后通知 JS */
window.onNewSession = function(data) {
    state.messages = [];
    state.currentSessionId = data ? data.sessionId : null;
    document.getElementById('messages').innerHTML = '';
    document.getElementById('messages').style.display = 'none';
    document.getElementById('welcome-screen').style.display = 'flex';
    closeSessions();
};

/** 会话列表数据 */
window.onSessionList = function(data) {
    allSessions = data.sessions || [];
    currentSessionIdFromServer = data.currentSessionId || null;
    renderSessionList(allSessions);
};

/** 加载指定会话的历史消息 */
window.onSessionLoaded = function(data) {
    state.currentSessionId = data.sessionId;
    state.messages = [];

    // 清空并重建消息列表
    const messagesEl = document.getElementById('messages');
    messagesEl.innerHTML = '';
    document.getElementById('welcome-screen').style.display = 'none';
    messagesEl.style.display = 'flex';

    (data.messages || []).forEach(msg => {
        if (msg.role === 'user') {
            addUserMessage(msg.content);
        } else if (msg.role === 'assistant') {
            addHistoryAiMessage(msg.content);
        }
    });

    // 切换模型显示
    if (data.provider && data.model) {
        updateModelDisplay(data.provider, data.model);
    }

    closeSessions();
    scrollToBottom();
};

/** 会话标题更新（首条消息后自动命名） */
window.onSessionTitleUpdate = function(sessionId, title) {
    // 更新侧边栏中对应项的标题（如果侧边栏打开着）
    const item = document.querySelector(`.session-item[data-id="${sessionId}"] .session-title`);
    if (item) item.textContent = title;
};

// ==================== Agent 模式 ====================

// ==================== Agent 工具汇总块 ====================

// 当前会话的工具汇总容器（折叠块）
let agentSummaryEl = null;
let agentToolItems = [];  // [{tool, summary}]

/** Agent 步骤通知 */
window.onAgentStep = function(step) {
    switch (step.type) {
        case 'THINKING':
            removeThinking();
            showThinking();
            break;
        case 'TOOL_CALL':
            removeThinking();
            // toolResultB64 是 Base64 编码的工具结果，需要解码
            const toolResult = step.toolResultB64
                ? decodeURIComponent(escape(atob(step.toolResultB64)))
                : (step.toolResult || '');
            updateAgentPanel(step.toolName, step.content === 'success', toolResult);
            break;
        case 'RESPONSE':
            // 最终回复来临：先完成面板（变绿），再移除 thinking，然后等待 token 流
            finalizeAgentPanel();
            removeThinking();
            // 为接下来的 token 流创建 AI 消息气泡（含模型名 + 🔥）
            addAiMessagePlaceholder();
            state.isStreaming = true;
            document.getElementById('send-btn').style.display = 'none';
            document.getElementById('stop-btn').style.display = 'flex';
            break;
        case 'ERROR':
            finalizeAgentPanel();
            removeThinking();
            appendErrorMessage(step.content);
            break;
    }
};

// ==================== Agent 工具执行面板（唯一面板，转圈→完成）====================

const TOOL_ICONS = {
    read_file: '📄', write_file: '✏️', list_files: '📁',
    search_code: '🔍', run_terminal: '⚡'
};
const TOOL_LABELS = {
    read_file: '读取文件', write_file: '写入文件', list_files: '列出目录',
    search_code: '搜索代码', run_terminal: '执行命令'
};

// 当前 Agent 任务唯一面板（不会重复创建）
let agentPanelEl = null;
let agentPanelItems = [];

/**
 * 更新（或创建）唯一的工具执行面板
 * 执行中：转圈 + 显示当前正在执行的操作
 */
// agentStartTime 记录 Agent 开始时间，用于计算耗时
let agentStartTime = 0;

function updateAgentPanel(toolName, success, result) {
    if (agentPanelItems.length === 0) agentStartTime = Date.now();
    agentPanelItems.push({ tool: toolName, success, result });

    if (!agentPanelEl) {
        // 外层与 AI 消息对齐（含 🔥 头像 + 模型名 + 时间戳）
        const wrap = document.createElement('div');
        wrap.className = 'message ai-message agent-panel-wrap';
        const panelId = 'agent-panel-' + Date.now();
        wrap.id = panelId;
        const now = new Date();
        const timeStr = now.getHours().toString().padStart(2,'0') + ':' +
                        now.getMinutes().toString().padStart(2,'0') + ':' +
                        now.getSeconds().toString().padStart(2,'0');
        const modelName = state.activeModel || '';
        const providerName = state.activeProvider || '';
        wrap.innerHTML = `
            <div class="ai-avatar">🔥</div>
            <div class="message-content">
                <div class="cm-msg-header">
                    <span class="cm-brand">CodeForge</span>
                    <span class="cm-meta">${timeStr}${modelName ? ' · ' + providerName + ' ' + modelName : ''}</span>
                </div>
                <div class="agent-panel running" id="${panelId}-panel">
                    <div class="ap-collapse-title">
                        <span class="ap-spinner"></span>
                        <span class="ap-collapse-label">文件读取</span>
                    </div>
                    <div class="ap-thinking-text" id="${panelId}-thinking">让我先探索一下项目结构。</div>
                    <div class="ap-rows" id="${panelId}-rows"></div>
                </div>
            </div>`;
        document.getElementById('messages').appendChild(wrap);
        agentPanelEl = document.getElementById(panelId + '-panel');
    }

    // 累加新的操作行
    _appendToolRow(toolName, success, result);
    scrollToBottom();
}

/** 在面板内追加一条操作行 */
function _appendToolRow(toolName, success, result) {
    const panelId = agentPanelEl.id;
    const rowsEl = document.getElementById(panelId + '-rows');
    if (!rowsEl) return;

    const icon = TOOL_ICONS[toolName] || '🔧';
    const label = TOOL_LABELS[toolName] || toolName;
    const rowId = panelId + '-row-' + agentPanelItems.length;

    // 提取路径/关键词
    let detail = '';
    try {
        // result 通常是 "### path\n```\ncontent\n```" 或文件列表
        const firstLine = (result || '').split('\n')[0].replace(/^###\s*/, '').trim();
        detail = firstLine.length > 60 ? firstLine.slice(0, 60) + '…' : firstLine;
    } catch(e) { detail = ''; }

    // 统计行数
    const lineCount = (result || '').split('\n').length;
    const lineInfo = lineCount > 3 ? ` <span class="ap-row-lines">引用行数: 1:${lineCount}</span>` : '';

    const statusIcon = success
        ? '<span class="ap-row-ok">✓</span>'
        : '<span class="ap-row-err">✗</span>';

    const row = document.createElement('div');
    row.className = 'ap-row';
    row.id = rowId;
    row.innerHTML = `
        <div class="ap-row-header" onclick="toggleApRow('${rowId}')">
            ${statusIcon}
            <span class="ap-row-label">${label}</span>
            <span class="ap-row-detail">${escapeHtml(detail)}${lineInfo}</span>
            <span class="ap-row-arrow">∨</span>
        </div>
        <div class="ap-row-body" style="display:none">
            <pre class="ap-row-content">${escapeHtml((result||'').split('\n').slice(0,20).join('\n'))}</pre>
        </div>`;
    rowsEl.appendChild(row);
}

function toggleApRow(rowId) {
    const row = document.getElementById(rowId);
    if (!row) return;
    const body = row.querySelector('.ap-row-body');
    const arrow = row.querySelector('.ap-row-arrow');
    const isOpen = body.style.display !== 'none';
    body.style.display = isOpen ? 'none' : 'block';
    arrow.textContent = isOpen ? '∨' : '∧';
}

/**
 * 所有工具执行完成，面板切换为完成状态
 */
function finalizeAgentPanel() {
    if (!agentPanelEl) return;

    const elapsed = agentStartTime ? ((Date.now() - agentStartTime) / 1000).toFixed(1) + 's' : '';
    const count = agentPanelItems.length;

    // 更新转圈为 ✓ 折叠标题
    const collapseTitle = agentPanelEl.querySelector('.ap-collapse-title');
    if (collapseTitle) {
        const panelId = agentPanelEl.id;
        collapseTitle.onclick = () => {
            const rows = document.getElementById(panelId + '-rows');
            const thinking = document.getElementById(panelId + '-thinking');
            const arrow = collapseTitle.querySelector('.ap-collapse-arrow');
            const isOpen = rows && rows.style.display !== 'none';
            if (rows) rows.style.display = isOpen ? 'none' : 'block';
            if (thinking) thinking.style.display = isOpen ? 'none' : 'block';
            if (arrow) arrow.textContent = isOpen ? '∨' : '∧';
        };
        collapseTitle.style.cursor = 'pointer';
        collapseTitle.innerHTML = `
            <span class="ap-done-check">✓</span>
            <span class="ap-collapse-label">文件读取</span>
            <span class="ap-collapse-meta">${count} 个操作${elapsed ? ' · ' + elapsed : ''}</span>
            <span class="ap-collapse-arrow">∨</span>`;
    }
    agentPanelEl.classList.remove('running');
    agentPanelEl.classList.add('done');
    agentPanelEl = null;
    agentPanelItems = [];
    scrollToBottom();
}

function toggleAgentPanel(panelId) {
    const detail = document.getElementById('ap-detail-' + panelId);
    const arrow = document.getElementById('ap-arrow-' + panelId);
    if (!detail) return;
    const isOpen = detail.style.display !== 'none';
    detail.style.display = isOpen ? 'none' : 'block';
    if (arrow) arrow.textContent = isOpen ? '▶' : '▼';
}

/** 计划生成完成 */
window.onPlanReady = function(steps) {
    removeThinking();
    renderPlanConfirm(steps);
};

/** 计划步骤状态更新 */
window.onPlanStepUpdate = function(step) {
    const el = document.querySelector(`.plan-step[data-index="${step.index}"]`);
    if (!el) return;
    el.querySelector('.plan-step-status').textContent =
        step.status === 'DONE' ? '✅' : step.status === 'RUNNING' ? '⏳' : '❌';
    if (step.status === 'RUNNING') el.classList.add('running');
    if (step.status === 'DONE') { el.classList.remove('running'); el.classList.add('done'); }
};

/** 全部计划执行完成 */
window.onPlanAllDone = function() {
    const planEl = document.querySelector('.plan-execution');
    if (planEl) planEl.querySelector('.plan-header').textContent = '✅ 计划执行完成';
};

/** 在聊天界面插入 Agent 步骤气泡 */
function appendAgentStep(icon, content, type) {
    const el = document.createElement('div');
    el.className = `message agent-step agent-step-${type}`;
    el.innerHTML = `<span class="agent-step-icon">${icon}</span>
        <span class="agent-step-content">${content}</span>`;
    document.getElementById('messages').appendChild(el);
    scrollToBottom();
}

/** 渲染 Spec 计划确认卡片 */
function renderPlanConfirm(steps) {
    const msgEl = document.createElement('div');
    msgEl.className = 'message ai-message';

    const iconEl = document.createElement('div');
    iconEl.className = 'ai-icon';
    iconEl.textContent = '🔥';

    const bubbleEl = document.createElement('div');
    bubbleEl.className = 'bubble plan-confirm';

    const stepsHtml = steps.map(s =>
        `<div class="plan-step" data-index="${s.index}">
            <span class="plan-step-status">⏳</span>
            <div class="plan-step-info">
                <strong>步骤 ${s.index}：${escapeHtml(s.title)}</strong>
                ${s.description ? `<div class="plan-step-desc">${escapeHtml(s.description)}</div>` : ''}
            </div>
        </div>`
    ).join('');

    bubbleEl.innerHTML = `
        <div class="plan-header">📋 执行计划（${steps.length} 个步骤）</div>
        <div class="plan-steps">${stepsHtml}</div>
        <div class="plan-actions">
            <button class="plan-confirm-btn" onclick="confirmPlan(this, ${JSON.stringify(steps).replace(/"/g, '&quot;')})">
                ▶ 开始执行
            </button>
            <button class="plan-cancel-btn" onclick="this.closest('.plan-confirm').innerHTML='<span style=color:#888>已取消</span>'">
                取消
            </button>
        </div>`;

    msgEl.appendChild(iconEl);
    msgEl.appendChild(bubbleEl);
    document.getElementById('messages').appendChild(msgEl);
    scrollToBottom();
}

/** 用户点击确认执行计划 */
function confirmPlan(btn, steps) {
    btn.disabled = true;
    btn.textContent = '⏳ 执行中...';
    btn.closest('.plan-actions').querySelector('.plan-cancel-btn').disabled = true;

    // 将计划卡片变为执行状态
    const planEl = btn.closest('.plan-confirm');
    planEl.classList.add('plan-execution');
    planEl.querySelector('.plan-actions').style.display = 'none';

    const requirement = state.messages.filter(m => m.role === 'user').slice(-1)[0]?.content || '';

    bridge.send({
        type: 'executePlan',
        requirement: requirement,
        plan: steps
    });

    showThinking();
}

// ==================== Chat模式 / Agent仓库智聊 切换 ====================

/** 当前大模式：'chat' | 'agent' */
state.chatBigMode = 'agent';   // 默认 Agent 仓库智聊

/** 打开/关闭 大模式下拉菜单 */
function toggleChatModeMenu() {
    const menu = document.getElementById('chat-mode-menu');
    const isOpen = menu.style.display !== 'none';
    menu.style.display = isOpen ? 'none' : 'block';
}

/** 选择大模式 */
function selectChatMode(mode) {
    state.chatBigMode = mode;
    document.getElementById('chat-mode-menu').style.display = 'none';

    // 更新按钮菜单高亮
    document.querySelectorAll('.chat-mode-item').forEach(item => {
        item.classList.toggle('active', item.dataset.mode === mode);
    });

    // 欢迎页模式卡片显隐
    const modeCards = document.querySelector('.mode-cards');

    if (mode === 'chat') {
        // Chat 普通聊天
        document.getElementById('chat-mode-icon').textContent = '○';
        document.getElementById('chat-mode-label').textContent = 'Chat 普通聊天';
        document.getElementById('user-input').placeholder = '输入问题... (Enter 发送)';
        // 隐藏欢迎页 Vibe/Spec 模式卡片
        if (modeCards) modeCards.style.display = 'none';
    } else {
        // Agent 仓库智聊
        document.getElementById('chat-mode-icon').textContent = '</>';
        document.getElementById('chat-mode-label').textContent = 'Agent 仓库智聊';
        document.getElementById('user-input').placeholder = '描述任务，AI 将自动读取仓库、修改代码... (Enter 发送)';
        // 显示欢迎页 Vibe/Spec 模式卡片
        if (modeCards) modeCards.style.display = 'flex';
    }
}

/** 点击其他地方关闭下拉 */
document.addEventListener('click', function(e) {
    if (!e.target.closest('#chat-mode-switcher') && !e.target.closest('#chat-mode-menu')) {
        document.getElementById('chat-mode-menu').style.display = 'none';
    }
});

/** 欢迎页模式卡片选择（Vibe / Spec） */
function selectMode(mode) {
    state.currentMode = mode;
    document.querySelectorAll('.mode-card').forEach(card => {
        card.classList.toggle('active', card.id === 'card-' + mode);
    });
    // 更新适用场景
    const sceneVibe = document.getElementById('scene-vibe');
    const sceneSpec = document.getElementById('scene-spec');
    if (sceneVibe) sceneVibe.style.display = mode === 'vibe' ? 'block' : 'none';
    if (sceneSpec) sceneSpec.style.display = mode === 'spec' ? 'block' : 'none';
}

/** 根据当前模式发送消息 — 所有模式均有 Agent 能力 */
function sendMessage() {
    const input = document.getElementById('user-input');
    let content = input.value.trim();
    if (!content || state.isStreaming) return;

    let finalContent = content;
    if (state.ctxFiles.length > 0) {
        const ctxBlock = state.ctxFiles.map(f =>
            `### 文件: \`${f.path}\`\n\`\`\`\n${f.content}\n\`\`\``
        ).join('\n\n');
        finalContent = `以下是用户引用的项目文件，请结合这些内容回答：\n\n${ctxBlock}\n\n---\n\n用户问题：${content}`;
    }

    input.value = '';
    autoResize(input);
    closeFilePicker();

    const tagsEl = document.getElementById('ctx-tags');
    state.ctxFiles = [];
    tagsEl.innerHTML = '';
    tagsEl.style.display = 'none';

    document.getElementById('welcome-screen').style.display = 'none';
    document.getElementById('messages').style.display = 'flex';

    addUserMessage(content);
    showThinking();

    // Chat 普通聊天 → 直接发送，纯对话无 Agent 能力
    if (state.chatBigMode === 'chat') {
        bridge.send({ type: 'sendMessage', content: finalContent, sessionId: state.currentSessionId });
        return;
    }

    // Agent 仓库智聊 → 所有子模式均走 Agent（包含仓库感知）
    // Spec 模式：先生成计划；Vibe/Agent：直接执行
    if (state.currentMode === 'spec') {
        bridge.send({ type: 'generatePlan', content: finalContent, sessionId: state.currentSessionId });
    } else {
        bridge.send({ type: 'runAgent', content: finalContent, sessionId: state.currentSessionId });
    }
}

/** 从右键菜单发送消息（预填输入框并发送） */
window.setInputAndSend = function(content) {
    document.getElementById('user-input').value = content;
    sendMessage();
};

// ==================== 用户交互 ====================

function handleKeyDown(event) {
    if (event.key === 'Escape') {
        closeFilePicker();
        return;
    }
    if (state.atSearching) {
        // @ 弹窗打开时：上下键选择文件
        if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
            event.preventDefault();
            navigateFilePicker(event.key === 'ArrowDown' ? 1 : -1);
            return;
        }
        if (event.key === 'Enter' || event.key === 'Tab') {
            event.preventDefault();
            confirmFilePickerSelection();
            return;
        }
    }
    if (event.key === 'Enter' && !event.shiftKey && !state.atSearching) {
        event.preventDefault();
        sendMessage();
    }
}

/** 输入框 oninput 统一处理（含 @ 检测） */
function onInputChange(event) {
    autoResize(event.target);
    handleAtTrigger(event.target);
}

function cancelMessage() {
    bridge.send({ type: 'cancelMessage' });
}

function newSession() {
    bridge.send({ type: 'newSession' });
}

// ==================== @文件引用 ====================

let filePickerSelectedIndex = -1;
let filePickerResults = [];
let atSearchTimer = null;

/** 检测输入是否触发 @ */
function handleAtTrigger(textarea) {
    const val = textarea.value;
    const pos = textarea.selectionStart;

    // 找到光标前最近的 @ 位置
    let atPos = -1;
    for (let i = pos - 1; i >= 0; i--) {
        if (val[i] === '@') { atPos = i; break; }
        if (val[i] === ' ' || val[i] === '\n') break;
    }

    if (atPos >= 0) {
        const keyword = val.slice(atPos + 1, pos);
        state.atSearching = true;
        state.atStartPos = atPos;
        showFilePicker();
        // 防抖搜索
        clearTimeout(atSearchTimer);
        atSearchTimer = setTimeout(() => {
            bridge.send({ type: 'searchFiles', keyword });
        }, 150);
    } else {
        state.atSearching = false;
        closeFilePicker();
    }
}

/** 显示文件选择弹窗 */
function showFilePicker() {
    const picker = document.getElementById('file-picker');
    picker.style.display = 'block';
    document.getElementById('file-picker-input').focus();
}

/** 关闭文件选择弹窗 */
function closeFilePicker() {
    document.getElementById('file-picker').style.display = 'none';
    state.atSearching = false;
    filePickerSelectedIndex = -1;
    filePickerResults = [];
}

/** 文件搜索弹窗内的搜索框输入 */
function onFilePickerInput(value) {
    clearTimeout(atSearchTimer);
    atSearchTimer = setTimeout(() => {
        bridge.send({ type: 'searchFiles', keyword: value });
    }, 150);
}

/** 收到文件搜索结果 */
window.onFileSearchResult = function(results) {
    filePickerResults = results || [];
    filePickerSelectedIndex = filePickerResults.length > 0 ? 0 : -1;
    renderFilePicker(filePickerResults);
};

/** 渲染文件列表 */
function renderFilePicker(results) {
    const listEl = document.getElementById('file-picker-list');
    if (!results || results.length === 0) {
        listEl.innerHTML = '<div class="file-picker-empty">未找到匹配文件</div>';
        return;
    }
    const langIcons = {
        java:'☕', kotlin:'🟣', python:'🐍', javascript:'🟨', typescript:'🔷',
        go:'🐹', rust:'🦀', cpp:'⚙️', c:'©️', xml:'📄', yaml:'📋',
        json:'📦', markdown:'📝', sql:'🗄️', bash:'💻', html:'🌐', css:'🎨'
    };
    listEl.innerHTML = results.map((f, i) => {
        const icon = langIcons[f.language] || '📄';
        const active = i === filePickerSelectedIndex ? 'active' : '';
        return `<div class="file-picker-item ${active}" data-index="${i}" onclick="selectFileFromPicker(${i})">
            <span class="file-picker-icon">${icon}</span>
            <div class="file-picker-info">
                <span class="file-picker-name">${escapeHtml(f.name)}</span>
                <span class="file-picker-path">${escapeHtml(f.path)}</span>
            </div>
        </div>`;
    }).join('');
}

/** 键盘导航文件列表 */
function navigateFilePicker(dir) {
    if (filePickerResults.length === 0) return;
    filePickerSelectedIndex = Math.max(0,
        Math.min(filePickerResults.length - 1, filePickerSelectedIndex + dir));
    renderFilePicker(filePickerResults);
    // 滚动到选中项
    const items = document.querySelectorAll('.file-picker-item');
    if (items[filePickerSelectedIndex]) {
        items[filePickerSelectedIndex].scrollIntoView({ block: 'nearest' });
    }
}

/** 确认选中（键盘 Enter/Tab） */
function confirmFilePickerSelection() {
    if (filePickerSelectedIndex >= 0 && filePickerResults[filePickerSelectedIndex]) {
        selectFileFromPicker(filePickerSelectedIndex);
    }
}

/** 选中一个文件 */
function selectFileFromPicker(index) {
    const file = filePickerResults[index];
    if (!file) return;

    // 从输入框中删除 @keyword
    const textarea = document.getElementById('user-input');
    const val = textarea.value;
    const atPos = state.atStartPos;
    const curPos = textarea.selectionStart;
    textarea.value = val.slice(0, atPos) + val.slice(curPos);
    textarea.selectionStart = textarea.selectionEnd = atPos;

    closeFilePicker();
    textarea.focus();

    // 检查是否已经引用过
    if (state.ctxFiles.find(f => f.path === file.path)) return;

    // 显示加载标签（等待内容读取）
    addCtxTag(file.path, file.name, null);

    // 请求文件内容
    bridge.send({ type: 'readFileCtx', path: file.path });
}

/** 收到文件内容 */
window.onFileContent = function(data) {
    // 找到已存在的标签，更新内容
    const existing = state.ctxFiles.find(f => f.path === data.path);
    if (existing) {
        existing.content = data.content;
        existing.truncated = data.truncated;
    } else {
        state.ctxFiles.push({
            path: data.path,
            name: data.path.split('/').pop(),
            content: data.content,
            truncated: data.truncated
        });
    }
    // 更新标签显示
    updateCtxTag(data.path, data.truncated);
};

/** 添加引用标签 */
function addCtxTag(path, name, content) {
    const tagsEl = document.getElementById('ctx-tags');
    tagsEl.style.display = 'flex';

    // 避免重复
    if (document.querySelector(`.ctx-tag[data-path="${CSS.escape(path)}"]`)) return;

    const tag = document.createElement('div');
    tag.className = 'ctx-tag loading';
    tag.dataset.path = path;
    tag.innerHTML = `<span class="ctx-tag-icon">📄</span>
        <span class="ctx-tag-name">${escapeHtml(name)}</span>
        <button class="ctx-tag-remove" onclick="removeCtxFile('${path.replace(/'/g, "\\'")}')">✕</button>`;
    tagsEl.appendChild(tag);

    // 添加到 ctxFiles（content 先为空）
    if (!state.ctxFiles.find(f => f.path === path)) {
        state.ctxFiles.push({ path, name, content: '' });
    }
}

/** 更新标签（内容加载完成） */
function updateCtxTag(path, truncated) {
    const tag = document.querySelector(`.ctx-tag[data-path="${CSS.escape(path)}"]`);
    if (tag) {
        tag.classList.remove('loading');
        if (truncated) tag.title = '文件较大，已截断前500行';
    }
}

/** 移除引用文件 */
function removeCtxFile(path) {
    state.ctxFiles = state.ctxFiles.filter(f => f.path !== path);
    const tag = document.querySelector(`.ctx-tag[data-path="${CSS.escape(path)}"]`);
    if (tag) tag.remove();
    const tagsEl = document.getElementById('ctx-tags');
    if (state.ctxFiles.length === 0) tagsEl.style.display = 'none';
}

// ==================== 会话历史 ====================

/** 全量会话列表缓存 */
let allSessions = [];
let currentSessionIdFromServer = null;

/** 打开历史会话侧边栏 */
function openSessions() {
    document.getElementById('sessions-sidebar').classList.add('open');
    document.getElementById('sessions-overlay').style.display = 'block';
    // 请求最新会话列表
    bridge.send({ type: 'getSessions' });
}

/** 关闭历史会话侧边栏 */
function closeSessions() {
    document.getElementById('sessions-sidebar').classList.remove('open');
    document.getElementById('sessions-overlay').style.display = 'none';
    document.getElementById('sessions-search-input').value = '';
}

/** 渲染会话列表 */
function renderSessionList(sessions) {
    const listEl = document.getElementById('sessions-list');
    if (!sessions || sessions.length === 0) {
        listEl.innerHTML = '<div class="sessions-empty">暂无历史会话</div>';
        return;
    }

    // 按时间分组
    const now = Date.now();
    const groups = { today: [], yesterday: [], week: [], older: [] };
    sessions.forEach(s => {
        const diff = now - s.updatedAt;
        if (diff < 86400000) groups.today.push(s);
        else if (diff < 172800000) groups.yesterday.push(s);
        else if (diff < 604800000) groups.week.push(s);
        else groups.older.push(s);
    });

    const labels = {today:'今天', yesterday:'昨天', week:'本周', older:'更早'};
    let html = '';
    Object.entries(groups).forEach(([key, items]) => {
        if (items.length === 0) return;
        html += `<div class="sessions-group-label">${labels[key]}</div>`;
        items.forEach(s => {
            const isActive = s.id === (state.currentSessionId || currentSessionIdFromServer);
            const modelTag = s.model ? `<span class="session-model">${s.model}</span>` : '';
            html += `
                <div class="session-item ${isActive ? 'active' : ''}" data-id="${s.id}"
                     onclick="loadSession('${s.id}')">
                    <div class="session-info">
                        <span class="session-title">${escapeHtml(s.title)}</span>
                        ${modelTag}
                    </div>
                    <div class="session-actions">
                        <button class="session-action-btn" title="重命名"
                                onclick="event.stopPropagation(); renameSessionPrompt('${s.id}', this)">✎</button>
                        <button class="session-action-btn danger" title="删除"
                                onclick="event.stopPropagation(); deleteSessionConfirm('${s.id}', this)">🗑</button>
                    </div>
                </div>`;
        });
    });
    listEl.innerHTML = html;
}

/** 搜索过滤会话 */
function filterSessions(keyword) {
    if (!keyword.trim()) {
        renderSessionList(allSessions);
        return;
    }
    const filtered = allSessions.filter(s =>
        s.title.toLowerCase().includes(keyword.toLowerCase())
    );
    renderSessionList(filtered);
}

/** 加载指定会话 */
function loadSession(sessionId) {
    bridge.send({ type: 'loadSession', sessionId });
}

/** 重命名会话 */
function renameSessionPrompt(sessionId, btnEl) {
    const titleEl = btnEl.closest('.session-item').querySelector('.session-title');
    const oldTitle = titleEl.textContent;
    const newTitle = prompt('重命名会话：', oldTitle);
    if (newTitle && newTitle.trim() && newTitle !== oldTitle) {
        bridge.send({ type: 'renameSession', sessionId, title: newTitle.trim() });
    }
}

/** 删除会话确认 */
function deleteSessionConfirm(sessionId, btnEl) {
    const item = btnEl.closest('.session-item');
    const title = item.querySelector('.session-title').textContent;
    if (confirm(`确认删除会话「${title}」？`)) {
        bridge.send({ type: 'deleteSession', sessionId });
    }
}

/** HTML 转义 */
function escapeHtml(str) {
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

/** 渲染历史 AI 消息（直接从 markdown 到 HTML，不走流式） */
function addHistoryAiMessage(content) {
    const msgEl = document.createElement('div');
    msgEl.className = 'message ai-message';

    const iconEl = document.createElement('div');
    iconEl.className = 'ai-icon';
    iconEl.textContent = '🔥';

    const bubbleEl = document.createElement('div');
    bubbleEl.className = 'bubble markdown-body';

    // 直接渲染 markdown
    if (window.marked) {
        bubbleEl.innerHTML = marked.parse(content);
        bubbleEl.querySelectorAll('pre code').forEach(codeEl => addCodeActions(codeEl));
    } else {
        bubbleEl.textContent = content;
    }

    msgEl.appendChild(iconEl);
    msgEl.appendChild(bubbleEl);
    document.getElementById('messages').appendChild(msgEl);
}

function openSettings() {
    bridge.send({ type: 'openSettings' });
}

function sendSuggestion(el) {
    const content = el.textContent.trim();
    document.getElementById('user-input').value = content;
    sendMessage();
}

function retryConnect() {
    bridge.send({ type: 'getModels' });
    document.getElementById('offline-banner').style.display = 'none';
}

function toggleSuggestions() {
    state.suggestionsVisible = !state.suggestionsVisible;
    const el = document.getElementById('suggestions');
    el.style.display = state.suggestionsVisible ? 'block' : 'none';
    document.querySelector('.suggest-btn').textContent =
        state.suggestionsVisible ? '推荐问题 ∧' : '推荐问题 ∨';
}

// ==================== Tab 切换 ====================

function switchTab(tab) {
    document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
    document.querySelectorAll('.main-content').forEach(c => c.style.display = 'none');
    event.target.classList.add('active');
    document.getElementById('tab-' + tab).style.display = 'flex';
    // 切换到 Review Tab 时自动加载文件
    if (tab === 'review' && reviewState.files.length === 0) {
        reviewLoadFiles();
    }
}

// ==================== 模型选择 ====================

/** 缓存模型列表数据 */
let cachedProviders = [];

function openModelPicker() {
    // 先请求最新模型列表
    bridge.send({ type: 'getModels' });
}

window.updateModels = function(data) {
    if (data && data.activeModel) {
        updateModelDisplay(data.activeProvider, data.activeModel);
    }
    if (data && data.providers) {
        cachedProviders = data.providers;
        renderModelDropdown(data.providers);
        showModelDropdown();
    }
};

function updateModelDisplay(provider, model) {
    state.activeProvider = provider;
    state.activeModel = model;
    const displayName = model || '未配置';
    document.getElementById('current-model-text').textContent = displayName;
}

/** 渲染模型下拉列表 */
function renderModelDropdown(providers) {
    const list = document.getElementById('model-dropdown-list');
    list.innerHTML = '';

    const cnProviders = providers.filter(p => p.region === 'cn' && p.hasApiKey);
    const globalProviders = providers.filter(p => p.region === 'global' && p.hasApiKey);

    if (cnProviders.length > 0) {
        list.appendChild(createModelGroup('🇨🇳 国内模型', cnProviders));
    }
    if (globalProviders.length > 0) {
        list.appendChild(createModelGroup('🌍 国外模型', globalProviders));
    }
    if (cnProviders.length === 0 && globalProviders.length === 0) {
        const empty = document.createElement('div');
        empty.style.cssText = 'padding: 20px 14px; text-align: center; color: var(--text-muted); font-size: 12px;';
        empty.textContent = '暂无已配置的模型，请在设置中添加 API Key';
        list.appendChild(empty);
    }
}

function createModelGroup(title, providers) {
    const group = document.createElement('div');
    group.className = 'model-dropdown-group';

    const titleEl = document.createElement('div');
    titleEl.className = 'model-dropdown-group-title';
    titleEl.textContent = title;
    group.appendChild(titleEl);

    providers.forEach(p => {
        const model = p.currentModel || p.models[0] || '';
        const isActive = p.name === state.activeProvider;

        const item = document.createElement('div');
        item.className = 'model-dropdown-item' + (isActive ? ' active' : '');

        const logo = getProviderLogo(p.name);
        item.innerHTML = `
            <div class="model-dropdown-item-left">
                <span class="model-dropdown-item-icon">${logo}</span>
                <span class="model-dropdown-item-name">${p.displayName} · ${model}</span>
            </div>
            ${isActive ? '<span class="model-dropdown-item-check">✓</span>' : ''}
        `;

        item.onclick = () => {
            if (!isActive) {
                bridge.send({ type: 'switchModel', provider: p.name, model: model });
                updateModelDisplay(p.name, model);
            }
            closeModelDropdown();
        };

        group.appendChild(item);
    });

    return group;
}

function showModelDropdown() {
    document.getElementById('model-dropdown').style.display = 'flex';
    document.getElementById('model-dropdown-overlay').style.display = 'block';
}

function closeModelDropdown() {
    document.getElementById('model-dropdown').style.display = 'none';
    document.getElementById('model-dropdown-overlay').style.display = 'none';
}

// ==================== 消息渲染 ====================

function addUserMessage(content) {
    const userName = state.userName || 'You';
    const msgEl = document.createElement('div');
    msgEl.className = 'message user-message';
    msgEl.innerHTML = `
        <div class="user-msg-body">
            <div class="user-name-label">${escapeHtml(userName)}</div>
            <div class="message-bubble">${escapeHtml(content)}</div>
        </div>
        <div class="user-avatar">👤</div>`;
    document.getElementById('messages').appendChild(msgEl);
    scrollToBottom();
}

let currentAiEl = null;
let currentAiRaw = '';
let mdRenderTimer = null;

function addAiMessagePlaceholder() {
    currentAiRaw = '';
    const now = new Date();
    const timeStr = now.getHours().toString().padStart(2,'0') + ':' +
                    now.getMinutes().toString().padStart(2,'0') + ':' +
                    now.getSeconds().toString().padStart(2,'0');
    const modelName = state.activeModel || '';
    const providerName = state.activeProvider || '';
    const metaStr = [timeStr, providerName, modelName].filter(Boolean).join(' · ');

    const msgEl = document.createElement('div');
    msgEl.className = 'message ai-message';
    msgEl.innerHTML = `
        <div class="ai-avatar">🔥</div>
        <div class="message-content">
            <div class="cm-msg-header">
                <span class="cm-brand">CodeForge</span>
                <span class="cm-meta">${metaStr}</span>
            </div>
            <div class="message-text streaming" id="ai-streaming"></div>
        </div>`;
    document.getElementById('messages').appendChild(msgEl);
    currentAiEl = msgEl.querySelector('#ai-streaming');
    scrollToBottom();
}

function appendToLastAiMessage(token) {
    if (!currentAiEl) return;
    currentAiRaw += token;

    // 增量 Markdown 渲染（节流 80ms，兼顾流畅度和性能）
    if (!mdRenderTimer) {
        mdRenderTimer = setTimeout(() => {
            mdRenderTimer = null;
            renderStreamingMarkdown();
        }, 80);
    }
}

/**
 * 过滤掉 AI 回复中不应展示给用户的内容：
 * - <tool_call>...</tool_call> 标签块
 * - 裸 JSON 工具调用（如 {"tool":"read_file","path":"..."}）
 */
function cleanAiOutput(raw) {
    let text = raw;
    // 移除 <tool_call> 块
    text = text.replace(/<tool_call>[\s\S]*?<\/tool_call>/g, '');
    // 移除裸 JSON 工具调用（含 "tool" 字段且值为已知工具名的 JSON 对象）
    text = text.replace(/\{[^{}]*"tool"\s*:\s*"(read_file|write_file|list_files|search_code|run_terminal)"[^{}]*\}/g, '');
    // 清理多余空行
    text = text.replace(/\n{3,}/g, '\n\n').trim();
    return text;
}

/** 流式过程中增量渲染 Markdown */
function renderStreamingMarkdown() {
    if (!currentAiEl) return;
    const displayText = cleanAiOutput(currentAiRaw);
    if (typeof marked !== 'undefined') {
        try {
            currentAiEl.innerHTML = marked.parse(displayText) +
                '<span class="streaming-cursor">▋</span>';
        } catch (e) {
            currentAiEl.textContent = currentAiRaw;
        }
    } else {
        currentAiEl.textContent = currentAiRaw;
    }
    scrollToBottom();
}

function finalizeLastAiMessage() {
    if (!currentAiEl) return;
    // 清除未执行的渲染定时器
    if (mdRenderTimer) {
        clearTimeout(mdRenderTimer);
        mdRenderTimer = null;
    }
    // 流结束后做最终 Markdown 渲染（过滤工具调用内容）
    const finalText = cleanAiOutput(currentAiRaw);
    currentAiEl.id = '';
    currentAiEl.classList.remove('streaming');
    if (typeof marked !== 'undefined') {
        try {
            currentAiEl.innerHTML = marked.parse(finalText);
        } catch (e) {
            currentAiEl.textContent = finalText;
        }
        // 为代码块添加复制按钮和 Apply 按钮
        currentAiEl.querySelectorAll('pre code').forEach(codeEl => {
            addCodeActions(codeEl);
        });
    } else {
        currentAiEl.textContent = finalText;
    }
    currentAiEl = null;
    currentAiRaw = '';
    scrollToBottom();
}

function appendErrorMessage(message) {
    const msgEl = document.createElement('div');
    msgEl.className = 'message error-message';

    const textSpan = document.createElement('span');
    textSpan.textContent = '❌ ' + message;
    msgEl.appendChild(textSpan);

    // 添加手动重试按钮
    const retryBtn = document.createElement('button');
    retryBtn.className = 'retry-btn';
    retryBtn.innerHTML = '🔄 重试';
    retryBtn.onclick = function() {
        // 移除这条错误消息
        msgEl.remove();
        // 找到最后一条用户消息内容，重新发送
        const userMsgs = document.querySelectorAll('.user-message .bubble');
        if (userMsgs.length > 0) {
            const lastContent = userMsgs[userMsgs.length - 1].textContent.trim();
            showThinking();
            bridge.send({
                type: 'sendMessage',
                content: lastContent,
                sessionId: state.currentSessionId
            });
        }
    };
    msgEl.appendChild(retryBtn);

    document.getElementById('messages').appendChild(msgEl);
    scrollToBottom();
}

/**
 * 为代码块添加操作按钮（复制 / Apply to File）
 */
function addCodeActions(codeEl) {
    const pre = codeEl.parentElement;
    const wrapper = document.createElement('div');
    wrapper.className = 'code-wrapper';
    pre.parentNode.insertBefore(wrapper, pre);
    wrapper.appendChild(pre);

    // 检测语言
    const lang = (codeEl.className || '').replace('language-', '').trim();

    const actions = document.createElement('div');
    actions.className = 'code-actions';

    // 语言标签
    if (lang) {
        const langTag = document.createElement('span');
        langTag.className = 'code-lang-tag';
        langTag.textContent = lang;
        actions.appendChild(langTag);
    }

    // 复制按钮
    const copyBtn = document.createElement('button');
    copyBtn.className = 'code-action-btn';
    copyBtn.innerHTML = '📋 复制';
    copyBtn.onclick = () => {
        navigator.clipboard.writeText(codeEl.textContent);
        copyBtn.innerHTML = '✅ 已复制';
        setTimeout(() => { copyBtn.innerHTML = '📋 复制'; }, 2000);
    };

    // Apply 按钮 — 弹出 Diff 视图写入文件
    const applyBtn = document.createElement('button');
    applyBtn.className = 'code-action-btn apply-btn';
    applyBtn.innerHTML = '⚡ Apply';
    applyBtn.title = '在 Diff 视图中对比并写入文件';
    applyBtn.onclick = () => {
        applyBtn.innerHTML = '⏳ 应用中...';
        applyBtn.disabled = true;
        bridge.send({
            type: 'applyCode',
            code: codeEl.textContent,
            language: lang,
            fileName: null   // 由 Kotlin 端智能推断目标文件
        });
        // 恢复按钮状态（Kotlin 侧会弹窗，无需等待回调）
        setTimeout(() => {
            applyBtn.innerHTML = '⚡ Apply';
            applyBtn.disabled = false;
        }, 1500);
    };

    actions.appendChild(copyBtn);
    actions.appendChild(applyBtn);
    wrapper.appendChild(actions);
}

// ==================== 工具函数 ====================

function autoResize(textarea) {
    textarea.style.height = 'auto';
    textarea.style.height = Math.min(textarea.scrollHeight, 200) + 'px';
}

function scrollToBottom() {
    const messages = document.getElementById('messages');
    messages.scrollTop = messages.scrollHeight;
}

function escapeHtml(str) {
    return str
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
}

// ==================== 思考中 Loading ====================

function showThinking() {
    removeThinking(); // 防止重复
    const msgEl = document.createElement('div');
    msgEl.className = 'message ai-message';
    msgEl.id = 'thinking-message';
    msgEl.innerHTML = `
        <div class="ai-avatar">🔥</div>
        <div class="message-content">
            <div class="thinking-indicator">
                <div class="thinking-dots">
                    <span></span><span></span><span></span>
                </div>
                <span>正在思考...</span>
            </div>
        </div>`;
    document.getElementById('messages').appendChild(msgEl);
    scrollToBottom();
}

function removeThinking() {
    const el = document.getElementById('thinking-message');
    if (el) el.remove();
}

// ==================== 滚动节流 ====================

let scrollTimer = null;
const _origScrollToBottom = scrollToBottom;
scrollToBottom = function() {
    if (scrollTimer) return;
    scrollTimer = setTimeout(() => {
        scrollTimer = null;
        const messages = document.getElementById('messages');
        messages.scrollTop = messages.scrollHeight;
    }, 50);
};

// ==================== 公司 Logo 映射 ====================

const PROVIDER_LOGOS = {
    deepseek:  '🔵',
    qwen:      '🟣',
    glm:       '🟢',
    kimi:      '🌙',
    minimax:   '🔶',
    ernie:     '🔴',
    doubao:    '🫘',
    yi:        '0️⃣',
    baichuan:  '🏔️',
    stepfun:   '🪜',
    openai:    '🤖',
    anthropic: '🅰️',
    gemini:    '💎',
    mistral:   '🌀',
    groq:      '⚡',
    xai:       '𝕏',
};

function getProviderLogo(name) {
    return PROVIDER_LOGOS[name] || '🔷';
}

// ==================== 页面初始化 ====================

// 页面加载后：默认设置为 Agent 仓库智聊模式
document.addEventListener('DOMContentLoaded', function() {
    // 同步默认状态到 UI
    selectChatMode('agent');
    // 默认显示 vibe 场景，隐藏 spec 场景
    selectMode('vibe');
    // 跟随 IDEA 主题自动切换
    applyIDETheme();
});

/**
 * 主题适配：通过检测背景色亮度决定 light/dark
 * IDEA 会把 body 背景色设为当前主题的背景，利用这一点做判断
 */
function applyIDETheme() {
    try {
        const bg = window.getComputedStyle(document.body).backgroundColor;
        const match = bg.match(/\d+/g);
        if (match && match.length >= 3) {
            const [r, g, b] = match.map(Number);
            // 感知亮度公式
            const luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
            if (luminance > 0.5) {
                document.body.classList.add('light');
            } else {
                document.body.classList.remove('light');
            }
        }
    } catch (e) { /* 无法检测时保持默认暗色 */ }
}

// 监听主题变化（IDEA 在运行时切换主题会触发 body 样式变更）
new MutationObserver(applyIDETheme).observe(document.body, {
    attributes: true,
    attributeFilter: ['style', 'class']
});


// ==================== Review Tab ====================

/** Review Tab 状态 */
const reviewState = {
    files: [],          // ChangedFile[]
    selectedPaths: [],  // 已勾选文件路径
    reviewBuffer: '',   // 累积的审查 Markdown 内容
    isReviewing: false
};

/** 加载 Git 变更文件列表 */
function reviewLoadFiles() {
    const btn = document.getElementById('review-refresh-btn');
    btn.classList.add('loading');
    btn.disabled = true;
    bridge.send({ type: 'getChangedFiles' });
}

/** Kotlin 回调：文件列表返回 */
window.onChangedFiles = function(files) {
    reviewState.files = files || [];
    const btn = document.getElementById('review-refresh-btn');
    btn.classList.remove('loading');
    btn.disabled = false;
    renderReviewFileList();
};

/** 渲染文件列表 */
function renderReviewFileList() {
    const listEl = document.getElementById('review-file-list');
    listEl.innerHTML = '';

    if (reviewState.files.length === 0) {
        listEl.innerHTML = `
            <div class="review-empty">
                <div style="font-size:32px;margin-bottom:8px">✅</div>
                <div>没有检测到 Git 变更文件</div>
                <div style="color:var(--text-muted);margin-top:4px;font-size:11px">请确认是否有 staged 或未提交的修改</div>
            </div>`;
        document.getElementById('review-start-btn').disabled = true;
        document.getElementById('review-select-all').checked = false;
        return;
    }

    reviewState.files.forEach(file => {
        const item = document.createElement('div');
        item.className = 'review-file-item';
        item.dataset.path = file.path;

        const addSign = file.additions > 0 ? `<span class="add">+${file.additions}</span>` : '';
        const delSign = file.deletions > 0 ? `<span class="del">-${file.deletions}</span>` : '';

        item.innerHTML = `
            <input type="checkbox" class="review-file-cb" data-path="${escapeHtml(file.path)}" checked>
            <span class="review-file-status ${file.status}">${file.status}</span>
            <span class="review-file-path" title="${escapeHtml(file.path)}">${escapeHtml(file.path)}</span>
            <span class="review-file-lines">${addSign}${delSign}</span>
        `;

        item.querySelector('.review-file-cb').addEventListener('change', () => {
            syncReviewSelection();
        });

        item.addEventListener('click', (e) => {
            if (e.target.tagName === 'INPUT') return;
            const cb = item.querySelector('.review-file-cb');
            cb.checked = !cb.checked;
            syncReviewSelection();
        });

        listEl.appendChild(item);
    });

    // 默认全选
    syncReviewSelection();
    document.getElementById('review-select-all').checked = true;
}

/** 全选/取消全选 */
function reviewToggleAll(checked) {
    document.querySelectorAll('.review-file-cb').forEach(cb => { cb.checked = checked; });
    syncReviewSelection();
}

/** 同步 selectedPaths 并更新开始按钮 */
function syncReviewSelection() {
    reviewState.selectedPaths = Array.from(document.querySelectorAll('.review-file-cb:checked'))
        .map(cb => cb.dataset.path);
    document.getElementById('review-start-btn').disabled = reviewState.selectedPaths.length === 0;

    // 更新全选 checkbox 状态
    const total = document.querySelectorAll('.review-file-cb').length;
    const selected = reviewState.selectedPaths.length;
    const selectAllCb = document.getElementById('review-select-all');
    selectAllCb.indeterminate = selected > 0 && selected < total;
    selectAllCb.checked = selected === total;
}

/** 点击「开始审查」 */
function reviewStart() {
    if (reviewState.isReviewing) return;
    if (reviewState.selectedPaths.length === 0) return;

    reviewState.isReviewing = true;
    reviewState.reviewBuffer = '';

    // 显示结果区
    const divider = document.getElementById('review-divider');
    const resultArea = document.getElementById('review-result-area');
    divider.style.display = 'block';
    resultArea.style.display = 'flex';

    // 重置内容
    document.getElementById('review-markdown').innerHTML = '';
    document.getElementById('review-thinking').style.display = 'flex';
    document.getElementById('review-result-status').textContent = '🔍 AI 审查中...';
    document.getElementById('review-start-btn').disabled = true;
    document.getElementById('review-start-btn').textContent = '审查中...';

    // 滚动到结果区
    resultArea.scrollIntoView({ behavior: 'smooth', block: 'start' });

    bridge.send({
        type: 'reviewCode',
        files: reviewState.selectedPaths,
        staged: false
    });
}

/** Kotlin 回调：审查开始 */
window.onReviewStart = function() {
    // 已在 reviewStart() 中处理 UI，这里无需重复
};

/** Kotlin 回调：流式 token（Base64 编码） */
window.onReviewToken = function(b64) {
    const token = decodeURIComponent(escape(atob(b64)));
    reviewState.reviewBuffer += token;

    // 隐藏 thinking
    document.getElementById('review-thinking').style.display = 'none';

    // 实时 Markdown 渲染
    const mdEl = document.getElementById('review-markdown');
    if (typeof marked !== 'undefined') {
        mdEl.innerHTML = marked.parse(reviewState.reviewBuffer);
    } else {
        mdEl.textContent = reviewState.reviewBuffer;
    }

    // 保持滚动到底
    const content = document.getElementById('review-result-content');
    content.scrollTop = content.scrollHeight;
};

/** Kotlin 回调：审查完成 */
window.onReviewDone = function() {
    reviewState.isReviewing = false;
    document.getElementById('review-thinking').style.display = 'none';
    document.getElementById('review-result-status').textContent = '✅ 审查完成';
    document.getElementById('review-start-btn').disabled = false;
    document.getElementById('review-start-btn').innerHTML = '🔍 重新审查';

    // 最终完整渲染（添加代码块复制按钮）
    const mdEl = document.getElementById('review-markdown');
    if (typeof marked !== 'undefined') {
        mdEl.innerHTML = marked.parse(reviewState.reviewBuffer);
        // 为代码块添加复制按钮
        mdEl.querySelectorAll('pre').forEach(pre => {
            const btn = document.createElement('button');
            btn.className = 'review-copy-btn';
            btn.style.cssText = 'position:absolute;top:6px;right:8px;font-size:10px;padding:2px 6px;';
            btn.textContent = '复制';
            btn.onclick = () => {
                navigator.clipboard.writeText(pre.querySelector('code')?.textContent || '');
                btn.textContent = '已复制';
                setTimeout(() => { btn.textContent = '复制'; }, 2000);
            };
            pre.style.position = 'relative';
            pre.appendChild(btn);
        });
    }
};

/** Kotlin 回调：审查出错 */
window.onReviewError = function(message) {
    reviewState.isReviewing = false;
    document.getElementById('review-thinking').style.display = 'none';
    document.getElementById('review-result-status').textContent = '❌ 审查失败';
    document.getElementById('review-markdown').innerHTML =
        `<div style="color:#ef4444;padding:10px 0">${escapeHtml(message)}</div>`;
    document.getElementById('review-start-btn').disabled = false;
    document.getElementById('review-start-btn').innerHTML = '🔍 重试审查';
};

/** 复制审查结果 */
function reviewCopyResult() {
    if (!reviewState.reviewBuffer) return;
    navigator.clipboard.writeText(reviewState.reviewBuffer).then(() => {
        const btn = document.querySelector('.review-copy-btn');
        const orig = btn.innerHTML;
        btn.innerHTML = '✅ 已复制';
        setTimeout(() => { btn.innerHTML = orig; }, 2000);
    });
}

