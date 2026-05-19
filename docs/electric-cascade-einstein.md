# wukong_agent 代码解读

## 总览
 wukong_agent 项目的 34 个 Java 源文件。

## 代码说明（由入口到细节）

### 第1组：应用入口与核心服务
1. `WukongApplication.java` — Application 子类，启动看门狗
2. `service/WukongService.java` — 核心编排服务，5个监听器
3. `service/BootCompletedReceiver.java` — 开机自启广播

### 第2组：状态机
4. `statemachine/BusinessState.java` — 5个状态枚举
5. `statemachine/BusinessStateMachine.java` — 状态机实现
6. `statemachine/StateChangeListener.java` — 状态变化监听接口

### 第3组：核心管理器
7. `manager/WakeUpManager.java` — 讯飞AIKit唤醒
8. `manager/AudioRecorderManager.java` — 6通道录音+VAD
9. `manager/WebSocketManager.java` — WebSocket通信
10. `manager/TTSEngine.java` — TTS PCM流式播放
11. `manager/RobotActionManager.java` — 机器人动作控制
12. `manager/ConfigManager.java` — 响应式配置

### 第4组：数据模型与协议
13. `model/RobotConfig.java` — 配置POJO
14. `model/WebSocketMessage.java` — WebSocket协议消息

### 第5组：数据层
15. `data/entity/ChatHistoryEntity.java`
16. `data/entity/WakeUpLogEntity.java`
17. `data/entity/ActionEntity.java`
18. `data/dao/ChatHistoryDao.java`
19. `data/dao/WakeUpLogDao.java`
20. `data/dao/ActionDao.java`
21. `data/db/Converters.java`
22. `data/db/WukongDatabase.java`
23. `data/repository/ChatHistoryRepository.java`
24. `data/repository/WakeUpLogRepository.java`
25. `data/repository/ActionRepository.java`

### 第6组：协调器与工具类
26. `coordinator/RobotStateCoordinator.java`
27. `util/VADDetector.java`
28. `util/AudioUtils.java`
29. `util/JsonUtils.java`
30. `util/NetworkUtils.java`

### 第7组：设置与保活
31. `settings/SettingsActivity.java`
32. `settings/SettingsFragment.java`
33. `watchdog/ServiceWatchdog.java`
34. `watchdog/ServiceRestartReceiver.java`

## 执行方式
- 读取每个文件的完整源码
- 逐文件讲解：用途、关键代码段、设计考量
- 用可视化图表展示核心交互流程
- 全部34个文件讲完后做整体总结
