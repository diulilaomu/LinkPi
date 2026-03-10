/**
 * 网络模块 - 封装 go_lan_server 模块调用
 * 处理局域网对战的 WebSocket 通信
 */

class NetworkManager {
    constructor() {
        this.moduleName = 'go_lan_server';
        this.connected = false;
        this.roomId = null;
        this.playerColor = null; // 1=黑，2=白
        this.onMessage = null;
        this.onStatusChange = null;
        this.messageQueue = [];
    }

    // 启动服务器
    async startServer() {
        try {
            const result = await this.callModule('start_server', {});
            console.log('服务器启动:', result);
            return result;
        } catch (e) {
            console.error('启动服务器失败:', e);
            throw e;
        }
    }

    // 创建房间
    async createRoom(roomName, boardSize = 19) {
        try {
            const result = await this.callModule('create_room', {
                roomName,
                boardSize: boardSize.toString()
            });
            
            if (result && result.roomId) {
                this.roomId = result.roomId;
                this.playerColor = 1; // 创建者是黑方
                this.connected = true;
                this.updateStatus('已创建房间', true);
            }
            
            return result;
        } catch (e) {
            console.error('创建房间失败:', e);
            throw e;
        }
    }

    // 加入房间
    async joinRoom(roomId) {
        try {
            const result = await this.callModule('join_room', {
                roomId
            });
            
            if (result && result.success) {
                this.roomId = roomId;
                this.playerColor = 2; // 加入者是白方
                this.connected = true;
                this.updateStatus('已加入房间', true);
            }
            
            return result;
        } catch (e) {
            console.error('加入房间失败:', e);
            throw e;
        }
    }

    // 获取房间列表
    async getRoomList() {
        try {
            const result = await this.callModule('list_rooms', {});
            return result || [];
        } catch (e) {
            console.error('获取房间列表失败:', e);
            return [];
        }
    }

    // 发送落子消息
    async sendMove(x, y) {
        if (!this.connected || !this.roomId) return false;
        
        try {
            await this.callModule('send_message', {
                roomId: this.roomId,
                message: JSON.stringify({
                    type: 'move',
                    x,
                    y,
                    color: this.playerColor
                })
            });
            return true;
        } catch (e) {
            console.error('发送落子失败:', e);
            return false;
        }
    }

    // 发送停手消息
    async sendPass() {
        if (!this.connected || !this.roomId) return false;
        
        try {
            await this.callModule('send_message', {
                roomId: this.roomId,
                message: JSON.stringify({
                    type: 'pass',
                    color: this.playerColor
                })
            });
            return true;
        } catch (e) {
            console.error('发送停手失败:', e);
            return false;
        }
    }

    // 发送认输消息
    async sendResign() {
        if (!this.connected || !this.roomId) return false;
        
        try {
            await this.callModule('send_message', {
                roomId: this.roomId,
                message: JSON.stringify({
                    type: 'resign',
                    color: this.playerColor
                })
            });
            return true;
        } catch (e) {
            console.error('发送认输失败:', e);
            return false;
        }
    }

    // 离开房间
    async leaveRoom() {
        if (!this.roomId) return;
        
        try {
            await this.callModule('leave_room', {
                roomId: this.roomId
            });
        } catch (e) {
            console.error('离开房间失败:', e);
        } finally {
            this.reset();
        }
    }

    // 接收消息（轮询方式）
    async pollMessages() {
        if (!this.connected || !this.roomId) return [];
        
        try {
            const result = await this.callModule('get_messages', {
                roomId: this.roomId
            });
            
            const messages = result || [];
            
            for (const msg of messages) {
                this.handleMessage(msg);
            }
            
            return messages;
        } catch (e) {
            console.error('获取消息失败:', e);
            return [];
        }
    }

    // 处理接收到的消息
    handleMessage(msg) {
        try {
            const data = typeof msg === 'string' ? JSON.parse(msg) : msg;
            
            if (this.onMessage) {
                this.onMessage(data);
            }
        } catch (e) {
            console.error('解析消息失败:', e);
        }
    }

    // 调用模块
    async callModule(endpoint, params = {}) {
        return new Promise((resolve, reject) => {
            // 使用 window.callModule 如果可用，否则模拟
            if (window.callModule) {
                window.callModule(this.moduleName, endpoint, params)
                    .then(resolve)
                    .catch(reject);
            } else {
                // 模拟响应（用于测试）
                console.log(`[模拟] 调用 ${endpoint}:`, params);
                resolve({ success: true });
            }
        });
    }

    // 更新状态
    updateStatus(text, connected = false) {
        this.connected = connected;
        if (this.onStatusChange) {
            this.onStatusChange(text, connected);
        }
    }

    // 重置状态
    reset() {
        this.connected = false;
        this.roomId = null;
        this.playerColor = null;
        this.updateStatus('未连接', false);
    }

    // 检查是否是自己的回合
    isMyTurn(currentPlayer) {
        return this.connected && currentPlayer === this.playerColor;
    }
}

// 创建全局实例
window.NetworkManager = new NetworkManager();
