# WukongAgent 架构设计文档

> 版本: 1.0 | 日期: 2026-05-17 | 作者: AI Assistant

---

## 1. 需求概述

### 1.1 项目目标
为优必选悟空2代机器人开发一个无界面的后台语音交互服务（wukong_agent），实现语音唤醒 → 录音 → WebSocket 上传 → 接收 TTS → 播放的完整对话流程。

### 1.2 核心流程
```
语音唤醒(AIKit) → PreProcessedRecorder录音 → WebSocket上传(OkHttp)
     → 服务端ASR+LLM+TTS处理 → WebSocket返回TTS音频 → AudioTrack流式播放
```

### 1.3 状态机
```
IDLE → WAKEUP → RECORDING → PROCESSING → PLAYING → IDLE
```
- 每个状态有超时保护：RECORDING 30s, PROCESSING 10s, PLAYING 60s
- PLAYING 期间检测到唤醒词 → 立即切换到 RECORDING（打断机制）
- 录音需有静音检测（VAD），用户说完自动结束录音

### 1.4 技术约束
- 语言: Java 17（机器人SDK是Kotlin，用Java调用）
- 构建: Gradle 8.7+
- 架构: Service + Repository + Manager（非MVVM，无界面）
- 异步: ExecutorService / Handler / LiveData（禁止 Kotlin Coroutines）
- 网络: OkHttp WebSocket
- 唤醒: 讯飞 AIKit SDK
- 录音: 机器人 opensdk PreProcessedRecorder
- 平台: Android 11 user release

---

## 2. 系统架构

### 2.1 分层架构图

```
┌─────────────────────────────────────────────────────┐
│                   Android System                      │
│  BOOT_COMPLETED → BootCompletedReceiver              │
│  JobScheduler → ServiceWatchdog                      │
└────────────────────────┬──────────────────────────────┘
                         │ start / restart
┌────────────────────────▼──────────────────────────────┐
│                  WukongService                         │
│  (ForegroundService + Notification)                   │
│  ┌───────────────────────────────────────────────┐    │
│  │           WukongApplication                    │    │
│  │  ConfigManager / WkSdk.init / AIKit.init      │    │
│  └───────────────────────────────────────────────┘    │
│  ┌───────────────────────────────────────────────┐    │
│  │       BusinessStateMachine                    │    │
│  │  IDLE → WAKEUP → RECORDING → PROCESSING       │    │
│  │         → PLAYING → IDLE                       │    │
│  └──────────────────┬────────────────────────────┘    │
│                     │ state change events              │
│  ┌──────────────────▼────────────────────────────┐    │
│  │     RobotStateCoordinator (Bridge)            │    │
│  │  双向协调: 业务状态 ↔ 机器人系统状态           │    │
│  └──────┬──────────────┬──────────────┬───────────┘    │
│         │              │              │                 │
│  ┌──────▼──────┐ ┌─────▼──────┐ ┌────▼─────────┐      │
│  │WakeUpManager│ │AudioRecMgr │ │WebSocketMgr  │      │
│  │ (AIKit)     │ │(PreProcRec)│ │ (OkHttp)     │      │
│  └─────────────┘ └────────────┘ └──────────────┘      │
│  ┌──────────────┐ ┌────────────┐ ┌──────────────┐     │
│  │  TTSEngine   │ │RobotAction │ │  ConfigMgr   │     │
│  │ (AudioTrack) │ │  Manager   │ │ (SharedPref) │     │
│  └──────────────┘ └────────────┘ └──────────────┘     │
└───────────────────────────────────────────────────────┘
         │                              │
┌────────▼──────────┐    ┌──────────────▼──────────────┐
│   Room Database    │    │    Robot SDK (opensdk)       │
│  ChatHistory       │    │    WkSdk, PreProcessedRec   │
│  WakeUpLog         │    │    ActionApi, ExpressApi     │
│  ActionCache       │    │    MotorApi, LightApi, etc.  │
└────────────────────┘    └─────────────────────────────┘
```

### 2.2 SettingsActivity（独立于服务）
```
SettingsActivity (PreferenceFragmentCompat)
  → SharedPreferences
  → ConfigManager (LiveData 监听)
  → Service 通过 ConfigManager 响应配置变化
```

---

## 3. 模块设计

### 3.1 核心模块清单

