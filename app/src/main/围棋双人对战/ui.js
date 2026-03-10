/**
 * UI 模块 - 处理用户界面交互
 * 整合游戏逻辑、存储和网络功能
 */

class GoUI {
    constructor() {
        this.game = new GoGame(19);
        this.canvas = null;
        this.ctx = null;
        this.cellSize = 0;
        this.padding = 0;
        this.settings = null;
        this.gameMode = 'local'; // 'local' or 'online'
        this.timerInterval = null;
        this.startTime = null;
        
        this.init();
    }

    // 初始化
    init() {
        // 加载设置
        this.settings = window.StorageManager.loadSettings();
        
        // 初始化 Canvas
        this.canvas = document.getElementById('go-board');
        this.ctx = this.canvas.getContext('2d');
        
        // 绑定事件
        this.bindEvents();
        
        // 应用设置
        this.applySettings();
        
        // 绘制棋盘
        this.resizeCanvas();
        this.drawBoard();
        
        // 启动计时器
        this.startTimer();
        
        // 设置网络消息回调
        window.NetworkManager.onMessage = (data) => this.handleNetworkMessage(data);
        window.NetworkManager.onStatusChange = (text, connected) => this.updateNetworkStatus(text, connected);
        
        // 检查是否有未完成的游戏
        this.checkUnfinishedGame();
    }

    // 绑定事件
    bindEvents() {
        // Canvas 点击落子
        this.canvas.addEventListener('click', (e) => this.handleCanvasClick(e));
        this.canvas.addEventListener('touchstart', (e) => {
            e.preventDefault();
            this.handleCanvasClick(e.touches[0]);
        }, { passive: false });
        
        // 窗口大小变化
        window.addEventListener('resize', () => {
            this.resizeCanvas();
            this.drawBoard();
        });
        
        // 游戏模式切换
        document.querySelectorAll('.mode-btn').forEach(btn => {
            btn.addEventListener('click', () => this.switchMode(btn.dataset.mode));
        });
        
        // 棋盘大小切换
        document.querySelectorAll('.size-btn').forEach(btn => {
            btn.addEventListener('click', () => this.switchBoardSize(parseInt(btn.dataset.size)));
        });
        
        // 按钮事件
        document.getElementById('btn-new-game').addEventListener('click', () => this.newGame());
        document.getElementById('btn-undo').addEventListener('click', () => this.undo());
        document.getElementById('btn-pass').addEventListener('click', () => this.pass());
        document.getElementById('btn-resign').addEventListener('click', () => this.resign());
        document.getElementById('btn-settings').addEventListener('click', () => this.toggleSettings());
        
        // 网络面板
        document.querySelectorAll('.tab-btn').forEach(btn => {
            btn.addEventListener('click', () => this.switchNetworkTab(btn.dataset.tab));
        });
        document.getElementById('btn-create-room').addEventListener('click', () => this.createRoom());
        document.getElementById('btn-join-room').addEventListener('click', () => this.joinRoom());
        document.getElementById('btn-close-network').addEventListener('click', () => this.closeNetworkPanel());
        
        // 设置面板
        document.getElementById('btn-save-settings').addEventListener('click', () => this.saveSettings());
        document.getElementById('btn-close-settings').addEventListener('click', () => this.toggleSettings());
        
        // 游戏结束弹窗
        document.getElementById('btn-new-game-modal').addEventListener('click', () => {
            document.getElementById('game-over-modal').classList.add('hidden');
            this.newGame();
        });
        document.getElementById('btn-close-modal').addEventListener('click', () => {
            document.getElementById('game-over-modal').classList.add('hidden');
        });
    }

    // 调整 Canvas 大小
    resizeCanvas() {
        const container = this.canvas.parentElement;
        const size = Math.min(container.clientWidth - 40, 600);
        
        // 设置实际像素大小（高分屏适配）
        const dpr = window.devicePixelRatio || 1;
        this.canvas.width = size * dpr;
        this.canvas.height = size * dpr;
        this.canvas.style.width = size + 'px';
        this.canvas.style.height = size + 'px';
        
        // 缩放上下文
        this.ctx.scale(dpr, dpr);
        
        // 计算格子大小和边距
        this.padding = size * 0.06;
        const boardPixels = size - 2 * this.padding;
        this.cellSize = boardPixels / (this.game.boardSize - 1);
    }

