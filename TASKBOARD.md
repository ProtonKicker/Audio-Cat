# Audio Cat — 任务板与项目状态

> 最后更新：2026-10-17  
> 当前版本：1.1.2（开发中）  
> 包名：`com.audiocat.debug`（applicationIdSuffix `.debug`）

---

## 项目简介

Audio Cat 是一款轻量级、完全离线的 Android 音频播放器，专为从本地视频文件中提取音频而设计。不解码视频流，只解码音频，节省 CPU/GPU/电量。支持 MP3/FLAC/AAC/OGG 等音频格式和 MP4/MKV/AVI/MOV/WebM 等视频容器（仅提取音频）。

- 4 个底部导航 Tab：正在播放 / 播放列表 / 文件 / 设置
- ExoPlayer (Media3 1.11.1) 音频播放
- Room 数据库 + SharedPreferences
- 12 段参数 EQ、混响、低音增强、音高/速度控制

---

## 设备信息

| 设备 | ADB Serial | Android | 状态 | 备注 |
|---|---|---|---|---|
| Redmi Turbo 4 Pro | `C7Y6R19828004113` | 新机型（待确认） | ✅ 已连接 | 主要测试机 |

---

## 已完成的修复 ✅

### Bug 1 — 切歌自动播放不一致
**现象**：切换歌曲时有时自动播放，有时不会。  
**根因**：播放列表中有已删除文件的残留行，切到这些轨道时 ExoPlayer 报错后停止；`PlayerConnection.next()/previous()` 的 fallback 路径使用 `seekToNextMediaItem()` 不触发 playWhenReady。  
**修复**：`PlaybackService.onPlayerError` 删除损坏文件行 + skipBrokenItem；`PlayerConnection.next()/previous()` fallback 路径在 seek 后显式调用 `play()`。

### Bug 2 — 设置页所有开关无法点击
**现象**：设置页所有 toggle 都无法通过点击切换。  
**根因**：`item_settings_row.xml` 的 `rowSettings` 设置了 `clickable="false"` 和 `focusable="false"`；且 `addRow()` 只在有 onClick 时才设监听。  
**修复**：移除 clickable/focusable=false；`addRow()` 对 switch 行改为 rowRoot click → switch.toggle()。

### Bug 3 — 随机播放/拖拽/移除时音频短暂中断
**现象**：切换 shuffle、拖拽重排队列、移除队列项时都有可闻的短暂停顿。  
**根因**：`setShuffle()` / `moveInQueue()` / `removeFromQueue()` 都调用 `pushQueueToPlayer()` → `setMediaItems()+prepare()` → 解码器重建 → 音频空隙。  
**修复**：
- `setShuffle()` 改用 `reorderPlayerPaths()` 辅助方法，使用 `moveMediaItem()` 原地重排
- `moveInQueue()` 改用单个 `moveMediaItem(from, to)`
- `removeFromQueue()` 当移除的非播放项时改用 `removeMediaItem(position)`
- 新增 `reorderPlayerPaths()` 辅助方法（最多 n-1 次 moveMediaItem）

### Bug 4 — 封面加载不充分（双重节流 bug）
**根因**：`enrichBacklog()` 先对候选集 `.take(artBudget)`，然后传给 `enrich()` 内部又 `.take(artBudget)`，导致大曲库封面增量加载被严重限制。  
**修复**：移除 enrichBacklog 中的重复 take。

### Bug 5 — Shuffle 状态 UI 不同步
**现象**：开启随机播放后，Shuffle 按钮始终显示关闭。  
**根因**：`PlayerConnection.syncAll()` 和 `refreshQueue()` 都读 `player.shuffleModeEnabled`，但该值始终为 false（app 管理 shuffle 靠重排 item，不设原生 shuffleMode）。  
**修复**：改为从 `service.queue.shuffle` 读取；`onShuffleModeEnabledChanged()` 仅在 service 为 null 时生效；`PlaybackService.queue` 改为 internal。

### Bug 6 — pruneMissing 音量检测错误
**根因**：`volumeOf()` 固定取 segments[1] 和 segments[2]，当 SD 卡路径嵌套 >3 段时返回错误的 mount point。  
**修复**：重写为正确区分内部存储和可移动存储。

---

## 已实现的新功能 🎯

