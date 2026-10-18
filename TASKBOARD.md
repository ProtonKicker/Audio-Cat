# Audio Cat — 任务板与项目状态

> 最后更新：2026-10-17  
> 当前版本：1.1.1（commit `c7f024d`）  
> 包名：`com.audiocat.debug`（applicationIdSuffix `.debug`）

---

## 项目简介

Audio Cat 是一款轻量级、完全离线的 Android 音频播放器，专为从本地视频文件中提取音频而设计。不解码视频流，只解码音频，节省 CPU/GPU/电量。支持 MP3/FLAC/AAC/OGG 等音频格式和 MP4/MKV/AVI/MOV/WebM 等视频容器（仅提取音频）。

- 4 个底部导航 Tab：正在播放 / 文件 / 音效 / 设置
- ExoPlayer (Media3 1.11.1) 音频播放
- Room 数据库 + SharedPreferences
- 12 段参数 EQ、混响、低音增强、音高/速度控制

---

## 设备信息

| 设备 | ADB Serial | Android | 状态 | 备注 |
|---|---|---|---|---|
| Redmi Turbo 4 Pro | `C7Y6R19828004113` | 新机型（待确认） | ✅ 已连接 | 主要测试机；输入法窗口可能遮挡 UI（需先收起输入法再 uiautomator dump） |
| Honor 9X | 旧机型 | Android 9 (API 28) | ❌ 未连接 | 之前的测试机 |

---

## 已完成的修复 ✅

### Bug 1 — 切歌自动播放不一致
**现象**：切换歌曲时有时自动播放，有时不会。  
**根因**：
- 播放列表中有已删除文件的残留行（tone440/tone660），切到这些轨道时 ExoPlayer 报错后停止
- 自动扫描开关已修复（见 Bug 2），长期可自动清理残留行
- `PlayerConnection.next()/previous()` 的 fallback 路径使用 `seekToNextMediaItem()` 不触发 `playWhenReady`

**已实施的修复（已在当前代码中）**：
- `PlaybackService.onPlayerError`：删除无法读取的文件对应的 DB 行 + 从队列移除该轨 + 跳到下一首继续播放（`skipBrokenItem`/`forgetTrack`）
- `PlayerConnection.next()/previous()`：fallback 路径在 `seekToNextMediaItem()`/`seekToPreviousMediaItem()` 后显式调用 `play()`

**涉及文件**：
- `app/src/main/java/com/audiocat/playback/PlaybackService.kt`（`onPlayerError`、`skipBrokenItem`、`forgetTrack`）
- `app/src/main/java/com/audiocat/playback/PlayerConnection.kt`（`next()`、`previous()`）

---

### Bug 2 — 设置页所有开关无法点击
**现象**：设置页所有 toggle（自动扫描、默认随机、耳机恢复、断开暂停、音效开关等）都无法通过点击切换。  
**根因**：
- `item_settings_row.xml` 中 `rowSwitch` 设置了 `android:clickable="false"` 和 `android:focusable="false"`
- `SettingsFragment.addRow()` 只在有 `onClick` 时才设置 `rowRoot` 的点击监听；纯 switch 行没有 onClick → 整个行没有点击处理 → 开关完全失效
- 这导致用户无法开启自动扫描，间接导致 Bug 1 中的残留文件行无法被自动清理（因果关系）

**已实施的修复**：
- 移除 `item_settings_row.xml` 中 `rowSwitch` 的 `clickable="false"` 和 `focusable="false"`
- `SettingsFragment.addRow()` 改为：当 `switch=true` 时，`rowRoot` 点击 → `row.rowSwitch.toggle()`

**涉及文件**：
- `app/src/main/res/layout/item_settings_row.xml`
- `app/src/main/java/com/audiocat/ui/settings/SettingsFragment.kt`（`addRow` 方法）

---

## 待办 / 未完成 🔴

### Bug 3 — 切换随机播放时音频短暂中断
**现象**：点击shuffle按钮后，正在播放的音频会有可闻的短暂停顿。  
**根因**：`PlaybackService.setShuffle()` 调用 `pushQueueToPlayer()` → `player.setMediaItems()` + `prepare()` → 解码器被拆除重建 → 约 100-500ms 的音频空隙。

**修复方案讨论（尚未确定最终方案）**：

| 方案 | 优点 | 缺点 |
|---|---|---|
| A. `player.shuffleModeEnabled = true` | 零中断 | ExoPlayer 内部 shuffle 顺序不等于 app 的 QueueEngine 排列，queue sheet 显示会与实际不符 |
| B. `moveMediaItemFromToIndex(from, to, allowPlayingItemMove=true)` 逐个移动 | 不重新 prepare，无中断 | 实现稍复杂；API 行为需验证 |
| C. 重构为 queue cursor ↔ player index 映射，shuffle 只改模型不改 player 列表 | 零中断 + shuffle 即时；需改动约 6 处索引映射（applyCursor/jumpToQueuePosition/removeFromQueue/moveInQueue/skipBrokenItem/onPositionDiscontinuity）+ MediaSession next/prev 覆写 | 改动较大，需充分测试 |
| D. 最小修复：不移动当前播放项，只 shuffle 其他项 | 当前播放不中断 | 实现仍较复杂 |

**建议下一步**：优先验证方案 B 的 `moveMediaItemFromToIndex` API 行为（`allowPlayingItemMove=true` 时是否不重新 prepare）；若不行则回退方案 C。  
**涉及文件**：`app/src/main/java/com/audiocat/playback/PlaybackService.kt`、`PlaybackQueue.kt`

