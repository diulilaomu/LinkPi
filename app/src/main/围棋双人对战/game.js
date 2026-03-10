/**
 * 围棋游戏核心逻辑
 * 包含棋盘状态管理、落子验证、BFS 提子算法、打劫检测
 */

class GoGame {
    constructor(boardSize = 19) {
        this.boardSize = boardSize;
        this.board = []; // 0=空，1=黑，2=白
        this.currentPlayer = 1; // 1=黑，2=白
        this.captured = { black: 0, white: 0 }; // 各方提子数
        this.moveHistory = []; // 落子历史
        this.koPosition = null; // 打劫位置
        this.passCount = 0; // 连续停手次数
        this.gameOver = false;
        this.lastMove = null; // 最后一步
        
        this.initBoard();
    }

    // 初始化棋盘
    initBoard() {
        this.board = [];
        for (let i = 0; i < this.boardSize; i++) {
            this.board[i] = [];
            for (let j = 0; j < this.boardSize; j++) {
                this.board[i][j] = 0;
            }
        }
    }

    // 重置游戏
    reset(boardSize = this.boardSize) {
        this.boardSize = boardSize;
        this.currentPlayer = 1;
        this.captured = { black: 0, white: 0 };
        this.moveHistory = [];
        this.koPosition = null;
        this.passCount = 0;
        this.gameOver = false;
        this.lastMove = null;
        this.initBoard();
    }

    // 检查位置是否在棋盘内
    isValidPosition(x, y) {
        return x >= 0 && x < this.boardSize && y >= 0 && y < this.boardSize;
    }

    // 检查位置是否为空
    isEmpty(x, y) {
        return this.isValidPosition(x, y) && this.board[y][x] === 0;
    }

    // 获取棋子的颜色（0=空，1=黑，2=白）
    getStone(x, y) {
        if (!this.isValidPosition(x, y)) return 0;
        return this.board[y][x];
    }

    // BFS 算法计算棋子的气
    getLiberties(x, y, color) {
        const visited = new Set();
        const liberties = new Set();
        const queue = [[x, y]];
        const key = (px, py) => `${px},${py}`;
        
        visited.add(key(x, y));
        
        while (queue.length > 0) {
            const [cx, cy] = queue.shift();
            
            // 检查四个方向
            const directions = [[0, 1], [0, -1], [1, 0], [-1, 0]];
            for (const [dx, dy] of directions) {
                const nx = cx + dx;
                const ny = cy + dy;
                
                if (!this.isValidPosition(nx, ny)) continue;
                
                const neighborKey = key(nx, ny);
                if (visited.has(neighborKey)) continue;
                
                const neighborColor = this.board[ny][nx];
                
                if (neighborColor === 0) {
                    // 空位是气
                    liberties.add(neighborKey);
                } else if (neighborColor === color) {
                    // 同色棋子，加入队列继续搜索
                    visited.add(neighborKey);
                    queue.push([nx, ny]);
                }
            }
        }
        
        return liberties.size;
    }

    // 获取相连的同色棋子组
    getGroup(x, y, color) {
        const group = [];
        const visited = new Set();
        const queue = [[x, y]];
        const key = (px, py) => `${px},${py}`;
        
        visited.add(key(x, y));
        
        while (queue.length > 0) {
            const [cx, cy] = queue.shift();
            group.push({ x: cx, y: cy });
            
            const directions = [[0, 1], [0, -1], [1, 0], [-1, 0]];
            for (const [dx, dy] of directions) {
                const nx = cx + dx;
                const ny = cy + dy;
                
                if (!this.isValidPosition(nx, ny)) continue;
                
                const neighborKey = key(nx, ny);
                if (visited.has(neighborKey)) continue;
                
                if (this.board[ny][nx] === color) {
                    visited.add(neighborKey);
                    queue.push([nx, ny]);
                }
            }
        }
        
        return group;
    }

