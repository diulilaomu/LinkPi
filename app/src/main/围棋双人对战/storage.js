/**
 * 存储模块 - 封装 NativeBridge STORAGE API
 * 用于保存游戏记录、设置等数据
 */

class StorageManager {
    constructor() {
        this.prefix = 'go_game_';
        this.available = typeof window !== 'undefined' && window.NativeBridge !== undefined;
    }

    // 保存数据
    save(key, value) {
        if (!this.available) {
            console.warn('NativeBridge 不可用，使用 localStorage 降级');
            try {
                localStorage.setItem(this.prefix + key, JSON.stringify(value));
                return true;
            } catch (e) {
                console.error('存储失败:', e);
                return false;
            }
        }
        
        try {
            window.NativeBridge.saveData(this.prefix + key, JSON.stringify(value));
            return true;
        } catch (e) {
            console.error('存储失败:', e);
            return false;
        }
    }

    // 加载数据
    load(key) {
        if (!this.available) {
            try {
                const data = localStorage.getItem(this.prefix + key);
                return data ? JSON.parse(data) : null;
            } catch (e) {
                console.error('加载失败:', e);
                return null;
            }
        }
        
        try {
            const data = window.NativeBridge.loadData(this.prefix + key);
            return data ? JSON.parse(data) : null;
        } catch (e) {
            console.error('加载失败:', e);
            return null;
        }
    }

    // 删除数据
    remove(key) {
        if (!this.available) {
            try {
                localStorage.removeItem(this.prefix + key);
                return true;
            } catch (e) {
                return false;
            }
        }
        
        try {
            window.NativeBridge.removeData(this.prefix + key);
            return true;
        } catch (e) {
            return false;
        }
    }

    // 清除所有数据
    clear() {
        if (!this.available) {
            try {
                localStorage.clear();
                return true;
            } catch (e) {
                return false;
            }
        }
        
        try {
            window.NativeBridge.clearData();
            return true;
        } catch (e) {
            return false;
        }
    }

    // 列出所有键
    listKeys() {
        if (!this.available) {
            try {
                const keys = [];
                for (let i = 0; i < localStorage.length; i++) {
                    const key = localStorage.key(i);
                    if (key && key.startsWith(this.prefix)) {
                        keys.push(key.replace(this.prefix, ''));
                    }
                }
                return keys;
            } catch (e) {
                return [];
            }
        }
        
        try {
            const keysStr = window.NativeBridge.listKeys();
            if (!keysStr) return [];
            return keysStr.split(',').filter(k => k.startsWith(this.prefix)).map(k => k.replace(this.prefix, ''));
        } catch (e) {
            return [];
        }
    }

    // 保存游戏记录
    saveGameRecord(record) {
        const records = this.load('game_records') || [];
        records.unshift({
            id: Date.now(),
            timestamp: new Date().toISOString(),
            ...record
        });
        // 只保留最近 50 局
        if (records.length > 50) records.length = 50;
        return this.save('game_records', records);
    }

    // 获取游戏记录列表
    getGameRecords() {
        return this.load('game_records') || [];
    }

    // 保存设置
    saveSettings(settings) {
        return this.save('settings', settings);
    }

    // 加载设置
    loadSettings() {
        return this.load('settings') || {
            blackName: '黑方',
            whiteName: '白方',
            sound: true,
            vibrate: true,
            boardSize: 19
        };
    }

    // 保存当前游戏状态（用于断线恢复）
    saveCurrentGame(gameState, mode, roomId = null) {
        return this.save('current_game', {
            gameState,
            mode,
            roomId,
            savedAt: new Date().toISOString()
        });
    }

    // 加载当前游戏状态
    loadCurrentGame() {
        return this.load('current_game');
    }

    // 清除当前游戏状态
    clearCurrentGame() {
        return this.remove('current_game');
    }
}

// 创建全局实例
window.StorageManager = new StorageManager();
