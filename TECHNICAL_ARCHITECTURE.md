# ShortVideoGuard 技术架构与实现细节（最新版）

> 适用工程：`Aiscanner`  
> 包名：`com.example.ai_scanner`  
> 目标：完整说明当前可运行版本的系统架构、关键链路、核心状态机、权限与合规约束，便于开发维护、答辩展示与后续扩展。

---

## 1. 项目目标与边界

### 1.1 项目目标
`ShortVideoGuard` 是一个基于 Android 原生能力实现的“短视频风险检测演示系统”，主流程为：

1. 获取悬浮窗权限并启动全局悬浮层；
2. 用户在任意 App（如短视频/聊天 App）内点击悬浮球；
3. 在白色操作面板点击“检测”，触发 5 秒录屏；
4. 录屏文件保存到系统媒体库并上传后端；
5. 屏幕顶部展示风险等级横幅（3 秒自动消失）；
6. 用户可点击横幅右侧 `...` 打开详细结果弹窗。

### 1.2 明确不包含
- 不使用无障碍服务；
- 不包含端侧 AI 推理；
- 不包含多路采集、实时帧流处理；
- 网络层目前为单请求模型（`multipart/form-data` 上传后同步等待响应）。

---

## 2. 技术栈与总体分层

### 2.1 技术栈
- 语言：Java
- UI：XML + Android View 系统
- 全局悬浮：`WindowManager` + `TYPE_APPLICATION_OVERLAY`
- 录屏：`MediaProjection` + `VirtualDisplay` + `MediaRecorder`
- 存储：`MediaStore`（`Movies/ShortVideoGuard`）
- 网络：`HttpURLConnection`（multipart 上传）
- 并发：主线程 `Handler` + 单线程 `ExecutorService`
- JSON：`org.json.JSONObject`

### 2.2 分层架构

1. **入口与权限引导层**：`MainActivity`
2. **录屏授权桥接层**：`ScreenCapturePermissionActivity`
3. **核心业务与状态调度层**：`FloatingOverlayService`
4. **资源表现层**：`res/layout`、`res/drawable`、`res/values`

架构核心思想：**UI 与长生命周期状态全部收敛到 Service**，Activity 仅承担“授权/引导”职责。

---

## 3. 关键组件职责

## 3.1 `MainActivity`
路径：`app/src/main/java/com/example/ai_scanner/MainActivity.java`

核心职责：
- 检查悬浮窗权限（`Settings.canDrawOverlays`）；
- 动态更新权限按钮状态：
  - 未授权：`开启悬浮窗权限`（品牌色背景）；
  - 已授权：`已获取悬浮窗权限`（安全绿背景）；
- 权限就绪后延时拉起 `FloatingOverlayService`。

关键实现点：
- `overlayPermissionLauncher` 监听权限页返回；
- `SERVICE_START_DELAY_MS` 与 `PERMISSION_RECHECK_DELAY_MS` 用于规避系统权限状态短抖动；
- `overlayServiceRequested` 防止重复启动服务。

## 3.2 `ScreenCapturePermissionActivity`
路径：`app/src/main/java/com/example/ai_scanner/ScreenCapturePermissionActivity.java`

核心职责：
- 调起系统录屏授权页（`createScreenCaptureIntent`）；
- 将授权结果转发给 `FloatingOverlayService`：
  - `ACTION_START_CAPTURE`
  - `ACTION_CAPTURE_DENIED`
- 快速 `finishAndRemoveTask()`，降低界面打扰。

## 3.3 `FloatingOverlayService`
路径：`app/src/main/java/com/example/ai_scanner/FloatingOverlayService.java`

核心职责（项目中枢）：
- 维护所有悬浮 UI（悬浮球/白色面板/顶部横幅/中风险条/高风险弹层/详情弹层）；
- 管理录屏会话状态（Projection、VirtualDisplay、Recorder）；
- 5 秒检测倒计时、自动结束与资源回收；
- 上传录屏文件并解析后端 JSON；
- 按风险等级执行 UI 分发与展示策略。

---

## 4. Manifest 与权限模型

路径：`app/src/main/AndroidManifest.xml`

已声明权限：
- `android.permission.SYSTEM_ALERT_WINDOW`
- `android.permission.INTERNET`
- `android.permission.FOREGROUND_SERVICE`
- `android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION`

组件声明：
- `MainActivity`（Launcher）
- `ScreenCapturePermissionActivity`（`excludeFromRecents/noHistory/singleTask`）
- `FloatingOverlayService`（`foregroundServiceType="mediaProjection"`）

说明：
- 当前 `usesCleartextTraffic="true"`，允许 HTTP；正式生产建议迁移 HTTPS。

---

## 5. UI 组件与窗口层级