    // 检查落子后是否会导致对方棋子被提
    getCapturedStones(x, y, color) {
        const opponentColor = color === 1 ? 2 : 1;
        const captured = [];
        const directions = [[0, 1], [0, -1], [1, 0], [-1, 0]];
        
        for (const [dx, dy] of directions) {
            const nx = x + dx;
            const ny = y + dy;
            
            if (!this.isValidPosition(nx, ny)) continue;
            if (this.board[ny][nx] !== opponentColor) continue;
            
            // 检查这个方向的对方棋子组是否还有气
            const group = this.getGroup(nx, ny, opponentColor);
            const hasLiberty = group.some(stone => {
                const stoneDirections = [[0, 1], [0, -1], [1, 0], [-1, 0]];
                for (const [sx, sy] of stoneDirections) {
                    const sx2 = stone.x + sx;
                    const sy2 = stone.y + sy;
                    if (this.isValidPosition(sx2, sy2) && this.board[sy2][sx2] === 0) {
                        return true;
                    }
                }
                return false;
            });
            
            if (!hasLiberty) {
                // 这个组被提了
                for (const stone of group) {
                    if (!captured.some(s => s.x === stone.x && s.y === stone.y)) {
                        captured.push(stone);
                    }
                }
            }
        }
        
        return captured;
    }

    // 检查自杀（落子后自己的棋子没有气且没有提对方）
    isSuicide(x, y, color) {
        // 临时放置棋子
        const originalValue = this.board[y][x];
        this.board[y][x] = color;
        
        // 检查这步棋是否有气
        const liberties = this.getLiberties(x, y, color);
        const captured = this.getCapturedStones(x, y, color);
        
        // 恢复原状
        this.board[y][x] = originalValue;
        
        // 如果有气或者能提子，就不是自杀
        return liberties === 0 && captured.length === 0;
    }

    // 检查打劫
    isKo(x, y, color) {
        if (this.koPosition === null) return false;
        return x === this.koPosition.x && y === this.koPosition.y;
    }

    // 尝试落子
    makeMove(x, y) {
        if (this.gameOver) return { success: false, reason: '游戏已结束' };
        if (!this.isEmpty(x, y)) return { success: false, reason: '位置已有棋子' };
        
        const color = this.currentPlayer;
        
        // 检查打劫
        if (this.isKo(x, y, color)) {
            return { success: false, reason: '打劫：不能立即回提' };
        }
        
        // 检查自杀
        if (this.isSuicide(x, y, color)) {
            return { success: false, reason: '自杀：落子后无气' };
        }
        
        // 记录当前棋盘状态用于打劫检测
        const previousBoard = this.board.map(row => [...row]);
        
        // 放置棋子
        this.board[y][x] = color;
        
        // 检查并提子
        const capturedStones = this.getCapturedStones(x, y, color);
        let capturedCount = 0;
        
        for (const stone of capturedStones) {
            this.board[stone.y][stone.x] = 0;
            capturedCount++;
        }
        
        // 更新提子数
        if (color === 1) {
            this.captured.black += capturedCount;
        } else {
            this.captured.white += capturedCount;
        }
        
        // 更新打劫位置
        if (capturedCount === 1 && this.getLiberties(x, y, color) === 1) {
            // 可能形成打劫
            const opponentColor = color === 1 ? 2 : 1;
            const capturedStone = capturedStones[0];
            
            // 检查是否是打劫形状
            if (this.getGroup(x, y, color).length === 1) {
                this.koPosition = { x: capturedStone.x, y: capturedStone.y };
            } else {
                this.koPosition = null;
            }
        } else {
            this.koPosition = null;
        }
        
        // 记录历史
        this.moveHistory.push({
            x, y, color,
            captured: capturedCount,
            previousBoard,
            koPosition: this.koPosition ? { ...this.koPosition } : null
        });
        
        this.lastMove = { x, y };
        this.passCount = 0;
        
        // 切换玩家
        this.currentPlayer = color === 1 ? 2 : 1;
        
        return { success: true, captured: capturedCount };
    }

    // 停一手
    pass() {
        if (this.gameOver) return { success: false, reason: '游戏已结束' };
        
        this.passCount++;
        this.moveHistory.push({
            pass: true,
            color: this.currentPlayer
        });
        
        // 连续两次停手，游戏结束
        if (this.passCount >= 2) {
            this.gameOver = true;
            return { success: true, gameOver: true };
        }
        
        this.currentPlayer = this.currentPlayer === 1 ? 2 : 1;
        return { success: true, gameOver: false };
    }

