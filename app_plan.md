# Audio Cat - App Plan

## Overview

Audio Cat is a lightweight, fully offline Android audio player built exclusively for playing audio extracted from local video files. Instead of playing videos and hiding the video surface (which still decodes video), Audio Cat only decodes audio streams from video files, saving CPU, GPU, RAM, and battery life.

## Core Goals

- Zero video processing - only decode audio streams, never render video
- Lightweight - < 50MB RAM usage, minimal battery drain even for 4K/8K videos
- Fully offline - no internet, ads, trials, accounts, or in-app purchases
- Audio-only by design - never accidentally play video
- Simple UI with bottom navigation, minimal bloat

## Target Formats

- **Audio:** MP3, FLAC, AAC, WAV, OGG, ALAC (and all other common audio formats)
- **Video:** MP4, MKV, AVI, MOV, WebM (and all other common containers) - only audio is extracted, no video rendering
- No transcoding required; native decoding only

## Architecture Overview

- **Audio extraction:** On scan, extract/resolve the audio track from video files. Do not create duplicate audio files unless user explicitly exports (optional). Prefer referencing original files by path with metadata cached locally.
- **Local library DB:** SQLite (Room/SQLDelight) to store track metadata (title, path, duration, video-derived thumbnail, hidden/unlisted flag, playlist membership, etc.)
- **Scanning:** Incremental filesystem scan with support for custom folders, excluded folders, and both auto-scan and manual scan modes.
- **Playback:** ExoPlayer (or Media3) configured for audio-only streams; background service with lockscreen/notification controls + Bluetooth/headset button support.
- **Thumbnails:** Extract first video frame (keyframe preferred) as track art on scan; cache small-sized thumbnails locally.
- **Effects:** 12-band parametric EQ, bass boost, reverb, pitch/speed control; save/load custom presets.

## Bottom Navigation (4 Tabs)

### 1. Playing Tab
- **Now Playing:** Large album art (from video first frame), track title, artist, seekbar, play/pause/prev/next, shuffle/repeat (track/album/queue)
- **Library view:** All visible tracks (excludes unlisted), sorted by default (date added/title/artist)
- **Queue management:** View/reorder/remove from queue, clear queue
- **Custom Playlists:**
  - Create unlimited custom playlists
  - Add/remove/reorder tracks within playlists
  - Quick access to saved playlists
- **Quick actions:** Long-press track to add to playlist, unlist, view file info
- **Unlist:** Hide tracks from library/rotation without deleting files
- **Background play:** Continues with lockscreen + notification controls
- **Lyrics sync:** Display synced lyrics if available in metadata/tags (or embedded LRC if present)

### 2. Sound Effects Tab
- **Equalizer:** 12-band parametric EQ with adjustable gain/frequency/Q
- **Effects:** Bass boost, reverb (room/reverb presets), pitch control, playback speed control
- **Presets:** Save custom presets (per-user/global), quick switch between presets
- **Live preview:** Apply changes in real-time while playing

### 3. File Management Tab
- **Scanned files:** List all scanned audio/video files with metadata (path, size, duration, unlisted status)
- **Unlist toggle:** Toggle unlisted status per track (or bulk)
- **Bulk operations:** Multi-select to unlist/relist, delete from library view (does not delete files from disk unless explicitly confirmed)
- **Custom scan folders:** Add/remove folders to scan list
- **File renaming:** Rename individual audio/video files directly in-app; library updates automatically (path/metadata stays consistent)
- **Batch renaming:** Support batch rename with patterns (e.g. prefix/suffix, sequential numbering, replace text) - updates file paths and library references safely
- **File info:** Show path, size, duration, container/codec info
- **Storage overview:** Show total scanned size/track count

### 4. Settings Tab (New)
- **Scan mode:**
  - Toggle Auto-scan on/off (if off, manual scan trigger required)
  - Trigger Manual scan (full or partial refresh)
  - Add/remove default scan folders
  - **Exclude scan folders:** Add folders to exclude from scanning (takes precedence over included folders)
- **Library:**
  - Adjust track art quality (thumbnail size/resolution) to balance storage vs quality
  - Configure behavior for newly found files
- **Playback:**
  - Configure background play behavior
  - Default playback options (shuffle/repeat defaults)
  - Crossfade (optional if feasible)
- **Appearance:** Light/dark theme toggle (system default optional)
- **Advanced:** Show app version, clear thumbnail cache, reset settings
- **Privacy:** Explicit note - fully offline, no data collected

## Technical Requirements

### Minimum Requirements
- Android (target modern API; focus on stability/perf)
- No internet permissions
- Storage access (READ_MEDIA_* / legacy storage as needed)
- Foreground service permission for background playback
- Wake lock for background playback
- Runtime media permissions (Android 13+)

### Performance Targets
- < 50MB RAM during playback
- Near-zero video decode overhead (audio-only path)
- Fast incremental scans (avoid full rescan of unchanged files)
- Minimal battery impact
- Small APK size (< 15MB target)

### Data Storage
- Local DB for library/playlists/presets/settings
- Local thumbnail cache (small compressed images)
- No cloud sync, no remote config, no analytics
- All data stays on device

## Implementation Notes

- **No video rendering:** Never create a SurfaceView/TextureView for video. Only use audio track extraction/decoding. If using ExoPlayer with a video container, ensure video renderer is not attached or audio-only track is selected.
- **Safe file operations:** Renaming/moving files must update DB paths and handle permission edge cases (Scoped Storage). Validate before renaming, handle conflicts.
- **Incremental scan:** Watch for filesystem changes where possible; track last modified time + file size to skip unchanged files.
- **Unlisted tracks:** Filtered out of "All Songs"/library rotation by default; always accessible via File Management or playlists they belong to.
- **No duplicate storage:** Reference original file paths; only cache derived thumbnails and DB records.
- **Format coverage:** Use platform decoders + FFmpeg/MediaCodec as appropriate. Prefer MediaExtractor to select audio track without initializing video codecs.

## Minimal Viable Scope (MVP)

1. Core audio playback from video files (audio-only extraction)
2. Auto-scan + manual scan with included/excluded folders
3. Playing tab with queue, shuffle/repeat, background play, lockscreen controls
4. Track art from video first frame
5. Unlist tracks (hide from library)
6. Custom playlists (create/add/remove/reorder)
7. File Management with rename (single + batch), bulk ops, folder management
8. Settings tab with auto/manual scan toggle + exclude folders
9. Sound Effects tab with 12-band EQ + effects + presets
10. Fully offline, no ads/trials

## Future Enhancements (Optional)
- Sleep timer
- Playback speed control UI polish (already planned in effects context)
- Export audio as standalone files (user opt-in)
- Lyrics import/sync UI
- Home screen widget
- More theme options

## Success Criteria
- Playing any video file's audio uses no measurable video decode/render overhead
- < 50MB RAM in normal playback
- All listed formats play reliably
- File rename/batch rename updates library correctly without breaking references
- Auto-scan respects excluded folders; manual scan works when auto-scan disabled
- Unlisted tracks never appear in main library rotation
- No ads, no network calls, fully private
- Smooth UX on mid/low-end devices
