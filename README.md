# WukongAgent - 悟空2代机器人语音交互服务

## 项目概述

wukong_agent 是一个运行在优必选悟空2代机器人上的 Android 后台服务应用，实现语音唤醒、对话、TTS播放等核心功能。

### 核心流程

```
语音唤醒(AIKit) → PreProcessedRecorder录音 → WebSocket上传(OkHttp)
  → 服务端ASR+LLM+TTS处理 → WebSocket返回TTS音频 → AudioTrack流式播放
```

### 状态机

```
IDLE → WAKEUP → RECORDING → PROCESSING → PLAYING → IDLE
```

- 每个状态有超时保护：RECORDING 30s, PROCESSING 10s, PLAYING 60s
- PLAYING期间检测到唤醒词 → 立即切换到RECORDING（打断机制）

## 环境要求

| 项目 | 版本要求 |
|------|---------|
| Android Studio | Flamingo (2022.2.1) 或更高 |
| JDK | 17 |
| Gradle | 8.7+ (项目已配置 9.4.1) |
| Android SDK | compileSdk 34, minSdk 26, targetSdk 30 |
| 目标设备 | 优必选悟空2代 (Android 11 user release) |

## 导入步骤

1. 克隆项目到本地
2. 使用 Android Studio 打开项目根目录
3. 等待 Gradle 同步完成（首次可能需要较长时间下载依赖）
4. 确认 `app/libs/` 目录下存在 `opensdk-v1.1.0.aar` 和 `AIKit.aar`
5. 连接悟空2代机器人（USB连接，adb检测到设备）
6. 选择 `app` 模块，点击 Run 安装到机器人

## 项目结构

```
app/src/main/java/com/wukong/agent/
├── WukongApplication.java          # Application初始化
├── service/
│   ├── WukongService.java          # 核心ForegroundService
│   └── BootCompletedReceiver.java  # 开机自启
├── watchdog/
│   ├── ServiceWatchdog.java        # JobScheduler守护
│   └── ServiceRestartReceiver.java # 崩溃重启
├── statemachine/
│   ├── BusinessState.java          # 状态枚举
│   ├── BusinessStateMachine.java   # 状态机
│   └── StateChangeListener.java   # 状态监听接口
├── coordinator/
│   └── RobotStateCoordinator.java  # 双状态机协调
├── manager/
│   ├── WakeUpManager.java          # 唤醒词管理(AIKit)
│   ├── AudioRecorderManager.java   # 录音管理(PreProcessedRecorder)
│   ├── WebSocketManager.java       # WebSocket通信(OkHttp)
│   ├── TTSEngine.java             # TTS播放(AudioTrack)
│   ├── RobotActionManager.java     # 机器人动作控制
│   └── ConfigManager.java          # 配置管理
├── data/
│   ├── entity/                     # Room实体
│   ├── dao/                        # Room DAO
│   ├── db/                         # Room数据库
│   └── repository/                 # 数据仓库
├── model/                           # 数据模型
├── util/                            # 工具类
└── settings/                        # 配置界面
```

## 配置说明

### 讯飞AIKit配置

1. 在讯飞开放平台创建应用，获取 appId、apiKey、apiSecret
2. 在 `WukongService.initComponents()` 中填入对应凭证
3. 唤醒词配置文件: `app/src/main/assets/aikit_resources/keyword.txt`

### WebSocket服务器

默认地址: `wss://localhost:8080/ws`

可通过 SettingsActivity 修改，或通过 SharedPreferences:

```java
SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
prefs.edit().putString("ws_server_url", "wss://your-server:port/ws").apply();
```

### 唤醒词

| 唤醒词 | 默认门限(nCM) | 说明 |
|--------|-------------|------|
| 悟空悟空 | 1200 | 四音节重复，辨识度高 |
| 你好悟空 | 1450 | 四音节非重复 |

门限值可通过 SettingsActivity 调整。值越低越灵敏，但误触发率越高。

## 构建与运行

### Debug构建

```bash
./gradlew assembleDebug
```

### Release构建

```bash
./gradlew assembleRelease
```

### 安装到机器人

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 运行测试

```bash
./gradlew test              # 单元测试
./gradlew connectedAndroidTest  # 集成测试(需连接设备)
```

## 通信协议

### 上传消息

```json
{
  "type": "chat",
  "session": "uuid",
  "audio": "base64编码的PCM音频",
  "is_final": false
}
```

### 接收消息

```json
{
  "type": "tts",
  "text": "回复文本",
  "audio": "base64编码的PCM音频",
  "action": "head_nod",
  "is_final": true
}
```

### 心跳

```json
// Client -> Server
{"type": "ping"}

// Server -> Client
{"type": "pong"}
```

## 已知问题排查

| 问题 | 可能原因 | 解决方案 |
|------|---------|---------|
| 服务启动后立即停止 | 未授予必要权限 | 检查录音、存储权限 |
| 唤醒词无法识别 | AIKit未正确初始化 | 检查appId/apiKey/apiSecret配置 |
| WebSocket连接失败 | 服务器地址错误或网络不通 | 检查URL配置和网络连接 |
| TTS播放无声 | 音量设置过低 | 检查TTS音量配置 |
| 机器人不执行动作 | opensdk未初始化 | 确认WkSdk.init()调用成功 |
| 服务被系统杀死 | 内存不足 | 检查ForegroundService Notification是否显示 |

## 技术文档

- [架构设计文档](docs/architecture.md)
- [悟空2代SDK文档](opensdk文档/悟空2代SDK文档_V1.0.9_20260107.pdf)
- [讯飞AIKit唤醒文档](https://www.xfyun.cn/doc/asr/AIkit_awaken/Android-SDK.html)

## License

内部项目，未公开授权。
