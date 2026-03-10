# 架构说明

## 概述
这是一个基于Canvas渲染的围棋对战应用，采用模块化设计分离游戏逻辑、UI交互、网络通信和数据存储。

## 核心文件
- index.html：应用入口，定义棋盘Canvas、控制面板和各类弹窗结构
- game.js：核心游戏逻辑，包含棋盘状态、落子验证、BFS提子算法、打劫检测
- ui.js：UI交互层，整合游戏逻辑、存储和网络模块，处理用户操作
- network.js：网络对战模块，封装go_lan_server实现局域网房间创建和加入
- storage.js：数据持久化模块，封装NativeBridge STORAGE API保存游戏记录和设置
- styles.css：应用样式，定义响应式布局和棋盘视觉效果

## 关键代码
- `game.js L62-L98`：BFS算法计算棋子气数，用于判断提子和棋子存活状态
- `ui.js L54-L58`：Canvas点击和触摸事件处理，将用户操作转换为棋盘坐标
- `network.js L30-L48`：创建房间逻辑，初始化局域网对战会话
- `storage.js L13-L32`：数据保存方法，支持NativeBridge和localStorage降级方案

## 数据流
用户点击棋盘 → UI模块捕获坐标并转换 → 游戏逻辑验证落子合法性 → 执行提子计算和状态更新 → Canvas重绘棋盘 → 存储模块保存游戏记录