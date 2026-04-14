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
    suggestionsVisible: true
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

/** 流开始 */
window.onStreamStart = function() {
    state.isStreaming = true;
    document.getElementById('send-btn').style.display = 'none';
    document.getElementById('stop-btn').style.display = 'flex';
    // 添加 AI 消息占位
    addAiMessagePlaceholder();
};

/** 追加一个 token */
window.appendToken = function(token) {
    appendToLastAiMessage(token);
};

/** 流结束 */
window.onStreamDone = function() {
    state.isStreaming = false;
    document.getElementById('send-btn').style.display = 'flex';
    document.getElementById('stop-btn').style.display = 'none';
    finalizeLastAiMessage();
};

/** 错误 */
window.onError = function(message) {
    state.isStreaming = false;
    document.getElementById('send-btn').style.display = 'flex';
    document.getElementById('stop-btn').style.display = 'none';
    appendErrorMessage(message);
};

/** 模型切换成功 */
window.onModelSwitched = function(provider, model) {
    updateModelDisplay(provider, model);
};

/** 新建会话 */
window.onNewSession = function() {
    state.messages = [];
    state.currentSessionId = null;
    document.getElementById('messages').innerHTML = '';
    document.getElementById('messages').style.display = 'none';
    document.getElementById('welcome-screen').style.display = 'flex';
};

/** 从右键菜单发送消息（预填输入框并发送） */
window.setInputAndSend = function(content) {
    document.getElementById('user-input').value = content;
    sendMessage();
};

// ==================== 用户交互 ====================

function handleKeyDown(event) {
    if (event.key === 'Enter' && !event.shiftKey) {
        event.preventDefault();
        sendMessage();
    }
}

function sendMessage() {
    const input = document.getElementById('user-input');
    const content = input.value.trim();
    if (!content || state.isStreaming) return;

    input.value = '';
    autoResize(input);

    // 隐藏欢迎页，显示消息列表
    document.getElementById('welcome-screen').style.display = 'none';
    document.getElementById('messages').style.display = 'flex';

    // 添加用户消息气泡
    addUserMessage(content);

    // 发送到 Kotlin
    bridge.send({
        type: 'sendMessage',
        content: content,
        sessionId: state.currentSessionId
    });
}

function cancelMessage() {
    bridge.send({ type: 'cancelMessage' });
}

function newSession() {
    bridge.send({ type: 'newSession' });
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

function openModelPicker() {
    bridge.send({ type: 'getModels' });
    // 实际弹窗由 Kotlin StatusBar Widget 处理
    // 或者在此实现 HTML 原生弹窗
}

window.updateModels = function(data) {
    if (data && data.activeModel) {
        updateModelDisplay(data.activeProvider, data.activeModel);
    }
};

function updateModelDisplay(provider, model) {
    state.activeProvider = provider;
    state.activeModel = model;
    const displayName = model || '未配置';
    document.getElementById('current-model-text').textContent = displayName;
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
    // 实时显示原始文字（流式状态）
    currentAiEl.textContent = currentAiRaw;
    scrollToBottom();
}

function finalizeLastAiMessage() {
    if (!currentAiEl) return;
    // 流结束后用 Markdown 渲染
    currentAiEl.id = '';
    currentAiEl.classList.remove('streaming');
    if (typeof marked !== 'undefined') {
        currentAiEl.innerHTML = marked.parse(currentAiRaw);
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
    msgEl.textContent = '❌ ' + message;
    document.getElementById('messages').appendChild(msgEl);
    scrollToBottom();
}

/**
 * 为代码块添加操作按钮（复制 / Apply）
 */
function addCodeActions(codeEl) {
    const pre = codeEl.parentElement;
    const wrapper = document.createElement('div');
    wrapper.className = 'code-wrapper';
    pre.parentNode.insertBefore(wrapper, pre);
    wrapper.appendChild(pre);

    const actions = document.createElement('div');
    actions.className = 'code-actions';

    const copyBtn = document.createElement('button');
    copyBtn.className = 'code-action-btn';
    copyBtn.textContent = '复制';
    copyBtn.onclick = () => {
        navigator.clipboard.writeText(codeEl.textContent);
        copyBtn.textContent = '已复制 ✓';
        setTimeout(() => copyBtn.textContent = '复制', 2000);
    };

    const applyBtn = document.createElement('button');
    applyBtn.className = 'code-action-btn apply-btn';
    applyBtn.textContent = '插入到编辑器';
    applyBtn.onclick = () => {
        const lang = codeEl.className.replace('language-', '');
        bridge.send({ type: 'applyCode', code: codeEl.textContent, language: lang });
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