---

### Bug 4 — 封面艺术图未充分加载
**现象**：部分有内置封面的歌曲在播放器中不显示封面。  
**根因**：
- 用户大部分 MP3 文件确实没有经内嵌 APIC/covr 标签
- 扫描的 `DEFAULT_ART_BUDGET = 300` 对 15 首曲库足够，但对大曲库不充分
- 经检查路径查找逻辑已实现（`CoverFinder.kt` + `ArtworkStore.sidecarFor()`），**但需确认新 Redmi 上文件系统的读取权限**

**已实施的部分**：
- `CoverFinder`：查找同目录下的 `cover.jpg` / `folder.jpg` / `front.jpg` 等及同名图片（`movie.mp4` → `movie.jpg`）
- `ArtworkStore.sidecarFor()` 优先级：同名图片 > 文件夹封面名 > 视频首帧

**待办**：
- [ ] 在新 Redmi Turbo 4 Pro 上手动触发"完全扫描"（force=true），确认封面是否正确加载
- [ ] 确认 Scoped Storage 下 app 是否可以通过 `File(path).canRead()` 读取同级目录的图片文件
- [ ] 考虑：force=true 扫描时使用无上限的 artBudget（当前 `DEFAULT_ART_BUDGET = 300`，对大曲库不够）

**涉及文件**：`app/src/main/java/com/audiocat/scanner/CoverFinder.kt`、`ArtworkStore.kt`、`LibraryScanner.kt`

---

### Bug 5 — 在新 Redmi Turbo 4 Pro 上的待验证项
**需要连接设备后手动测试**：

- [ ] **开关切换**：验证 Bug 2 修复后所有设置开关是否正常工作
- [ ] **切歌自动播放**：验证 Bug 1 修复后切歌是否始终自动播放
- [ ] **切歌 fallback 路径**：当 service 未绑定时，通过 MediaController 的下一首是否也能触发播放
- [ ] **封面加载**：扫描后检查封面是否正常显示
- [ ] **音效/均衡器**：EQ 预设切换、低音增强、混响是否正常工作
- [ ] **蓝牙耳机**：连接/断开时自动暂停/恢复（耳机相关设置开关已修复）
- [ ] **通知栏控制**：播放/暂停/下一首/上一首
- [ ] **文件夹排除**：设置中排除文件夹是否生效

---

### 代码质量 / 工程化

- [ ] 确认 `verify.sh`（detekt 静态分析）通过
- [ ] 确认 APK 构建成功 (`./gradlew assembleDebug`)
- [ ] 确认在 Redmi 上安装并启动无 crash

---

## 文件结构速查

```
app/src/main/java/com/audiocat/
├── AudioCatApp.kt              # 应用入口，DI 容器
├── MainActivity.kt             # 主 Activity，底部导航
├── data/
│   ├── SettingsStore.kt        # SharedPreferences 封装
│   ├── db/                     # Room 数据库
│   │   ├── Track.kt            # 音轨实体（含 artPath 列）
│   │   ├── TrackDao.kt         # 含 deleteByPaths / setArtPath / pathsPendingEnrichment
│   │   └── ...
├── playback/
│   ├── PlaybackService.kt      # 核心：ExoPlayer 封装、错误修复
│   ├── PlaybackQueue.kt        # QueueEngine, setShuffle/setRepeat/next/previous, removeAt
│   ├── PlayerConnection.kt     # UI↔Service 桥接
│   └── PlaybackStateStore.kt   # 持久化播放状态
├── scanner/
│   ├── LibraryScanner.kt       # 媒体库扫描（策略：新增→封面富化→清理）
│   ├── ScanCoordinator.kt      # 调度扫描任务（自动/手动）
│   ├── ArtworkStore.kt         # 封面缓存（embedded → sidecar → video frame）
│   ├── CoverFinder.kt          # 同级图片查找
│   └── TrackMetadataReader.kt  # 元数据/内嵌封面提取
├── ui/
│   ├── settings/SettingsFragment.kt  # 设置页（含 addRow 修复）
│   ├── playing/                # PlayerFragment/NowPlayingSheet/QueueSheet/Playlist*
│   ├── files/                  # FilesFragment（文件浏览）
│   ├── effects/                # EQ/Effects
│   └── common/                 # 共用 UI 组件
└── files/                      # 文件操作（删除/重命名/批量）
```

## 常用命令

```bash
# 构建 debug APK
./gradlew assembleDebug

# 静态分析
./scripts/verify.sh

# 安装到已连接设备
adb -s C7Y6R19828004113 install -r app/build/outputs/apk/debug/app-debug.apk

# 查看当前连接的设备
adb devices

# 抓取 UI 层级（在 Redmi 上需先关闭输入法窗口）
adb -s C7Y6R19828004113 shell uiautomator dump /sdcard/win.xml
adb -s C7Y6R19828004113 pull /sdcard/win.xml /tmp/win.xml

# 过滤 logcat
adb -s C7Y6R19828004113 logcat -d | grep -iE "audiocat|FATAL|AndroidRuntime"
```

## 版本管理约定

- 主分支：`main`，与 `origin/main` 同步
- 版本号标记：commit message 格式为 `Audio Cat X.Y.Z — <简短描述>`
- 发布前打 tag：`git tag vX.Y.Z`
