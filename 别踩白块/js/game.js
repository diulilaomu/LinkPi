'use strict';

class DontTapWhite {
    constructor() {
        this.boardEl = document.getElementById('board');
        this.scoreEl = document.getElementById('score');
        this.bestEl = document.getElementById('best');
        this.startOverlay = document.getElementById('start-overlay');
        this.overOverlay = document.getElementById('over-overlay');
        this.finalScoreEl = document.getElementById('final-score');
        this.finalBestEl = document.getElementById('final-best');
        this.startBtn = document.getElementById('start-btn');

        this.COLS = 4;
        this.VISIBLE = 5;
        this.rows = [];
        this.score = 0;
        this.bestScore = 0;
        this.playing = false;
        this._id = 0;
        this.boardHeight = 0;
        this.rowHeight = 0;

        // 滚动状态
        this.scrollOffset = 0;
        this.speed = 2;
        this.baseSpeed = 2;
        this.rafId = 0;

        this.loadBest();
        this.updateScoreDisplay();
        this.bindEvents();
    }

    /* -------- 持久化 -------- */

    loadBest() {
        try {
            var v;
            if (window.NativeBridge) {
                v = window.NativeBridge.loadData('dtw_best');
            } else {
                v = localStorage.getItem('dtw_best');
            }
            if (v) this.bestScore = parseInt(v, 10) || 0;
        } catch (e) { /* ignore */ }
    }

    saveBest() {
        try {
            if (window.NativeBridge) {
                window.NativeBridge.saveData('dtw_best', String(this.bestScore));
            } else {
                localStorage.setItem('dtw_best', String(this.bestScore));
            }
        } catch (e) { /* ignore */ }
    }

    /* -------- 事件绑定 -------- */

    bindEvents() {
        this.boardEl.addEventListener('pointerdown', function (e) {
            e.preventDefault();
            var t = e.target;
            if (t.classList.contains('tile')) {
                this.handleTap(+t.dataset.id, +t.dataset.col, t);
            }
        }.bind(this));

        document.getElementById('play-btn').addEventListener('click', this.start.bind(this));
        document.getElementById('restart-btn').addEventListener('click', this.start.bind(this));
        this.startBtn.addEventListener('click', function () {
            if (!this.playing) this.start();
        }.bind(this));

        document.addEventListener('keydown', function (e) {
            if (!this.playing) return;
            var n = parseInt(e.key, 10);
            if (n >= 1 && n <= 4) {
                this.handleKeyTap(n - 1);
            }
        }.bind(this));

        window.addEventListener('resize', function () {
            this.measure();
            if (this.playing) this.render();
        }.bind(this));

        document.addEventListener('contextmenu', function (e) { e.preventDefault(); });
        document.addEventListener('dblclick', function (e) { e.preventDefault(); }, { passive: false });
    }

    /* -------- 游戏流程 -------- */

    createRow() {
        return {
            id: this._id++,
            blackCol: Math.floor(Math.random() * this.COLS),
            clicked: false
        };
    }

    measure() {
        this.boardHeight = this.boardEl.clientHeight;
        this.rowHeight = this.boardHeight / this.VISIBLE;
    }

    start() {
        if (this.rafId) cancelAnimationFrame(this.rafId);
        this._id = 0;
        this.rows = [];
        this.score = 0;
        this.playing = true;
        this.scrollOffset = 0;
        this.speed = this.baseSpeed;

        this.measure();

        for (var i = 0; i < this.VISIBLE + 2; i++) {
            this.rows.push(this.createRow());
        }

        this.startOverlay.classList.remove('show');
        this.overOverlay.classList.remove('show');

        this.startBtn.textContent = '游戏中…';
        this.startBtn.disabled = true;
        this.startBtn.style.opacity = '0.5';

        this.updateScoreDisplay();
        this.render();
        this.loop();
    }

    /* -------- 游戏主循环（自动下落） -------- */