### 功能 1 — Playlist 泳道拖拽排序
用户可通过 lane card 前端的 6-dot 拖拽手柄重新排列 playlist 顺序。  
**实现**：新增 `ic_drag_handle.xml`；修改 `PlaylistLaneAdapter` 增加 `onStartDrag` 和 `moveLaneItem()`；`PlaylistFragment.setUpLane()` 创建 `ItemTouchHelper.Callback` 处理水平拖拽；"All songs" 卡片固定首位不可拖拽；`Playlist` 实体新增 `lane_order` 列（DB v3→v4）；`PlaylistDao.setLaneOrder()` 持久化新顺序。

### 功能 2 — Playlist 折叠下拉菜单
在 header 区域新增下拉按钮，点击弹出 `ListPopupWindow`，以逐行列表展示所有 playlist（不含歌曲），点击即切换到对应 playlist。  
**实现**：header 新增 `btnPlaylistPicker`；`PlaylistFragment.showPlaylistPicker()` 创建 ListPopupWindow（宽度 60% 或 200dp）；维护 `laneRows` 快照。

---

## 待手动验证（在 Redmi Turbo 4 Pro 上）🔴

- [ ] **开关切换**：所有设置开关正常工作
- [ ] **切歌自动播放**：切歌始终自动播放
- [ ] **封面加载**：扫描后封面正常显示
- [ ] **音效/均衡器**：EQ、低音增强、混响
- [ ] **蓝牙耳机**：连接/断开自动暂停/恢复
- [ ] **通知栏控制**：播放暂停/下一首/上一首
- [ ] **全新安装**：DB v4 的 lane_order 对已有用户表现正确
- [ ] **Playlist 拖拽**：lane 拖拽排序符合预期
- [ ] **Playlist 下拉菜单**：折叠列表+切换正常工作

---

## 文件结构速查

```
app/src/main/java/com/audiocat/
├── AudioCatApp.kt              # 应用入口，DI 容器
├── MainActivity.kt             # 主 Activity，底部导航
├── data/
│   ├── SettingsStore.kt        # SharedPreferences 封装
│   ├── db/                     # Room 数据库 (v4)
│   │   ├── Track.kt            # 音轨实体
│   │   ├── Playlist.kt         # 含新增 lane_order 列
│   │   ├── PlaylistDao.kt      # 含新增 setLaneOrder()
│   │   └── AudioCatDatabase.kt # version = 4
├── playback/
│   ├── PlaybackService.kt      # 核心：shuffle/move/remove 零中断修复
│   ├── PlaybackQueue.kt        # QueueEngine, setShuffle/setRepeat/next/previous
│   ├── PlayerConnection.kt     # UI↔Service 桥接（shuffle 状态修复）
│   └── PlaybackStateStore.kt   # 持久化播放状态
├── scanner/
│   ├── LibraryScanner.kt       # 修复 enrichBacklog 双重节流 + volumeOf
│   ├── ScanCoordinator.kt      # 调度扫描任务
│   ├── ArtworkStore.kt         # 封面缓存
│   ├── CoverFinder.kt          # 同级图片查找
│   └── TrackMetadataReader.kt  # 元数据/内嵌封面提取
├── ui/
│   ├── settings/SettingsFragment.kt  # 设置页（含 addRow 修复）
│   ├── playing/
│   │   ├── PlaylistFragment.kt       # 泳道拖拽 + 下拉菜单
│   │   ├── PlaylistLaneAdapter.kt    # 拖拽支持
│   │   └── ...
│   └── files/                  # FilesFragment（文件浏览）
└── effects/                    # EQ/Effects
    └── EffectsEngine.kt

res/drawable/
├── ic_drag_handle.xml          # 新增：6-dot 拖拽手柄
├── ic_drag.xml                 # 保留：向后兼容 queue adapter
└── ...

res/layout/
├── item_playlist_lane.xml      # 新增 laneDragHandle
├── fragment_playlist.xml       # 新增 btnPlaylistPicker
└── ...
```

## 常用命令

```bash
# 构建 debug APK
./gradlew assembleDebug

# 安装到已连接设备
adb -s C7Y6R19828004113 install -r app/build/outputs/apk/debug/app-debug.apk

# 查看 logcat
adb -s C7Y6R19828004113 logcat -d | grep -iE "audiocat|FATAL|AndroidRuntime"

# 启动 app
adb -s C7Y6R19828004113 shell am start -n com.audiocat.debug/com.audiocat.MainActivity
```

## 版本管理约定

- 主分支：`main`，与 `origin/main` 同步
- 版本号标记：commit message 格式为 `Audio Cat X.Y.Z — <简短描述>`
