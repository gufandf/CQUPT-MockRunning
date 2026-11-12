# README 截图

`05`~`07` 是 HarmonyOS 上的 v1.8 截图。其余是在 **Android 16（API 36）模拟器**
上重拍的（1080×2400），统一处理成 **720 宽 + 256 色 PNG** ——
UI 截图用调色板量化是标准做法，体积掉一半多，肉眼看不出差别。

| 文件 | 页面 | 内容 |
|---|---|---|
| `icon.png` | — | 应用图标（插画），README 顶部大图，512×512 真彩 |
| `01-run.png` | 【主页】 | 纯色底 + 玻璃卡片 + 胶囊【开始打卡】+ 地图占位 |
| `02-stats.png` | 【设置 · 成绩统计】 | 刷新统计 + 各场地次数 + 最近记录 |
| `04-settings.png` | 【设置首页】 | 七个分组入口 + 各自当前取值摘要 |
| `08-settings-run.png` | 【设置 · 跑步参数】 | 三个滚轮（配速 / 距离 / 抖动）+ 快捷 chip + **轨迹毛刺开关** + 电池白名单 |
| `09-dark.png` | 【主页 · 深色】 | 深色纯底 + 玻璃卡片 + 浅蓝胶囊按钮 |
| `11-countdown.png` | 【主页 · 倒计时】 | 点【开始打卡】后的 3 秒倒计时：压暗的地图 + 环 + 秒数 + 「再点一次可取消」 |
| `12-about.png` | 【设置 · 关于】 | 版本号 + 本项目仓库 + 原仓库 + 简短说明（**没有作者署名**） |
| `08-fluid.png` | 【设置 · 流体云】 | 胶囊可用状态 + 测试胶囊入口（Android 16 上的实际判定结果） |
| `10-capsule.png` | 【通知栏】 | 「测试胶囊」在通知栏里的实际样子：标题 + 副标题 + **四段分色的进度条 + 三个里程碑点** |
| `05-battery-bad.png` | — | 电池条 · **红色**（未加白名单） |
| `06-battery-ok.png` | — | 电池条 · **绿色**（已加白名单，无 ✅ emoji） |
| `07-log.png` | 【日志】 | 完整运行日志，①~⑥ 全流程 |

> `05`~`07` 还是 v1.8 拍的，但那三处**没改过**，仍然准确：
> 电池条的红/绿两态、以及独立的日志页。
>
> `04` / `08-settings-run` / `09` / `11` / `12` 是最近一轮在模拟器上重拍的，
> 反映的是当前界面：设置页已经分成两层（首页只列分组入口）、
> 背景是纯色（不再是光场渐变）、主页多了一层倒计时。
>
> 关于页里的 openid 是 `testaccount` 这类占位账号，仓库地址是真实公开地址。
>
> `08` 里的「系统没放行实时通知」是**模拟器上的真实结果**：AOSP 镜像不带
> ColorOS 的流体云，`canPostPromotedNotifications()` 返回 false。真机 ColorOS 16 上
> 这一行会变成绿色的「✓ 流体云可用」。这个分支本身就是给用户看诊断结论用的。
>
> `10` 说明的是另一件事：**即使系统不放行「提升为实时活动」，`ProgressStyle` 本身
> 也是生效的** —— 进度条、分段、里程碑点全都画出来了，只是没被收成胶囊。
> 也就是说低版本系统 / 没开实时通知的机器上，这条通知依然比旧版那三行文字好看。
> 真机 ColorOS 16 上它会被收进状态栏胶囊，展开就是这张卡片。

## 拍摄时做的脱敏

- `02-stats.png` 统计页所有数字打了码
- 旧图里日志的 `token =` 和 `sportRecordNo` 也打了码
- 新图里的 openid 是 `testaccount` 占位账号

## 重新处理

`adb exec-out screencap -p` 出的原图直接喂进去：

```bash
python - <<'EOF'
from PIL import Image
import os
src = {
    '01-run': 'shot_main_light.png',
    '04-settings': 'shot_settings.png',
    '08-settings-run': 'shot_run.png',
    '09-dark': 'shot_main_dark.png',
    '11-countdown': 'shot_countdown.png',
    '12-about': 'shot_about.png',
}
for name, path in src.items():
    im = Image.open(path).convert('RGB')
    w, h = im.size
    im = im.resize((720, int(h * 720 / w)), Image.LANCZOS)
    im.quantize(colors=256, method=Image.MEDIANCUT, dither=Image.NONE) \
      .save(f'docs/img/{name}.png', optimize=True)
EOF
```

## 显示尺寸

README 里用 HTML `<img>` 控制宽度，不用 markdown 图片语法
（markdown 没法限宽，竖屏截屏会撑满整个页面）：

```html
<img src="docs/img/01-run.png" width="200">
```