    loop() {
        if (!this.playing) return;

        this.scrollOffset += this.speed;

        // 滚过一整行 → 检查底部行是否已被点击
        if (this.scrollOffset >= this.rowHeight) {
            var bottom = this.rows[0];
            if (bottom && !bottom.clicked) {
                // 漏掉黑块 → 游戏结束
                this.markMissed(bottom);
                this.gameOver();
                return;
            }
            this.rows.shift();
            this.rows.push(this.createRow());
            this.scrollOffset -= this.rowHeight;
        }

        this.render();
        this.rafId = requestAnimationFrame(this.loop.bind(this));
    }

    /* -------- 点击处理 -------- */

    handleTap(rowId, col, el) {
        if (!this.playing) return;

        var rowIndex = -1;
        for (var i = 0; i < this.rows.length; i++) {
            if (this.rows[i].id === rowId) { rowIndex = i; break; }
        }
        if (rowIndex < 0) return;

        var row = this.rows[rowIndex];
        if (row.clicked) return;

        // 点了白块 → 游戏结束
        if (col !== row.blackCol) {
            if (el) el.classList.add('wrong');
            this.gameOver();
            return;
        }

        // 点对了
        row.clicked = true;
        this.score++;

        // 加速：每 10 分 +0.5，上限 12
        this.speed = Math.min(this.baseSpeed + Math.floor(this.score / 10) * 0.5, 12);

        try {
            if (window.NativeBridge) window.NativeBridge.vibrate(25);
        } catch (e) { /* ignore */ }

        this.updateScoreDisplay();
        if (el) el.classList.add('clicked');
    }

    handleKeyTap(col) {
        for (var i = 0; i < this.rows.length; i++) {
            if (!this.rows[i].clicked) {
                var el = this.boardEl.querySelector(
                    '[data-id="' + this.rows[i].id + '"][data-col="' + col + '"]');
                this.handleTap(this.rows[i].id, col, el);
                return;
            }
        }
    }

    markMissed(row) {
        var els = this.boardEl.querySelectorAll('[data-id="' + row.id + '"]');
        for (var i = 0; i < els.length; i++) {
            if (+els[i].dataset.col === row.blackCol) {
                els[i].classList.add('missed');
            }
        }
    }

    gameOver() {
        this.playing = false;
        if (this.rafId) { cancelAnimationFrame(this.rafId); this.rafId = 0; }

        if (this.score > this.bestScore) {
            this.bestScore = this.score;
            this.saveBest();
        }

        try {
            if (window.NativeBridge) window.NativeBridge.vibrate(200);
        } catch (e) { /* ignore */ }

        this.updateScoreDisplay();
        this.finalScoreEl.textContent = this.score;
        this.finalBestEl.textContent = this.bestScore;

        this.startBtn.textContent = '开始游戏';
        this.startBtn.disabled = false;
        this.startBtn.style.opacity = '1';

        var overlay = this.overOverlay;
        setTimeout(function () { overlay.classList.add('show'); }, 500);
    }

    /* -------- 渲染 -------- */

    updateScoreDisplay() {
        this.scoreEl.textContent = this.score;
        this.bestEl.textContent = this.bestScore;
    }

    render() {
        var h = this.boardHeight;
        var rh = this.rowHeight;
        var offset = this.scrollOffset;
        var parts = [];

        for (var i = 0; i < this.rows.length; i++) {
            var row = this.rows[i];
            // scrollOffset 让所有行持续向下移动
            var y = h - (i + 1) * rh + offset;

            if (y + rh <= 0 || y >= h) continue;

            var clicked = row.clicked;
            parts.push('<div class="row" style="top:' + y + 'px;height:' + rh + 'px">');
            for (var c = 0; c < this.COLS; c++) {
                var isBlack = (c === row.blackCol);
                var cls = 'tile';
                if (isBlack) {
                    cls += clicked ? ' black clicked' : ' black';
                } else {
                    cls += ' white';
                }
                parts.push(
                    '<div class="' + cls +
                    '" data-id="' + row.id +
                    '" data-col="' + c + '"></div>'
                );
            }
            parts.push('</div>');
        }

        this.boardEl.innerHTML = parts.join('');
    }
}

/* -------- 启动 -------- */

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () { new DontTapWhite(); });
} else {
    new DontTapWhite();
}