| 包路径 | 类名 | 职责 |
|--------|------|------|
| `com.wukong.agent` | WukongApplication | Application初始化、全局单例管理 |
| `service` | WukongService | ForegroundService，生命周期管理，Notification |
| `service` | BootCompletedReceiver | 开机自启广播接收器 |
| `watchdog` | ServiceWatchdog | JobScheduler守护，崩溃重启 |
| `statemachine` | BusinessState | 业务状态枚举 |
| `statemachine` | BusinessStateMachine | 业务状态机，状态流转+超时+打断 |
| `statemachine` | StateChangeListener | 状态变化监听接口 |
| `coordinator` | RobotStateCoordinator | 业务状态与机器人状态桥接 |
| `manager` | WakeUpManager | AIKit唤醒词检测 |
| `manager` | AudioRecorderManager | PreProcessedRecorder录音+VAD |
| `manager` | WebSocketManager | WebSocket连接、心跳、重连 |
| `manager` | TTSEngine | AudioTrack流式TTS播放 |
| `manager` | RobotActionManager | 机器人动作控制（预留接口） |
| `manager` | ConfigManager | 配置管理，SharedPreferences+LiveData |
| `data.entity` | ChatHistoryEntity | 对话历史实体 |
| `data.entity` | WakeUpLogEntity | 唤醒日志实体 |
| `data.entity` | ActionEntity | 动作指令缓存实体 |
| `data.dao` | ChatHistoryDao | 对话历史DAO |
| `data.dao` | WakeUpLogDao | 唤醒日志DAO |
| `data.dao` | ActionDao | 动作缓存DAO |
| `data.db` | WukongDatabase | Room数据库定义 |
| `data.db` | Converters | Room类型转换器 |
| `data.repository` | ChatHistoryRepository | 对话历史仓库 |
| `data.repository` | WakeUpLogRepository | 唤醒日志仓库 |
| `data.repository` | ActionRepository | 动作缓存仓库 |
| `model` | WebSocketMessage | WebSocket消息模型 |
| `model` | RobotConfig | 机器人配置模型 |
| `util` | AudioUtils | 音频处理工具（格式转换等） |
| `util` | JsonUtils | JSON序列化/反序列化 |
| `util` | VADDetector | 静音检测（Voice Activity Detection） |
| `util` | NetworkUtils | 网络状态工具 |
| `settings` | SettingsActivity | 配置界面 |
| `settings` | SettingsFragment | 配置Fragment |

### 3.2 类依赖关系

```
WukongService
  ├── BusinessStateMachine
  │     ├── StateChangeListener (interface)
  │     ├── WakeUpManager
  │     ├── AudioRecorderManager
  │     │     └── VADDetector
  │     ├── WebSocketManager
  │     ├── TTSEngine
  │     └── RobotActionManager
  ├── RobotStateCoordinator
  │     ├── BusinessStateMachine (observer)
  │     └── Robot SDK APIs (SysEventApi, etc.)
  ├── ConfigManager
  │     └── SharedPreferences + LiveData
  ├── WukongDatabase
  │     ├── ChatHistoryDao → ChatHistoryRepository
  │     ├── WakeUpLogDao → WakeUpLogRepository
  │     └── ActionDao → ActionRepository
  └── Notification (ForegroundService)
```

---

## 4. 状态机详细设计

### 4.1 BusinessStateMachine

```java
public enum BusinessState {
    IDLE,        // 监听唤醒词
    WAKEUP,      // 被唤醒，准备录音
    RECORDING,   // 录音上传中
    PROCESSING,  // 等待服务端处理结果
    PLAYING      // 播放TTS音频
}
```

### 4.2 状态转换规则

| 当前状态 | 事件 | 目标状态 | 动作 |
|---------|------|---------|------|
| IDLE | 唤醒词检测 | WAKEUP | 停止唤醒监听，启动录音 |
| WAKEUP | 录音就绪 | RECORDING | 开始录音+上传 |
| RECORDING | VAD检测静音 | PROCESSING | 停止录音，发送完成标记 |
| RECORDING | 超时30s | PROCESSING | 强制停止录音，发送已有数据 |
| PROCESSING | 收到TTS音频 | PLAYING | 启动AudioTrack播放 |
| PROCESSING | 超时10s | IDLE | 丢弃本次对话，回到监听 |
| PLAYING | TTS播放完成 | IDLE | 停止播放，重新启动唤醒监听 |
| PLAYING | 超时60s | IDLE | 强制停止播放 |
| PLAYING | 唤醒词打断 | RECORDING | 立即停止播放，开始新录音 |
| PLAYING | 唤醒词打断 | RECORDING | （同上，打断优先级最高） |

### 4.3 超时保护

每个非IDLE状态都有超时计时器，使用 `Handler.postDelayed()` 实现：