## 5.1 悬浮球
- 布局：`view_floating_ball.xml`
- 特性：可拖拽、贴边停靠、停靠后透明度降低。

## 5.2 白色控制面板
- 布局：`view_control_panel.xml`
- 按钮：`检测`、`设置`
- 行为：点击 `检测` 后隐藏面板并进入检测流程。

## 5.3 顶部状态横幅（核心）
- 布局：`view_top_status_banner.xml`
- 检测中状态：显示 `风险检测中` + 倒计时
- 结果状态：仅显示 `风险等级：xxx`，右侧显示 `...` 按钮
- 自动隐藏：结果横幅 3 秒自动消失（`RISK_BANNER_AUTO_HIDE_MS = 3000L`）

风险颜色映射：
- 低危/安全：`bg_top_banner_safe.xml`
- 中危：`bg_top_banner_mid.xml`
- 高危：`bg_top_banner_high.xml`

## 5.4 风险扩展视图
- `...` 点击后展示结果详情弹层：`view_result_dialog.xml`
- 弹层展示：风险评分、AI 伪造概率、暴力概率、触发规则、文本片段、推理耗时等。

## 5.5 兼容保留视图
- 中风险可拖拽条：`view_mid_risk_bar.xml`
- 高风险模态框：`view_high_risk_dialog.xml`

> 当前主链路以“顶部横幅 + 详情弹窗”为准，中/高风险视图作为兼容能力保留。

---

## 6. 核心状态机与流程

## 6.1 检测状态机（逻辑）

```text
Idle
  -> 点击悬浮球 -> ControlPanelVisible
ControlPanelVisible
  -> 点击检测 -> PermissionCheck
PermissionCheck
  -> 已有可用Projection -> Recording
  -> 无可用Projection -> RequestProjectionPermission
RequestProjectionPermission
  -> 授权成功 -> Recording
  -> 授权失败 -> Idle
Recording (5s)
  -> 自动结束 -> Uploading
Uploading
  -> 成功 -> RiskBannerVisible(3s)
  -> 失败 -> Idle + Toast
RiskBannerVisible
  -> 点击... -> ResultDialogVisible
  -> 3s超时 -> Idle(横幅消失)
ResultDialogVisible
  -> 再次检测 -> Recording/PermissionCheck
  -> 关闭 -> Idle
```

## 6.2 关键时序（一次完整检测）

```text
用户点击检测
-> FloatingOverlayService.startDetectionFromCurrentPermissionState()
-> startScreenRecording()
-> switchToCheckingState()（顶部横幅倒计时 + 球旋转）
-> 5s后 finishCheck()
-> stopScreenRecording(true)（保存视频并触发上传）
-> uploadRecordingToAnalyze(videoUri)
-> parseAnalyzeResponse(json)
-> showRiskLevelBanner(result)（3s后自动隐藏）
-> 用户点击 ... 可查看 showResultDialog(result)
```

---

## 7. 录屏子系统设计

## 7.1 会话对象
- `MediaProjectionManager mediaProjectionManager`
- `MediaProjection mediaProjection`
- `VirtualDisplay virtualDisplay`
- `ImageReader idleImageReader`
- `MediaRecorder mediaRecorder`
- `ParcelFileDescriptor recordingPfd`
- `Uri recordingUri`

## 7.2 权限与缓存策略
- 首次授权成功后缓存：`cachedCaptureResultCode` + `cachedCaptureResultData`；
- 可用时尝试复用会话，减少重复弹权限；
- 令牌失效时清理缓存并重新授权。

## 7.3 录制参数
- 时长：5 秒（定时自动停止）
- 编码：H264
- 容器：MP4
- 帧率：30fps
- 分辨率：当前屏幕分辨率（`DisplayMetrics`）

## 7.4 前台服务要求
- 在创建 Projection 前调用 `startRecordForeground()`；
- Android Q+ 使用 `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`；
- 释放 Projection 时关闭前台通知。

## 7.5 文件落盘策略
- 通过 `MediaStore.Video.Media.EXTERNAL_CONTENT_URI` 创建记录；
- Android Q+ 使用 `IS_PENDING=1 -> 0` 提交；
- 默认目录：`Movies/ShortVideoGuard`。

---

## 8. 网络协议与后端交互

## 8.1 请求协议
- Method：`POST`
- Endpoint：`ANALYZE_ENDPOINT`（当前代码默认 `http://localhost:2333/analyze`）
- Content-Type：`multipart/form-data; boundary=...`
- 文件字段：`file`

## 8.2 响应解析字段
`parseAnalyzeResponse()` 解析以下字段：
- `risk_level`
- `risk_score`
- `ai_glitch_prob`
- `violence_prob`
- `transcript_snippet`
- `inference_time_ms`
- `status`
- `trigger`