    // 绘制棋盘
    drawBoard() {
        const size = this.canvas.width / (window.devicePixelRatio || 1);
        
        // 清空画布
        this.ctx.clearRect(0, 0, size, size);
        
        // 绘制棋盘背景
        this.ctx.fillStyle = '#dcb35c';
        this.ctx.fillRect(0, 0, size, size);
        
        // 绘制网格线
        this.ctx.strokeStyle = '#1a1a1a';
        this.ctx.lineWidth = 1;
        
        for (let i = 0; i < this.game.boardSize; i++) {
            const pos = this.padding + i * this.cellSize;
            
            // 横线
            this.ctx.beginPath();
            this.ctx.moveTo(this.padding, pos);
            this.ctx.lineTo(size - this.padding, pos);
            this.ctx.stroke();
            
            // 竖线
            this.ctx.beginPath();
            this.ctx.moveTo(pos, this.padding);
            this.ctx.lineTo(pos, size - this.padding);
            this.ctx.stroke();
        }
        
        // 绘制星位（19 路棋盘）
        if (this.game.boardSize === 19) {
            const stars = [3, 9, 15];
            this.ctx.fillStyle = '#1a1a1a';
            for (const x of stars) {
                for (const y of stars) {
                    const cx = this.padding + x * this.cellSize;
                    const cy = this.padding + y * this.cellSize;
                    this.ctx.beginPath();
                    this.ctx.arc(cx, cy, 4, 0, Math.PI * 2);
                    this.ctx.fill();
                }
            }
        } else if (this.game.boardSize === 13) {
            const stars = [3, 6, 9];
            this.ctx.fillStyle = '#1a1a1a';
            for (const x of stars) {
                for (const y of stars) {
                    const cx = this.padding + x * this.cellSize;
                    const cy = this.padding + y * this.cellSize;
                    this.ctx.beginPath();
                    this.ctx.arc(cx, cy, 3, 0, Math.PI * 2);
                    this.ctx.fill();
                }
            }
        } else if (this.game.boardSize === 9) {
            const stars = [2, 6];
            this.ctx.fillStyle = '#1a1a1a';
            for (const x of stars) {
                for (const y of stars) {
                    const cx = this.padding + x * this.cellSize;
                    const cy = this.padding + y * this.cellSize;
                    this.ctx.beginPath();
                    this.ctx.arc(cx, cy, 3, 0, Math.PI * 2);
                    this.ctx.fill();
                }
            }
        }
        
        // 绘制棋子
        this.drawStones();
        
        // 标记最后一步
        if (this.game.lastMove) {
            this.drawLastMoveMarker(this.game.lastMove.x, this.game.lastMove.y);
        }
    }

    // 绘制棋子
    drawStones() {
        for (let y = 0; y < this.game.boardSize; y++) {
            for (let x = 0; x < this.game.boardSize; x++) {
                const stone = this.game.board[y][x];
                if (stone !== 0) {
                    this.drawStone(x, y, stone);
                }
            }
        }
    }

    // 绘制单个棋子
    drawStone(x, y, color) {
        const cx = this.padding + x * this.cellSize;
        const cy = this.padding + y * this.cellSize;
        const radius = this.cellSize * 0.45;
        
        this.ctx.beginPath();
        this.ctx.arc(cx, cy, radius, 0, Math.PI * 2);
        
        // 渐变效果
        const gradient = this.ctx.createRadialGradient(
            cx - radius * 0.3, cy - radius * 0.3, 0,
            cx, cy, radius
        );
        
        if (color === 1) {
            // 黑子
            gradient.addColorStop(0, '#444');
            gradient.addColorStop(1, '#000');
        } else {
            // 白子
            gradient.addColorStop(0, '#fff');
            gradient.addColorStop(1, '#ccc');
        }
        
        this.ctx.fillStyle = gradient;
        this.ctx.fill();
        
        // 阴影
        this.ctx.shadowColor = 'rgba(0, 0, 0, 0.4)';
        this.ctx.shadowBlur = 4;
        this.ctx.shadowOffsetX = 2;
        this.ctx.shadowOffsetY = 2;
        this.ctx.fill();
        this.ctx.shadowColor = 'transparent';
        this.ctx.shadowBlur = 0;
        this.ctx.shadowOffsetX = 0;
        this.ctx.shadowOffsetY = 0;
    }

    // 标记最后一步
    drawLastMoveMarker(x, y) {
        const cx = this.padding + x * this.cellSize;
        const cy = this.padding + y * this.cellSize;
        
        this.ctx.strokeStyle = '#ff0000';
        this.ctx.lineWidth = 2;
        this.ctx.beginPath();
        this.ctx.arc(cx, cy, this.cellSize * 0.15, 0, Math.PI * 2);
        this.ctx.stroke();
    }