```java
// 进入状态时启动超时计时器
handler.postDelayed(timeoutRunnable, timeoutMs);

// 离开状态时取消计时器
handler.removeCallbacks(timeoutRunnable);
```

### 4.4 打断机制

PLAYING 状态下的唤醒词打断：
1. TTSEngine 立即停止播放
2. RobotActionManager 停止当前动作
3. 切换到 RECORDING 状态
4. 启动新的录音会话

---

## 5. RobotStateCoordinator 详细设计

### 5.1 协调规则

| 业务状态变化 | Coordinator动作 | 目的 |
|-------------|----------------|------|
| → WAKEUP | `SysEventApi` 注入 WantToActive | 唤醒机器人 |
| → RECORDING | 保持 WantToActive 心跳 | 防止机器人降级 |
| → PLAYING | 注入 WantToActive | 播放期间不允许降级 |
| → IDLE | 不干预 | 允许机器人按自身逻辑管理 |

| 机器人状态变化 | Coordinator动作 | 目的 |
|--------------|----------------|------|
| → Sleep（业务非IDLE） | 注入 WantToActive | 强制拉回，保护业务 |
| → Active（业务IDLE） | 通知 BusinessStateMachine | 机器人被唤醒，可预监听 |
| PIR 检测到人（业务IDLE）| 可选：进入 WAKEUP | 辅助唤醒 |

### 5.2 解耦保证

- `BusinessStateMachine` 不依赖任何机器人SDK类
- `RobotStateCoordinator` 是唯一同时依赖两个系统的类
- 如果换机器人硬件，只需重写 Coordinator
- 如果机器人SDK升级，只需修改 Coordinator 的映射逻辑

---

## 6. 各Manager详细设计

### 6.1 WakeUpManager

```
职责: 管理AIKit唤醒词检测
SDK: AIKit.aar (AiHelper, AiRequest, AiAudio, AiHandle, AiListener)
```

生命周期：
```
init() → startListening() → [持续写入音频] → onResult(唤醒) → stopListening()
         ↑                                                              │
         └──────────── 回到IDLE后重新启动监听 ──────────────────────────┘
```

关键实现：
- 使用 PreProcessedRecorder 的 FOR_WAKEUP 音频喂给 AIKit
- 唤醒词: "悟空悟空"(nCM:1200), "你好悟空"(nCM:1450)
- 唤醒词配置文件放在 assets/aikit_resources/keyword.txt
- 支持预留切换逻辑（接口抽象，可替换为其他唤醒引擎）

### 6.2 AudioRecorderManager

```
职责: 通过PreProcessedRecorder获取录音数据，VAD检测
SDK: opensdk-v1.1.0.aar (PreProcessedRecorder)
```

音频流：
```
PreProcessedRecorder.FOR_ASR (6通道)
  → 提取单通道数据
  → VAD检测
  → 编码为base64
  → 通过WebSocketManager发送
```

关键实现：
- `PreProcessedRecorder.init(context)` → `start()` → `registerRecordListener(listener, FOR_ASR)`
- VAD检测：基于能量阈值 + 持续静音时间判断
- 录音格式：PCM 16bit 16kHz 单声道
- 6通道数据需提取 MIC1 通道（或使用beamforming后的单通道）

### 6.3 WebSocketManager

```
职责: 管理WebSocket连接、消息收发、心跳、重连
库: OkHttp
```

通信协议：
```json
// 上传
{"type":"chat","session":"uuid","audio":"base64...","is_final":false}
{"type":"chat","session":"uuid","audio":"base64...","is_final":true}

// 接收
{"type":"tts","text":"回复文本","audio":"base64...","action":"head_nod","is_final":false}
{"type":"tts","text":"回复文本","audio":"base64...","action":"head_nod","is_final":true}
```

心跳机制：
- 每30秒发送 ping
- 收到 pong 即认为连接正常
- pong 超时 10 秒 → 触发重连

断线重连：
- 指数退避：1s → 2s → 4s → 8s → 16s → 32s → 60s（最大）
- 重连成功后重置退避
- 最大重连次数：无限（持续尝试）

### 6.4 TTSEngine

```
职责: 流式播放TTS音频
实现: AudioTrack
```

播放流程：
```
收到TTS音频片段(base64) → 解码为PCM → 写入AudioTrack
  → 继续接收更多片段 → 持续写入
  → 收到is_final=true → 等待播放完成 → 通知完成
```

