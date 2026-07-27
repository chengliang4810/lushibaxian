# 炉石拔线

安卓端一键「拔线」工具：对局中短暂切断**仅炉石传说**的网络，触发游戏重连，从而跳过超长动画，争取更多思考与操作时间。

> 仅供学习与个人使用。请遵守游戏用户协议与当地法律法规。

## 功能

- **只断炉石**：本地 VPN 仅劫持国服炉石包名，其它 App 不受影响
- **悬浮球**：可拖动；单击拔线；显示网络延迟（带 `ms` 与颜色）
- **一键启动**：授权后点启动 → 常驻隧道 + 悬浮球 → 自动打开炉石并回到后台
- **网络延迟**：约每 2 秒探测一次（通用网络 RTT，非游戏内延迟）

## 原理

采用**常驻隧道 + 短暂丢包**（非「建黑洞再拆 VPN」）：

1. 启动后建立只允许炉石进入的本地 VPN  
2. 用户态转发 UDP/TCP（`protect()` 出站，避免环路）  
3. 点击悬浮球时丢弃上下行包约 **200ms ± 50ms**，并重置会话  
4. **不拆 VPN**，恢复转发后由游戏走同一隧道重连  

目标包名（国服）：

```text
com.blizzard.wtcg.hearthstone.cn.baidu_sem_dev
```

## 截图 / 交互

| 操作 | 效果 |
|------|------|
| 主界面 **启动** | 开隧道 + 悬浮球，尝试打开炉石 |
| 主界面 **停止** | 关悬浮球与隧道 |
| 单击悬浮球 | 触发一次拔线 |
| 拖动悬浮球 | 改位置并记住 |
| 拔线中 | 球变红，显示「断」 |
| 冷却中 | 球变灰，显示「…」 |

悬浮球延迟颜色：

| 延迟 | 颜色 |
|------|------|
| &lt; 80ms | 绿 |
| 80–149ms | 铜 |
| ≥ 150ms | 黄 |

## 权限

- **悬浮窗**：显示悬浮球  
- **网络权限（系统 VPN）**：劫持并转发炉石流量  
- **通知**：前台服务保活  

## 安装

### 直接安装正式包

在 [Releases](https://github.com/chengliang4810/lushibaxian/releases) 下载最新 `app-release.apk`，手机安装即可（需允许「未知来源」）。

```bash
adb install -r app-release.apk
```

### 从源码编译

环境：JDK 17、Android SDK（API 35）。

```bash
# 配置 SDK 路径
echo "sdk.dir=/path/to/Android/sdk" > local.properties

# Debug
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Release（体积更小，不可调试）
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

## 使用步骤

1. 安装并打开「炉石拔线」  
2. 按提示完成悬浮窗、网络权限  
3. 点圆形按钮 **启动**（会尝试打开炉石）  
4. 确认能正常登录/对局  
5. 动画中点悬浮球 **拔**  

## 常见问题

| 现象 | 建议 |
|------|------|
| 启动后炉石上不了网 | 先停用其它 VPN/加速器；确认包名是否匹配你的安装渠道 |
| 点了不触发重连 | 网络较好时可适当加长基准时长（源码 `Prefs.BASE_DURATION_MS`） |
| 直接掉线 / 重连过久 | 缩短基准时长，或检查是否弱网 |
| 与加速器冲突 | 系统同时只能有一个 VPN |
| 服务被杀 | 厂商设置中允许自启动、电池无限制 |

## 工程结构

```text
app/src/main/java/com/lushibaxian/pullwire/
  MainActivity.kt            # 主界面：启动/停止、权限、延迟
  FloatBallService.kt        # 悬浮球 + 延迟显示
  PullWireVpnService.kt      # 常驻 VPN + 丢包开关
  PullWireController.kt      # 状态机（空闲 / 拔线 / 冷却）
  LatencyProbe.kt            # 通用网络 RTT 探测
  Prefs.kt                   # 配置与时长抖动
  vpn/Packet.kt              # IPv4/UDP/TCP 组包
  vpn/VpnEngine.kt           # 用户态 NAT 转发
```

## 版本

- 应用 ID：`com.lushibaxian.pullwire`
- 版本：`0.1.0`（versionCode 1）
- minSdk 26 / targetSdk 35

## License

MIT
