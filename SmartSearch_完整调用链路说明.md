# SmartSearch 完整业务调用链路

> 从点击按钮到展示答案的全流程说明

---

## 一、完整调用链路图

```
┌──────────────────────────────────────────────────────────────────┐
│  HomeActivity (首页)                                              │
│  ┌──────────────────────────────────────────────────────────────┐│
│  │  [无障碍搜题] 按钮                                            ││
│  │     ↓                                                        ││
│  │  startAccessibilitySearch()                                   ││
│  │     ├─ ① checkFloatingWindow() → 未授权 → 跳转设置页          ││
│  │     ├─ ② isServiceEnabled()    → 未开启 → 跳转无障碍设置      ││
│  │     └─ ③ startFloatingBallService()                          ││
│  └──────────────────────────────────────────────────────────────┘│
└──────────────────────────┬───────────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────────┐
│  FloatingWindowService (悬浮球前台服务)                           │
│  ┌──────────────────────────────────────────────────────────────┐│
│  │  attachFloatingBall() → 绿色悬浮球显示在屏幕右侧               ││
│  │                                                               ││
│  │  用户点击悬浮球 → triggerSelectionSearch()                    ││
│  │     ├─ ④ checkFloatingWindow() → 二次确认                    ││
│  │     └─ ⑤ FloatWindowManager.showSelectOverlay { rect -> }    ││
│  └──────────────────────────────────────────────────────────────┘│
└──────────────────────────┬───────────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────────┐
│  FloatSelectOverlay (选题框悬浮窗)                                │
│  ┌──────────────────────────────────────────────────────────────┐│
│  │  Canvas 全屏绘制：半透明遮罩 + 透明选区 + X按钮 + 缩放控制点  ││
│  │                                                               ││
│  │  用户拖拽确认选区 → onRectConfirmed(rect)                     ││
│  └──────────────────────────────────────────────────────────────┘│
└──────────────────────────┬───────────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────────┐
│  AccessibilitySearchService (无障碍搜题服务)                      │
│  ┌──────────────────────────────────────────────────────────────┐│
│  │  setSelectionRect(rect)                                       ││
│  │     ↓                                                        ││
│  │  performScan()                                                ││
│  │     ├─ ⑥ collectTextNodes() → 递归遍历 AccessibilityNodeInfo ││
│  │     ├─ ⑦ RectUtil.overlapRatio() ≥ 60% 过滤                  ││
│  │     ├─ ⑧ buildQuestionText() → 拼接题干                      ││
│  │     │                                                         ││
│  │     ├─ 题干为空 → showEmptyResultDialog()                    ││
│  │     │     └─ 提示切换录屏模式                                  ││
│  │     │                                                         ││
│  │     └─ 题干有效 → QuestionBankSearcher.search()              ││
│  └──────────────────────────────────────────────────────────────┘│
└──────────────────────────┬───────────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────────┐
│  QuestionBankSearcher (题库检索)                                  │
│  ┌──────────────────────────────────────────────────────────────┐│
│  │  search(context, question)                                    ││
│  │     ├─ ⑨ 精确匹配: dao.findByExactQuestion()                 ││
│  │     ├─ ⑩ 模糊匹配: dao.searchByKeyword("%keyword%")          ││
│  │     └─ ⑪ 兜底: "未找到匹配的题目"                             ││
│  └──────────────────────────────────────────────────────────────┘│
└──────────────────────────┬───────────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────────┐
│  AnswerFloatWindow (答案弹窗)                                     │
│  ┌──────────────────────────────────────────────────────────────┐│
│  │  FloatWindowManager.showAnswerWindow(answer, explanation)     ││
│  │                                                               ││
│  │  Canvas 绘制：绿色边框 + 白色内容 + 返回箭头 + 缩放控制点     ││
│  │                                                               ││
│  │  点击返回箭头 → 重新打开 FloatSelectOverlay                    ││
│  └──────────────────────────────────────────────────────────────┘│
└──────────────────────────────────────────────────────────────────┘
```

---

## 二、各步骤详解