    // 处理 Canvas 点击
    handleCanvasClick(e) {
        if (this.game.gameOver) return;
        
        // 检查是否是网络对战且不是自己的回合
        if (this.gameMode === 'online' && !window.NetworkManager.isMyTurn(this.game.currentPlayer)) {
            this.showToast('不是你的回合');
            return;
        }
        
        const rect = this.canvas.getBoundingClientRect();
        const x = e.clientX - rect.left;
        const y = e.clientY - rect.top;
        
        // 转换为棋盘坐标
        const boardX = Math.round((x - this.padding) / this.cellSize);
        const boardY = Math.round((y - this.padding) / this.cellSize);
        
        // 检查是否在有效范围内
        if (boardX < 0 || boardX >= this.game.boardSize || 
            boardY < 0 || boardY >= this.game.boardSize) {
            return;
        }
        
        // 尝试落子
        this.makeMove(boardX, boardY);
    }

    // 落子
    makeMove(x, y) {
        const result = this.game.makeMove(x, y);
        
        if (result.success) {
            // 反馈
            if (this.settings.vibrate && window.NativeBridge) {
                window.NativeBridge.vibrate(50);
            }
            
            // 重绘
            this.drawBoard();
            this.updateUI();
            
            // 网络对战：发送落子
            if (this.gameMode === 'online') {
                window.NetworkManager.sendMove(x, y);
            }
            
            // 检查游戏结束
            if (result.gameOver) {
                this.endGame();
            }
        } else {
            this.showToast(result.reason);
        }
    }

    // 更新 UI
    updateUI() {
        // 更新玩家指示器
        document.getElementById('black-turn').classList.toggle('active', this.game.currentPlayer === 1);
        document.getElementById('white-turn').classList.toggle('active', this.game.currentPlayer === 2);
        
        // 更新提子数
        document.getElementById('black-captured').textContent = this.game.captured.black;
        document.getElementById('white-captured').textContent = this.game.captured.white;
        
        // 更新状态文字
        const statusEl = document.getElementById('game-status');
        if (this.game.gameOver) {
            statusEl.textContent = '游戏结束';
        } else {
            const playerName = this.game.currentPlayer === 1 ? 
                this.settings.blackName : this.settings.whiteName;
            statusEl.textContent = `${playerName}落子`;
        }
        
        // 更新按钮状态
        document.getElementById('btn-undo').disabled = this.game.moveHistory.length === 0;
        document.getElementById('btn-pass').disabled = this.game.gameOver;
        document.getElementById('btn-resign').disabled = this.game.gameOver;
    }

    // 新游戏
    newGame() {
        this.game.reset(this.game.boardSize);
        this.drawBoard();
        this.updateUI();
        this.resetTimer();
        this.startTimer();
        
        // 清除未保存的游戏
        window.StorageManager.clearCurrentGame();
        
        this.showToast('新游戏开始');
    }

    // 悔棋
    undo() {
        if (this.gameMode === 'online') {
            this.showToast('网络对战不支持悔棋');
            return;
        }
        
        if (this.game.undo()) {
            this.drawBoard();
            this.updateUI();
            this.showToast('已悔棋');
        } else {
            this.showToast('无法悔棋');
        }
    }

    // 停一手
    pass() {
        const result = this.game.pass();
        
        if (result.success) {
            this.updateUI();
            
            if (this.gameMode === 'online') {
                window.NetworkManager.sendPass();
            }
            
            if (result.gameOver) {
                this.endGame();
            } else {
                this.showToast('停一手');
            }
        }
    }

    // 认输
    resign() {
        if (!confirm('确定要认输吗？')) return;
        
        const winner = this.game.currentPlayer === 1 ? 'white' : 'black';
        const winnerName = winner === 'black' ? this.settings.blackName : this.settings.whiteName;
        
        this.showGameOver(`${winnerName}获胜`, '对方认输');
        
        if (this.gameMode === 'online') {
            window.NetworkManager.sendResign();
        }
        
        // 保存记录
        this.saveGameRecord(winner, 'resign');
    }

    // 游戏结束
    endGame() {
        const result = this.game.countTerritory();
        let message = '';
        
        if (result.winner === 'draw') {
            message = `和棋！黑方${result.black.total}子，白方${result.white.total}子`;
        } else {
            const winnerName = result.winner === 'black' ? this.settings.blackName : this.settings.whiteName;
            message = `${winnerName}获胜！领先${result.diff}子`;
        }
        
        this.showGameOver(
            result.winner === 'black' ? '黑方获胜' : (result.winner === 'white' ? '白方获胜' : '和棋'),
            message
        );
        
        // 保存记录
        this.saveGameRecord(result.winner, 'count');
    }