## 8.3 线程模型
- 上传在线程池执行：`uploadExecutor`（单线程）
- UI 更新统一切回主线程：`mainHandler.post(...)`

## 8.4 失败处理
- 网络失败：`analyze_request_failed` Toast
- JSON 解析失败：`analyze_parse_failed` Toast
- 本地输入流为空：日志报错并跳过上传

---

## 9. 生命周期与资源管理

## 9.1 Service 生命周期
- `onCreate()`：获取系统服务，不立刻 addView
- `onStartCommand()`：权限校验、初始化 Overlay、处理 Action
- `onDestroy()`：移除全部 Runnable，清理窗口，停止录屏，释放 Projection

## 9.2 防抖与防重入
- `isChecking`、`capturePermissionPending` 防止重复启动检测
- `functionPopupAdded/topBannerAdded/...` 防止窗口重复 add/remove
- 权限短暂抖动时延迟复核，不立即销毁现有悬浮层

## 9.3 资源释放关键点
- `stopScreenRecording(keepFile)`：停止 Recorder、恢复 Surface、关闭 FD
- `releaseProjectionSession()`：释放 `VirtualDisplay/ImageReader/Projection`
- `cleanupOverlayViews()`：统一回收所有 overlay 视图

---

## 10. 稳定性与异常场景处理

## 10.1 已处理异常
- `ClassCastException`/`addView` 类错误通过初始化异常兜底并停止服务
- `MediaRecorder.stop()` 非法状态捕获并清理坏文件
- `SecurityException`（Projection token 失效）识别后触发重新授权流程
- `MediaProjection.Callback#onStop` 统一做系统中断回收

## 10.2 兼容点
- Android O+：`TYPE_APPLICATION_OVERLAY`
- 低版本：`TYPE_PHONE`
- Android Q+：必须前台服务类型 `mediaProjection`
- 厂商 ROM：可能存在后台策略与悬浮窗限制差异

## 10.3 已知限制
- 在 Android 高版本上，MediaProjection token 生命周期限制较严格；
- 若系统主动结束 Projection，会触发本轮检测失败并需要再次授权；
- 目前网络层无断点续传与重试策略。

---

## 11. 性能与可观测性建议

## 11.1 建议埋点
- `overlay_permission_grant_rate`
- `projection_permission_grant_rate`
- `record_success_rate`
- `upload_success_rate`
- `detect_end_to_end_latency_ms`
- `banner_show_to_hide_duration_ms`

## 11.2 关键性能风险
- 录屏 + 编码 + 上传并发时，低端设备可能出现帧丢失或 UI 卡顿；
- 主线程频繁 View 更新（倒计时）需避免叠加重任务。

## 11.3 优化方向
- 网络改用 OkHttp + 超时重试策略；
- 结果上报改为结构化日志；
- 录屏引擎可拆分成独立 `RecorderEngine` 组件。

---

## 12. 安全与合规建议

- 明确展示权限用途说明（项目已实现悬浮窗说明）；
- 上传视频涉及隐私，生产版需增加用户同意与范围提示；
- 建议强制 HTTPS + 鉴权签名；
- 可增加上传前脱敏与服务端访问控制策略。

---

## 13. 代码导航索引

Java：
- `app/src/main/java/com/example/ai_scanner/MainActivity.java`
- `app/src/main/java/com/example/ai_scanner/ScreenCapturePermissionActivity.java`
- `app/src/main/java/com/example/ai_scanner/FloatingOverlayService.java`

Manifest：
- `app/src/main/AndroidManifest.xml`

核心布局：
- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/res/layout/view_floating_ball.xml`
- `app/src/main/res/layout/view_control_panel.xml`
- `app/src/main/res/layout/view_top_status_banner.xml`
- `app/src/main/res/layout/view_result_dialog.xml`
- `app/src/main/res/layout/view_mid_risk_bar.xml`
- `app/src/main/res/layout/view_high_risk_dialog.xml`

核心文案与色彩：
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values/colors.xml`

---

## 14. 后续演进路线（建议）

1. **配置化**：将录制时长、端点 URL、超时参数迁移至环境配置；
2. **状态机显式化**：将多个布尔状态重构为单一枚举状态机；
3. **网络可靠性**：失败重试、幂等标识、离线队列；
4. **观测体系**：接入崩溃上报 + 性能埋点 + 业务指标看板；
5. **隐私合规**：补充上传弹窗、协议链接、可撤回授权引导。

---

## 15. 一句话总结

当前版本采用 **`MainActivity` 权限引导 + `ScreenCapturePermissionActivity` 授权桥接 + `FloatingOverlayService` 全流程调度** 的三段式架构，围绕“悬浮交互 + 5 秒录屏 + 后端分析 + 顶部风险横幅反馈”实现了完整、可演示、可扩展的业务闭环。
