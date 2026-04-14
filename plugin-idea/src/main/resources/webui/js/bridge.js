/**
 * bridge.js — IDE 环境自动识别通信桥
 *
 * 统一 IDEA JCEF 和 VSCode WebView 两种环境的消息通信接口。
 * 上层代码只需调用 bridge.send(message)，无需关心底层环境。
 */
const bridge = {
    /** 是否运行在 IDEA JCEF 环境 */
    isIdea: false,
    /** 是否运行在 VSCode WebView 环境 */
    isVscode: false,
    /** VSCode API 实例 */
    _vscode: null,

    init() {
        // 检测 VSCode 环境
        if (typeof acquireVsCodeApi !== 'undefined') {
            this.isVscode = true;
            this._vscode = acquireVsCodeApi();
            // 接收 VSCode 主进程推送的消息
            window.addEventListener('message', (event) => {
                this._handleIncoming(event.data);
            });
        }
        // IDEA JCEF 环境：window.ideaBridge 由 Kotlin 在页面加载后注入
        // 检测方式：等待 DOMContentLoaded 后再判断
        console.log('[Bridge] 初始化完成, isVscode=', this.isVscode);
    },

    /**
     * 向宿主（Kotlin / TypeScript）发送消息
     * @param {Object} message - 消息对象，必须包含 type 字段
     */
    send(message) {
        const json = typeof message === 'string' ? message : JSON.stringify(message);
        if (this.isVscode && this._vscode) {
            this._vscode.postMessage(message);
        } else if (typeof window.ideaBridge === 'function') {
            // IDEA JCEF：调用 Kotlin 注入的函数
            window.ideaBridge(message);
        } else {
            // 开发模式 / 浏览器直接打开
            console.log('[Bridge] Mock send:', json);
        }
    },

    /**
     * 处理来自宿主的消息（VSCode 模式）
     */
    _handleIncoming(data) {
        if (!data || !data.type) return;
        switch (data.type) {
            case 'appendToken':
                window.appendToken && window.appendToken(data.content);
                break;
            case 'onStreamDone':
                window.onStreamDone && window.onStreamDone();
                break;
            case 'onStreamStart':
                window.onStreamStart && window.onStreamStart();
                break;
            case 'updateModels':
                window.updateModels && window.updateModels(data.payload);
                break;
            case 'onError':
                window.onError && window.onError(data.message);
                break;
            case 'onInit':
                window.onInit && window.onInit(data.payload);
                break;
            case 'onModelSwitched':
                window.onModelSwitched && window.onModelSwitched(data.provider, data.model);
                break;
        }
    }
};

// 自动初始化
bridge.init();