| 步骤 | 所在文件 | 方法 | 说明 |
|------|---------|------|------|
| ① | `HomeActivity.kt` | `startAccessibilitySearch()` | 校验悬浮窗权限，未授权跳转 `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` |
| ② | `HomeActivity.kt` | `startAccessibilitySearch()` | 校验无障碍服务是否已开启，未开启跳转 `Settings.ACTION_ACCESSIBILITY_SETTINGS` |
| ③ | `HomeActivity.kt` | `startFloatingBallService()` | `startForegroundService(intent)` 启动悬浮球，系统通知栏显示"智能搜题运行中" |
| ④ | `FloatingWindowService.kt` | `triggerSelectionSearch()` | 二次确认悬浮窗权限，未授权跳转设置页 |
| ⑤ | `FloatingWindowService.kt` | `triggerSelectionSearch()` | 调用 `FloatWindowManager.showSelectOverlay()`，注册选区回调→ 传递给 `AccessibilitySearchServiceHolder.instance.setSelectionRect(rect)` |
| ⑥ | `AccessibilitySearchService.kt` | `collectTextNodes()` | 递归遍历 `rootInActiveWindow` 的所有子节点 |
| ⑦ | `AccessibilitySearchService.kt` | `collectTextNodes()` | 调用 `RectUtil.overlapRatio(nodeRect, selectionRect)`，保留 ≥ 60% 节点 |
| ⑧ | `AccessibilitySearchService.kt` | `buildQuestionText()` | 过滤状态栏文字、空白、杂讯，按 Y 坐标排序拼接 |
| ⑨ | `QuestionBankSearcher.kt` | `search()` | `QuizDatabase.questionDao().findByExactQuestion()` 精确匹配 |
| ⑩ | `QuestionBankSearcher.kt` | `search()` | `QuizDatabase.questionDao().searchByKeyword()` LIKE 模糊匹配 |
| ⑪ | `QuestionBankSearcher.kt` | `search()` | 返回兜底提示文本 |

---

## 三、录屏模式切换链路

```
无障碍识别空白
  → showEmptyResultDialog()
  → 用户在 Activity 中调用 AccessibilitySearchService.switchToScreenCapture()
  → startActivityForResult(projectionManager.createScreenCaptureIntent())
  → onActivityResult OK
  → ScreenCaptureService.startWithProjection(context, data, rect)
  → FloatWindowManager.showSelectOverlayForScreenCapture()
  → 用户框选 → ScreenCaptureService.updateSelectionRect() + triggerCaptureOnce()
  → PaddleOCREngine.recognize() → QuestionBankSearcher.search()
  → FloatWindowManager.showAnswerWindow()
```

**自动回退：**

| 触发条件 | 回退行为 |
|---------|---------|
| `MediaProjection.Callback.onStop()` | 录屏权限回收 → `switchToAccessibilityMode()` |
| 连续 3 帧黑帧 | FLAG_SECURE 页面 → 弹窗提示 → 切回无障碍模式 |
| `mediaProjection == null` | 启动失败 → 切回无障碍模式 |

---

## 四、数据流全景

```
┌──────────────┐     ┌────────────────────┐     ┌──────────────────┐
│  ExcelImporter │ ──▶ │  QuizDatabase (Room) │ ◀── │  QuestionBankSearcher │
│  (Excel导入)   │     │  ┌───────────────┐ │     │  (题库检索)          │
└──────────────┘     │  │ QuestionEntity │ │     └──────────┬───────────┘
                     │  │ WrongQuestion  │ │                │
                     │  │ PracticeRecord │ │                │
                     │  └───────────────┘ │                │
                     └────────────────────┘                │
                                                           │
┌──────────────────────────┐                              │
│  AccessibilitySearchService │ ── 题干文本 ──────────────┘
│  (无障碍文本提取)            │
└──────────────────────────┘
                                                           │
┌──────────────────────────┐                              │
│  ScreenCaptureService     │ ── OCR 文本 ───────────────┘
│  (录屏+OCR识别)            │
└──────────────────────────┘
```

---

## 五、新增/更新文件清单

| 文件 | 路径 | 类型 |
|------|------|------|
| `QuestionEntity.kt` | `data/local/entity/` | 新增 |
| `WrongQuestionEntity.kt` | `data/local/entity/` | 新增 |
| `PracticeRecordEntity.kt` | `data/local/entity/` | 新增 |
| `QuestionDao.kt` | `data/local/dao/` | 新增 |
| `WrongQuestionDao.kt` | `data/local/dao/` | 新增 |
| `PracticeRecordDao.kt` | `data/local/dao/` | 新增 |
| `QuizDatabase.kt` | `data/local/` | 新增 |
| `ExcelImporter.kt` | `data/parser/` | 新增 |
| `HomeScreen.kt` | `ui/` | 新增 |
| `AndroidManifest.xml` | `app/src/main/` | 新增 |
| `accessibility_service_config.xml` | `res/xml/` | 新增 |
| `file_paths.xml` | `res/xml/` | 新增 |
| `QuestionBankSearcher.kt` | `feature/search/capture/` | 更新（接入 Room） |

---

## 六、关键依赖

| 依赖 | 用途 |
|------|------|
| `androidx.room:room-runtime:2.6.1` | Room 数据库 |
| `androidx.room:room-ktx:2.6.1` | Room 协程扩展 |
| `org.apache.poi:poi:5.2.5` | Excel .xls 解析 |
| `org.apache.poi:poi-ooxml:5.2.5` | Excel .xlsx 解析 |
| `androidx.compose.material3:material3` | 首页 UI |
| `androidx.activity:activity-compose` | Compose Activity 集成 |