    // 悔棋
    undo() {
        if (this.moveHistory.length === 0) return false;
        if (this.gameOver) {
            this.gameOver = false;
        }
        
        const lastMove = this.moveHistory.pop();
        
        if (lastMove.pass) {
            // 悔停手
            this.passCount--;
            this.currentPlayer = lastMove.color;
        } else {
            // 悔落子
            this.board = lastMove.previousBoard.map(row => [...row]);
            this.koPosition = lastMove.koPosition ? { ...lastMove.koPosition } : null;
            this.currentPlayer = lastMove.color;
            
            // 恢复提子数
            if (lastMove.captured > 0) {
                if (lastMove.color === 1) {
                    this.captured.black -= lastMove.captured;
                } else {
                    this.captured.white -= lastMove.captured;
                }
            }
            
            // 更新最后一步
            if (this.moveHistory.length > 0) {
                const prevMove = this.moveHistory[this.moveHistory.length - 1];
                if (!prevMove.pass) {
                    this.lastMove = { x: prevMove.x, y: prevMove.y };
                } else {
                    this.lastMove = null;
                }
            } else {
                this.lastMove = null;
            }
        }
        
        return true;
    }

    // 获取游戏状态
    getState() {
        return {
            board: this.board.map(row => [...row]),
            boardSize: this.boardSize,
            currentPlayer: this.currentPlayer,
            captured: { ...this.captured },
            moveHistory: this.moveHistory.length,
            koPosition: this.koPosition ? { ...this.koPosition } : null,
            gameOver: this.gameOver,
            lastMove: this.lastMove ? { ...this.lastMove } : null,
            passCount: this.passCount
        };
    }

    // 从状态恢复
    loadState(state) {
        this.board = state.board.map(row => [...row]);
        this.boardSize = state.boardSize;
        this.currentPlayer = state.currentPlayer;
        this.captured = { ...state.captured };
        this.koPosition = state.koPosition ? { ...state.koPosition } : null;
        this.gameOver = state.gameOver;
        this.lastMove = state.lastMove ? { ...state.lastMove } : null;
        this.passCount = state.passCount;
    }

    // 计算地盘（简化版：数子法）
    countTerritory() {
        const visited = new Array(this.boardSize).fill(null).map(() => 
            new Array(this.boardSize).fill(false)
        );
        
        let blackTerritory = 0;
        let whiteTerritory = 0;
        let blackStones = 0;
        let whiteStones = 0;
        
        // 数棋子
        for (let y = 0; y < this.boardSize; y++) {
            for (let x = 0; x < this.boardSize; x++) {
                if (this.board[y][x] === 1) blackStones++;
                else if (this.board[y][x] === 2) whiteStones++;
            }
        }
        
        // BFS 计算空位归属
        for (let y = 0; y < this.boardSize; y++) {
            for (let x = 0; x < this.boardSize; x++) {
                if (this.board[y][x] !== 0 || visited[y][x]) continue;
                
                // BFS 找相连的空位区域
                const emptyGroup = [];
                const queue = [[x, y]];
                visited[y][x] = true;
                
                let touchesBlack = false;
                let touchesWhite = false;
                
                while (queue.length > 0) {
                    const [cx, cy] = queue.shift();
                    emptyGroup.push({ x: cx, y: cy });
                    
                    const directions = [[0, 1], [0, -1], [1, 0], [-1, 0]];
                    for (const [dx, dy] of directions) {
                        const nx = cx + dx;
                        const ny = cy + dy;
                        
                        if (!this.isValidPosition(nx, ny)) continue;
                        
                        if (this.board[ny][nx] === 0 && !visited[ny][nx]) {
                            visited[ny][nx] = true;
                            queue.push([nx, ny]);
                        } else if (this.board[ny][nx] === 1) {
                            touchesBlack = true;
                        } else if (this.board[ny][nx] === 2) {
                            touchesWhite = true;
                        }
                    }
                }
                
                // 如果只接触一方，算作该方地盘
                if (touchesBlack && !touchesWhite) {
                    blackTerritory += emptyGroup.length;
                } else if (touchesWhite && !touchesBlack) {
                    whiteTerritory += emptyGroup.length;
                }
            }
        }
        
        // 中国规则：数子法（棋盘点数的一半为基准）
        const halfPoints = (this.boardSize * this.boardSize) / 2;
        const blackTotal = blackStones + blackTerritory;
        const whiteTotal = whiteStones + whiteTerritory;
        
        return {
            black: { stones: blackStones, territory: blackTerritory, total: blackTotal },
            white: { stones: whiteStones, territory: whiteTerritory, total: whiteTotal },
            winner: blackTotal > whiteTotal ? 'black' : (whiteTotal > blackTotal ? 'white' : 'draw'),
            diff: Math.abs(blackTotal - whiteTotal)
        };
    }
}

// 导出给其他模块使用
window.GoGame = GoGame;