    // 显示游戏结束弹窗
    showGameOver(title, message) {
        document.getElementById('game-over-title').textContent = title;
        document.getElementById('game-over-message').textContent = message;
        document.getElementById('game-over-modal').classList.remove('hidden');
        
        this.stopTimer();
    }

    // 保存游戏记录
    saveGameRecord(winner, endType) {
        const record = {
            boardSize: this.game.boardSize,
            mode: this.gameMode,
            winner,
            endType,
            blackName: this.settings.blackName,
            whiteName: this.settings.whiteName,
            blackCaptured: this.game.captured.black,
            whiteCaptured: this.game.captured.white,
            moves: this.game.moveHistory.length
        };
        
        window.StorageManager.saveGameRecord(record);
    }

    // 切换游戏模式
    switchMode(mode) {
        if (mode === this.gameMode) return;
        
        this.gameMode = mode;
        
        // 更新按钮状态
        document.querySelectorAll('.mode-btn').forEach(btn => {
            btn.classList.toggle('active', btn.dataset.mode === mode);
        });
        
        // 显示/隐藏网络面板
        const networkPanel = document.getElementById('network-panel');
        if (mode === 'online') {
            networkPanel.classList.remove('hidden');
            // 启动服务器
            this.connectToServer();
        } else {
            networkPanel.classList.add('hidden');
            // 离开房间
            window.NetworkManager.leaveRoom();
        }
        
        // 如果当前有游戏，提示重新开始
        if (this.game.moveHistory.length > 0) {
            if (confirm('切换模式将开始新游戏，确定吗？')) {
                this.newGame();
            }
        }
    }

    // 切换棋盘大小
    switchBoardSize(size) {
        if (size === this.game.boardSize) return;
        
        // 更新按钮状态
        document.querySelectorAll('.size-btn').forEach(btn => {
            btn.classList.toggle('active', parseInt(btn.dataset.size) === size);
        });
        
        // 如果当前有游戏，提示重新开始
        if (this.game.moveHistory.length > 0) {
            if (confirm('切换棋盘大小将开始新游戏，确定吗？')) {
                this.game.reset(size);
                this.resizeCanvas();
                this.drawBoard();
                this.updateUI();
                this.resetTimer();
                this.startTimer();
            }
        } else {
            this.game.reset(size);
            this.resizeCanvas();
            this.drawBoard();
        }
        
        // 保存设置
        this.settings.boardSize = size;
        window.StorageManager.saveSettings(this.settings);
    }

    // 连接服务器
    async connectToServer() {
        this.updateNetworkStatus('连接中...', false);
        
        try {
            await window.NetworkManager.startServer();
            this.updateNetworkStatus('已连接', true);
            this.refreshRoomList();
        } catch (e) {
            this.updateNetworkStatus('连接失败', false);
            this.showToast('无法连接服务器');
        }
    }

    // 刷新房间列表
    async refreshRoomList() {
        const rooms = await window.NetworkManager.getRoomList();
        const roomListEl = document.getElementById('room-list');
        
        if (rooms.length === 0) {
            roomListEl.innerHTML = '<p style="color: #666; text-align: center;">暂无房间</p>';
        } else {
            roomListEl.innerHTML = rooms.map(room => `
                <div class="room-item" data-room-id="${room.roomId}">
                    <div>${room.roomName}</div>
                    <div style="font-size: 0.8rem; color: #888;">
                        ${room.boardSize}路 | ${room.players}/2
                    </div>
                </div>
            `).join('');
            
            // 绑定点击事件
            roomListEl.querySelectorAll('.room-item').forEach(item => {
                item.addEventListener('click', () => {
                    document.getElementById('join-room-id').value = item.dataset.roomId;
                    this.joinRoom();
                });
            });
        }
    }

    // 切换网络面板标签
    switchNetworkTab(tab) {
        document.querySelectorAll('.tab-btn').forEach(btn => {
            btn.classList.toggle('active', btn.dataset.tab === tab);
        });
        
        document.getElementById('create-room-section').classList.toggle('hidden', tab !== 'create');
        document.getElementById('join-room-section').classList.toggle('hidden', tab !== 'join');
        
        if (tab === 'join') {
            this.refreshRoomList();
        }
    }

    // 创建房间
    async createRoom() {
        const roomName = document.getElementById('room-name-input').value.trim();
        const boardSize = parseInt(document.getElementById('room-board-size').value);
        
        if (!roomName) {
            this.showToast('请输入房间名称');
            return;
        }
        
        try {
            await window.NetworkManager.createRoom(roomName, boardSize);
            this.showToast(`房间创建成功：${window.NetworkManager.roomId}`);
            this.closeNetworkPanel();
            this.newGame();
        } catch (e) {
            this.showToast('创建房间失败');
        }
    }

