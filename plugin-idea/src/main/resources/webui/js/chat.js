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
    if (data.activeModel) {
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

function sendMessage() {
    const input = document.getElementById('user-input');
    let content = input.value.trim();
    if (!content || state.isStreaming) return;

    // 注入 @引用文件 上下文到消息前
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

    // 清空引用标签
    const tagsEl = document.getElementById('ctx-tags');
    state.ctxFiles = [];
    tagsEl.innerHTML = '';
    tagsEl.style.display = 'none';

    // 隐藏欢迎页，显示消息列表
    document.getElementById('welcome-screen').style.display = 'none';
    document.getElementById('messages').style.display = 'flex';

    // 添加用户消息气泡（展示原始内容，不展示文件内容）
    addUserMessage(content, state.ctxFiles.map(f => f.name));

    // 显示"思考中"loading 动画
    showThinking();

    // 发送到 Kotlin（携带注入了文件内容的完整消息）
    bridge.send({
        type: 'sendMessage',
        content: finalContent,
        sessionId: state.currentSessionId
    });
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

// ==================== 模式选择 ====================

function selectMode(mode) {
    state.currentMode = mode;
    document.getElementById('card-vibe').classList.toggle('active', mode === 'vibe');
    document.getElementById('card-spec').classList.toggle('active', mode === 'spec');
    document.getElementById('scene-vibe').style.display = mode === 'vibe' ? 'block' : 'none';
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
    const msgEl = document.createElement('div');
    msgEl.className = 'message user-message';
    msgEl.innerHTML = `<div class="message-bubble">${escapeHtml(content)}</div>`;
    document.getElementById('messages').appendChild(msgEl);
    scrollToBottom();
}

let currentAiEl = null;
let currentAiRaw = '';
let mdRenderTimer = null;

function addAiMessagePlaceholder() {
    currentAiRaw = '';
    const msgEl = document.createElement('div');
    msgEl.className = 'message ai-message';
    msgEl.innerHTML = `
        <div class="ai-avatar">🔥</div>
        <div class="message-content">
            <div class="message-text streaming" id="ai-streaming"></div>
        </div>`;
    document.getElementById('messages').appendChild(msgEl);
    currentAiEl = msgEl.querySelector('#ai-streaming');
    scrollToBottom();
}

function appendToLastAiMessage(token) {
    if (!currentAiEl) return;
    currentAiRaw += token;

    // 增量 Markdown 渲染（节流 300ms，避免每个 token 都触发 DOM 重排）
    if (!mdRenderTimer) {
        mdRenderTimer = setTimeout(() => {
            mdRenderTimer = null;
            renderStreamingMarkdown();
        }, 300);
    }
}

/** 流式过程中增量渲染 Markdown */
function renderStreamingMarkdown() {
    if (!currentAiEl) return;
    if (typeof marked !== 'undefined') {
        try {
            currentAiEl.innerHTML = marked.parse(currentAiRaw) +
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
    // 流结束后做最终 Markdown 渲染
    currentAiEl.id = '';
    currentAiEl.classList.remove('streaming');
    if (typeof marked !== 'undefined') {
        try {
            currentAiEl.innerHTML = marked.parse(currentAiRaw);
        } catch (e) {
            currentAiEl.textContent = currentAiRaw;
        }
        // 为代码块添加复制按钮和 Apply 按钮
        currentAiEl.querySelectorAll('pre code').forEach(codeEl => {
            addCodeActions(codeEl);
        });
    } else {
        currentAiEl.textContent = currentAiRaw;
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
