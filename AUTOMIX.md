# AutoMix（混音过渡）说明

本仓库是 [ljyh223/Mei](https://github.com/ljyh223/Mei)（Apache-2.0）的一个分支构建，
在播放层移植了 [Gaimoydev/HyPlayer-Nextgen](https://github.com/Gaimoydev/HyPlayer-Nextgen)
的 AutoMix 思路，并针对 Android/Media3 的限制重新实现。

上游 AutoMix 是为 Windows WASAPI 写的：两首歌的 PCM 直接喂进一个共享混音总线。
Android 没有等价的公共混音节点，所以这里改成：

```
ExoPlayer A ──(volume 0, 只推进时间轴)──┐
ExoPlayer B ──(volume 0, 只推进时间轴)──┤
                                       ├─→ MediaSession position / 切歌状态
MediaCodec ×2 → 离线解码 PCM ──────────┤
                                       └─→ AutoMixEngine → AudioTrack(float) 出声
```

也就是说：**过渡期间出声完全由自己的 AudioTrack 负责**，两个 ExoPlayer 静音陪跑，
混音结束后把声音交回 ExoPlayer B（seek 到对齐位置 + 150ms 淡入）。

## 目录

| 文件 | 作用 |
| --- | --- |
| `AutoMixEngine.kt` | 时间线状态机（Handover → Mixing → RampBack → Aligning），每个 quantum 推进 |
| `MixVoice.kt` | 单条声部：内容位置 → 输出采样，串联 WSOLA 变速 |
| `WsolaStretcher.kt` | WSOLA 变速/变调不变的时间伸缩 |
| `PcmRegionDecoder.kt` | 用 MediaExtractor/MediaCodec 把某段音频离线解码成 float PCM |
| `MixerOutput.kt` | AudioTrack 输出线程 + 延迟探测（供交接对齐用） |
| `BeatDetector.kt` | 内置 DSP 拍点检测（频谱通量 + 自相关 + 倍频修正），模型不可用时的兜底 |
| `BeatGridMarks.kt` | 拍网格抽象：模型结果 / DSP 结果统一成同一结构 |
| `AutoMixSession.kt` | 会话编排：解码 → 拍点对齐 → 起播 → 预 seek → 交接 |
| `AutoMixBridge.kt` | 与 Mei 现有 `SmartTransitionController` 的适配层 |

拍点对齐优先使用 Mei 自带的 **BeatNet** 模型（`app/src/main/assets/beatnet/`）：
- 重叠长度取 A 的 4 小节，退出点吸附到最近的小节起点；
- B 的进入点取 B 的第一个小节起点；
- 变速比 = 两者拍周期之比（限制在 ±8% 内，避免明显变调）。

模型不可用时退回 DSP 检测；两者都失败时仍然走 PCM 混音，只是不做拍点吸附。

## 已知限制

- 只在 `playbackSpeed == 1.0` 时启用；带倍速播放会自动放弃混音。
- 两首歌 BPM 相差超过 ±8% 时无法完全拍点同相，只能保证整数拍重叠。
- 混音输出与 ExoPlayer 输出是两条不同的 AudioTrack，交接瞬间可能存在轻微响度差。

## 构建

```bash
./gradlew :app:assembleDebug     # 本机调试包
./gradlew :app:assembleRelease   # 需要注入签名环境变量，见 .github/workflows/release.yml
```

默认只打 `arm64-v8a`，需要全架构时加 `-PmeiAllAbis=true`。

## 许可证

上游代码遵循 Apache-2.0，见 `LICENSE` 与 `THIRD_PARTY_NOTICES`。
本仓库对 `playback/automix/` 与 `playback/transition/` 的修改同样以 Apache-2.0 发布。