    // 加入房间
    async joinRoom() {
        const roomId = document.getElementById('join-room-id').value.trim();
        
        if (!roomId) {
            this.showToast('请输入房间 ID');
            return;
        }
        
        try {
            await window.NetworkManager.joinRoom(roomId);
            this.showToast('加入房间成功');
            this.closeNetworkPanel();
            this.newGame();
        } catch (e) {
            this.showToast('加入房间失败');
        }
    }

    // 关闭网络面板
    closeNetworkPanel() {
        document.getElementById('network-panel').classList.add('hidden');
    }

    // 更新网络状态
    updateNetworkStatus(text, connected) {
        const statusText = document.getElementById('status-text');
        const statusDot = document.querySelector('.status-dot');
        
        statusText.textContent = text;
        statusDot.className = 'status-dot';
        
        if (connected) {
            statusDot.classList.add('connected');
        } else if (text.includes('连接中')) {
            statusDot.classList.add('connecting');
        }
    }

    // 处理网络消息
    handleNetworkMessage(data) {
        console.log('收到网络消息:', data);
        
        switch (data.type) {
            case 'move':
                // 对方落子
                if (data.color !== window.NetworkManager.playerColor) {
                    this.game.makeMove(data.x, data.y);
                    this.drawBoard();
                    this.updateUI();
                }
                break;
                
            case 'pass':
                if (data.color !== window.NetworkManager.playerColor) {
                    this.game.pass();
                    this.updateUI();
                }
                break;
                
            case 'resign':
                if (data.color !== window.NetworkManager.playerColor) {
                    const winnerName = this.settings.blackName;
                    this.showGameOver(`${winnerName}获胜`, '对方认输');
                    this.saveGameRecord('black', 'resign');
                }
                break;
        }
    }

    // 切换设置面板
    toggleSettings() {
        const panel = document.getElementById('settings-panel');
        panel.classList.toggle('hidden');
        
        if (!panel.classList.contains('hidden')) {
            // 加载当前设置
            document.getElementById('setting-black-name').value = this.settings.blackName;
            document.getElementById('setting-white-name').value = this.settings.whiteName;
            document.getElementById('setting-sound').checked = this.settings.sound;
            document.getElementById('setting-vibrate').checked = this.settings.vibrate;
        }
    }

    // 保存设置
    saveSettings() {
        this.settings.blackName = document.getElementById('setting-black-name').value.trim() || '黑方';
        this.settings.whiteName = document.getElementById('setting-white-name').value.trim() || '白方';
        this.settings.sound = document.getElementById('setting-sound').checked;
        this.settings.vibrate = document.getElementById('setting-vibrate').checked;
        
        window.StorageManager.saveSettings(this.settings);
        this.applySettings();
        this.toggleSettings();
        this.updateUI();
        this.showToast('设置已保存');
    }

    // 应用设置
    applySettings() {
        document.getElementById('black-name').textContent = this.settings.blackName;
        document.getElementById('white-name').textContent = this.settings.whiteName;
    }

    // 检查未完成的游戏
    checkUnfinishedGame() {
        const savedGame = window.StorageManager.loadCurrentGame();
        if (savedGame && savedGame.gameState) {
            if (confirm('发现未完成的游戏，是否继续？')) {
                this.game.loadState(savedGame.gameState);
                this.gameMode = savedGame.mode;
                this.resizeCanvas();
                this.drawBoard();
                this.updateUI();
            }
        }
    }

    // 启动计时器
    startTimer() {
        this.startTime = Date.now();
        this.timerInterval = setInterval(() => {
            const elapsed = Math.floor((Date.now() - this.startTime) / 1000);
            const minutes = Math.floor(elapsed / 60).toString().padStart(2, '0');
            const seconds = (elapsed % 60).toString().padStart(2, '0');
            document.getElementById('timer').textContent = `${minutes}:${seconds}`;
        }, 1000);
    }

    // 重置计时器
    resetTimer() {
        this.stopTimer();
        document.getElementById('timer').textContent = '00:00';
    }

    // 停止计时器
    stopTimer() {
        if (this.timerInterval) {
            clearInterval(this.timerInterval);
            this.timerInterval = null;
        }
    }

    // 显示 Toast
    showToast(message) {
        if (window.NativeBridge) {
            window.NativeBridge.showToast(message);
        } else {
            console.log('[Toast]', message);
        }
    }
}

// 启动应用
window.addEventListener('DOMContentLoaded', () => {
    window.goUI = new GoUI();
});
