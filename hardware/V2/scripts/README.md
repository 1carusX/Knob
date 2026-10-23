# knob-v2 生成脚本

原理图和 PCB 都是脚本生成的，不是手画的。`build.sh` 从零重建两者，会**覆盖** `knob-v2.kicad_sch` 和
`knob-v2.kicad_pcb`，在 KiCad 里手动做的修改如果没同步写进这些脚本，重建后就会丢失。

依赖：KiCad 9（`kicad-cli` 和 `pcbnew` Python 模块）、Python 3 + Pillow（`render.sh` 和 `fab.py` 出 PCB 图用）、
poppler-utils（`render.sh` 必需；`fab.py` 优先用它，没有就用 Ghostscript）、Java 21+ 和 Freerouting 2.5.0 的 jar
（https://github.com/freerouting/freerouting/releases ，64 MB，不放进工程）。交互式 BOM 要装
InteractiveHtmlBom：`pip install --user --break-system-packages InteractiveHtmlBom`（要用装了 KiCad 的那个系统
Python，它依赖 `pcbnew` 模块）。

```
FREEROUTING=~/opt/freerouting-2.5.0.jar scripts/build.sh
python3 scripts/fab.py production
```

| 脚本 | 作用 |
|------|------|
| `gen_sch.py <out.kicad_sch>` | 按 `PARTS` 列表（器件、封装、网络、GPIO 分配）写出原理图，信号靠标签连接。输出文件已存在时保留其中的符号 UUID，PCB 就不会和原理图断开关联；只改原理图不用重建 PCB |
| `gen_pcb.py <netlist>` | 从空板开始：放封装、网络、布局（`PLACE`）、板框、GND 铺铜、设计规则和网络类 |
| `route.py preroute x` | 加入事先规划好、**锁定**的走线：LCD 排线、背光栅极、经 ESD 阵列进 USB-C 焊盘的 USB D+/D-、VBUS、CC、EN |
| `route.py export-dsn <dsn>` / `import-ses <ses>` | 与 Freerouting 往返（导出时去掉 GND 铺铜，让 GND 跟其他网络一样布线） |
| `route.py stitch <pitch>` | 在两层铺铜都完整的地方打 GND 缝合过孔 |
| `route.py silk x` | 隐藏 R/C/Q 的位号，删掉超出板边的封装丝印 |
| `swap_fp.py <ref> <lib:fp>` | 在现有 PCB 上原地替换一个封装（保留位置和网络） |
| `metrics.py <pcb>` | 每个网络的实际走线长度与理想长度对比、过孔数 |
| `render.sh <pcb> <png> <layers> [dpi]` | 把指定层渲染成 PNG |
| `fab.py <out dir>` | 嘉立创生产文件：Gerber + 钻孔 zip、BOM、CPL。立创编号在它的 `PARTS` 表里；EC11（SW3）手焊，不进 BOM。另外顺带出查看用的文档：原理图 PDF、正反面 2D PCB 图、交互式 BOM（见下方注意事项） |
| `jlc_align.py <pcb> <lib> <ref>=<fp> ...` | 把 `jlc.pretty` 里的嘉立创/立创封装对齐到板上焊盘，得出 CPL 的旋转角和中心点（`fab.py` 调用） |
| `jlc.pretty/` | 有方向要求的器件的立创封装，用 easyeda2kicad 下载 |
| `sexp.py`、`libsym.py` | S 表达式和 KiCad 符号库的辅助函数 |

注意：

- **运行任何脚本前先关掉 KiCad（包括工程管理器）。** KiCad 把工程设置留在内存里，打开编辑器或退出时会写回
  `knob-v2.kicad_pro`，把设计规则和 Power 网络类换成默认值。之后在 GUI 里跑 DRC 会报出并不存在的间距
  （0.2 mm）和板边（0.5 mm）错误。出现这种情况就关掉 KiCad，用下面的命令恢复规则：
  `python3 -c "import sys; sys.argv=['x','x']; sys.path.insert(0,'scripts'); import gen_pcb; gen_pcb.write_project()"`。
- `gen_pcb.write_project()` 是合并写入 `knob-v2.kicad_pro`，KiCad 自己的键会保留。
- Freerouting 会把锁定的 USB 连接报成未布线，以 DRC 的 `0 unconnected` 为准。
- Freerouting 每次结果不同，有时会留下没连上的线；这时 `build.sh` 返回非零，重跑即可。每次重建后都要看
  `metrics.py` 和 DRC 的结果。
- `fab.py` 的查看用文档（`knob-v2-schematic.pdf`、`knob-v2-pcb-top.png`、`knob-v2-pcb-bottom.png`、
  `knob-v2-ibom.html`）是尽力而为：缺工具或导出失败只打印 `WARNING`，并删掉这一项的旧文件（不让过期文档和新
  Gerber 放在一起），Gerber、BOM、CPL 照常生成，退出码仍是 0。看到 `WARNING` 就补装对应工具后重跑。