关键实现：
- AudioTrack 配置: PCM 16bit 16kHz 单声道, STREAM_MUSIC
- 流式模式: 先初始化 AudioTrack，收到数据后 write()
- 打断: 调用 stop() + flush() 立即停止
- 播放完成回调: 通过 Handler 检测 AudioTrack 播放位置

### 6.5 RobotActionManager

```
职责: 调用机器人SDK执行动作（先预留接口）
SDK: opensdk-v1.1.0.aar (ActionApi, ExpressApi, MotorApi, LightApi)
```

预留接口：
```java
public interface IRobotActionController {
    void playAction(String actionName, ResourcePolicy policy, ResponseListener<Void> listener);
    void playExpression(String expressionName, int loopCount);
    void stopAction();
    void stopExpression();
    void controlServo(int motorId, int angle, int duration);
    void controlLight(List<Integer> ids, int color, int duration);
}
```

### 6.6 ConfigManager

```
职责: 管理所有可配置项，提供LiveData观察
实现: SharedPreferences + LiveData
```

配置项：
| Key | 类型 | 默认值 | 说明 |
|-----|------|--------|------|
| ws_server_url | String | wss://localhost:8080/ws | WebSocket服务器地址 |
| wake_word_wukong_enabled | boolean | true | "悟空悟空"唤醒词开关 |
| wake_word_nihao_enabled | boolean | true | "你好悟空"唤醒词开关 |
| wake_word_ncm_wukong | int | 1200 | "悟空悟空"门限值 |
| wake_word_ncm_nihao | int | 1450 | "你好悟空"门限值 |
| tts_volume | int | 80 | TTS播放音量 |
| vad_silence_duration_ms | int | 1500 | VAD静音持续时间 |
| vad_energy_threshold | int | 500 | VAD能量阈值 |
| ws_heartbeat_interval_ms | int | 30000 | 心跳间隔 |
| ws_reconnect_max_interval_ms | int | 60000 | 重连最大间隔 |
| recording_timeout_ms | int | 30000 | 录音超时 |
| processing_timeout_ms | int | 10000 | 处理超时 |
| playing_timeout_ms | int | 60000 | 播放超时 |
| llm_model_name | String | "" | LLM模型名（预留） |

---

## 7. 数据层设计

### 7.1 Entity定义

**ChatHistoryEntity**
| 字段 | 类型 | 说明 |
|------|------|------|
| id | long (PK, auto) | 主键 |
| sessionId | String | 会话ID |
| role | String | 角色: user/assistant |
| content | String | 内容文本 |
| timestamp | long | 时间戳 |

**WakeUpLogEntity**
| 字段 | 类型 | 说明 |
|------|------|------|
| id | long (PK, auto) | 主键 |
| keyword | String | 唤醒词 |
| confidence | int | 置信度得分 |
| timestamp | long | 时间戳 |

**ActionEntity**
| 字段 | 类型 | 说明 |
|------|------|------|
| id | long (PK, auto) | 主键 |
| actionType | String | 动作类型 |
| params | String | 动作参数(JSON) |
| timestamp | long | 时间戳 |
| executed | boolean | 是否已执行 |

### 7.2 DAO方法

每个Entity至少提供:
- `insert()`
- `getAll()` (LiveData返回)
- `getBySessionId()` (ChatHistory)
- `getRecent()` (限制数量)

---

## 8. 服务生命周期设计

### 8.1 启动流程

```
系统开机
  → BootCompletedReceiver.onReceive()
  → startForegroundService(WukongService)

WukongService.onCreate()
  → 创建Notification, startForeground()
  → ConfigManager.init()
  → WkSdk.init(context)
  → AiHelper.init(params)
  → WukongDatabase.getInstance()
  → 初始化所有Manager
  → RobotStateCoordinator.init()
  → BusinessStateMachine.transitionTo(IDLE)
  → IDLE状态启动WakeUpManager

WukongService.onDestroy()
  → BusinessStateMachine.cleanup()
  → 所有Manager.release()
  → AiHelper.unInit()
```

### 8.2 守护机制

```
ServiceWatchdog (JobScheduler)
  → 每15分钟检查服务是否存活
  → 如果服务未运行 → 重新启动

WukongService (自身)
  → return START_STICKY
  → onDestroy中发送自定义广播触发重启
```

---

## 9. 双状态机协调时序图

```
用户说"悟空悟空"
    │
    ├── WakeUpManager检测到唤醒词
    │     └── BusinessStateMachine: IDLE → WAKEUP
    │           └── RobotStateCoordinator: 注入WantToActive
    │                 └── 机器人: Sleep/Standby → Active
    │
    ├── AudioRecorderManager开始录音
    │     └── BusinessStateMachine: WAKEUP → RECORDING
    │           └── RobotStateCoordinator: 保持Active
    │
    ├── VAD检测到静音
    │     └── BusinessStateMachine: RECORDING → PROCESSING
    │           └── WebSocketManager发送is_final=true
    │
    ├── 收到TTS音频
    │     └── BusinessStateMachine: PROCESSING → PLAYING
    │           └── TTSEngine开始播放
    │           └── RobotActionManager执行动作
    │
    ├── TTS播放完成
    │     └── BusinessStateMachine: PLAYING → IDLE
    │           └── RobotStateCoordinator: 不干预（允许机器人降级）
    │           └── WakeUpManager重新开始监听
    │
    └── 如果播放期间用户说"悟空悟空"（打断）
          └── WakeUpManager检测到唤醒词
                └── BusinessStateMachine: PLAYING → RECORDING
                      └── TTSEngine.stop()
                      └── RobotActionManager.stopAction()
                      └── AudioRecorderManager开始新录音
```

---

## 10. 扩展性设计

### 10.1 可替换组件

| 组件 | 接口/抽象 | 替换场景 |
|------|----------|---------|
| 唤醒引擎 | `IWakeUpEngine` | 换讯飞VoiceWakeuper或其他引擎 |
| 录音源 | `IAudioSource` | 换Android AudioRecord |
| 通信协议 | `ICommunicationChannel` | 换gRPC/MQTT等 |
| TTS播放 | `ITTSPlayer` | 换其他播放器 |
| 机器人动作 | `IRobotActionController` | 换其他机器人平台 |

### 10.2 未来扩展预留

- **SettingsActivity**: 用户可配置WebSocket地址、唤醒词、大模型选择等
- **ConfigManager**: 所有配置项可动态修改，Service实时响应
- **RobotActionManager**: 接口抽象，后续可扩展跳舞、唱歌等复杂行为
- **WebSocket协议**: 协议版本号预留，支持后续升级
- **LLM模型选择**: 预留模型名配置项，服务端根据此选择不同模型

---

## 11. 目录结构

```
app/src/main/java/com/wukong/agent/
├── WukongApplication.java
├── service/
│   ├── WukongService.java
│   └── BootCompletedReceiver.java
├── watchdog/
│   └── ServiceWatchdog.java
├── statemachine/
│   ├── BusinessState.java
│   ├── BusinessStateMachine.java
│   └── StateChangeListener.java
├── coordinator/
│   └── RobotStateCoordinator.java
├── manager/
│   ├── WakeUpManager.java
│   ├── AudioRecorderManager.java
│   ├── WebSocketManager.java
│   ├── TTSEngine.java
│   ├── RobotActionManager.java
│   └── ConfigManager.java
├── data/
│   ├── db/
│   │   ├── WukongDatabase.java
│   │   └── Converters.java
│   ├── dao/
│   │   ├── ChatHistoryDao.java
│   │   ├── WakeUpLogDao.java
│   │   └── ActionDao.java
│   ├── entity/
│   │   ├── ChatHistoryEntity.java
│   │   ├── WakeUpLogEntity.java
│   │   └── ActionEntity.java
│   └── repository/
│       ├── ChatHistoryRepository.java
│       ├── WakeUpLogRepository.java
│       └── ActionRepository.java
├── model/
│   ├── WebSocketMessage.java
│   └── RobotConfig.java
├── util/
│   ├── AudioUtils.java
│   ├── JsonUtils.java
│   ├── VADDetector.java
│   └── NetworkUtils.java
└── settings/
    ├── SettingsActivity.java
    └── SettingsFragment.java

app/src/main/res/
├── layout/
│   └── notification_layout.xml
├── xml/
│   └── preferences.xml
├── values/
│   ├── strings.xml
│   ├── colors.xml
│   └── keys.xml
└── ...

app/src/main/assets/
└── aikit_resources/
    └── keyword.txt

app/src/test/java/com/wukong/agent/
├── statemachine/
│   └── BusinessStateMachineTest.java
├── manager/
│   ├── WakeUpManagerTest.java
│   ├── AudioRecorderManagerTest.java
│   ├── WebSocketManagerTest.java
│   ├── TTSEngineTest.java
│   └── ConfigManagerTest.java
├── coordinator/
│   └── RobotStateCoordinatorTest.java
├── util/
│   └── VADDetectorTest.java
└── mock/
    ├── TestRobotSDK.java
    ├── MockAiHelper.java
    └── MockWebSocketServer.java
```
