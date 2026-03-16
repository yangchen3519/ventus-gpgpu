# DCachev2 Debug Playbook

## 1. 接手前先确认 6 件事

1. 当前问题是否真的落在 `DCachev2` 主路径，而不是 `DCache.scala` 或 CTA/LSU/CSR 语义。
2. 当前 case 的首个真实异常点是什么，而不是最终崩溃点是什么。
3. 当前异常更像数量不守恒、事务身份错配，还是单纯数据值错误。
4. 当前问题是否已经有成功案例可参考。
5. 本轮调试证据会保存在独立目录，而不是覆盖上一轮日志/波形。
6. 当前运行到底是哪种后端：未 `source env.sh` 的宿主机 OpenCL/NVIDIA、默认 `Spike`、`gvm`，还是 `rtl`。

## 2. 一轮标准流程

1. 先确认是不是 DCachev2 主路径问题。
2. 先跑定向 case，不要直接全量回归。
3. 先从日志找“首个真实异常点”，再开波形。
4. 只抓最小信号集，先判断是哪一段丢了事务身份。
5. 有了明确时序假设后，再改 RTL。
6. 先验证是否越过旧故障点，再验证最终 PASS。
7. 成功后同步更新案例库、硬件改动账本和自我迭代文档。

## 3. 首轮必须拿到的事实

- 首个真实异常发生的时间戳或 retire 点
- 对应的 `pc`
- 对应的 `instrId`
- 至少一个身份字段：
  - `a_source/d_source`
  - `blockAddr`
  - `activeMask`

如果这 4 项还不清楚，不要改代码。

## 4. 推荐命令顺序

所有 build / case run / 日志过滤 / regression 命令，都先在同一个 shell 里执行：

```bash
source /home/sunhn/ventus-env/env.sh
```

如果这一步漏掉了，程序通常会直接走宿主机 OpenCL/NVIDIA GPU 路径；这类 `run_console.log` 不能拿来当 Ventus 调试证据。

需要重编译 GVM 时：

```bash
cd /home/sunhn/ventus-env/gpgpu
bash build-ventus.sh --build gvm
```

除非 `sim-verilator/gvm.mk` 有非常明确且可复现的脚本错误，否则不要直接修改 `gvm.mk`。常规调试应优先通过：

- 正确 `source env.sh`
- 显式指定 `VENTUS_BACKEND`
- 正常重编 `gvm`

来获得可信的运行结果。

运行定向 case 时，优先使用独立目录保存工件：

```bash
cd /home/sunhn/ventus-env/rodinia/opencl/<case>
mkdir -p debug-artifacts/<case>/<tag>
VENTUS_BACKEND=gvm VENTUS_WAVEFORM=1 <case command> \
  2>&1 | tee debug-artifacts/<case>/<tag>/run_console.log
```

过滤日志：

```bash
python3 /home/sunhn/ventus-env/gpgpu/sim-verilator/gvm-log-script.py \
  --input debug-artifacts/<case>/<tag>/run_console.log \
  --output debug-artifacts/<case>/<tag>/gvm_filter.log
```

## 4.1 执行后端矩阵

默认要按下面顺序理解和使用执行后端：

1. 未 `source /home/sunhn/ventus-env/env.sh`
  - 实际执行在宿主机 OpenCL/NVIDIA GPU 上
  - 不能当 Ventus `Spike/gvm/rtl` 的调试依据
2. 已 `source /home/sunhn/ventus-env/env.sh`，但未设置 `VENTUS_BACKEND`
  - 默认是 `Spike`
  - 适合先做基础功能核对
3. `VENTUS_BACKEND=gvm`
  - 这是第一轮 debug 的主后端
  - 一般用它先生成 `run_console.log` 和 `waveform.gvm.fst`
  - 定位、修改、重编 `gvm`，直到 `gvm` 后端先通过
4. `VENTUS_BACKEND=rtl`
  - `gvm` 通过后，必须再跑 `rtl`
  - 最终不能只报 `gvm` PASS，必须确认 `rtl` 也通过

只有 targeted case 已通过后，才执行：

```bash
cd /home/sunhn/ventus-env/gpgpu
python3 regression-test.py
```

## 5. 首轮日志分析规则

- 先区分 benign mismatch 和真正传播的第一个异常。
- 优先定位：
  - 第一次不再 retire 的点
  - 第一次数据明显来自错误 cacheline 的点
  - 第一次同一条指令 completion 次数不对的点
- 如果表面现象是 LSU / `Coalscer.currentMask` / `activeMask` 清不掉，不要先假设 bug 在 LSU；DCache coreRsp 不带地址，很多更前面的身份错配最后都会表现成 LSU 等不到 completion。

如果日志只能告诉你“结果错了”，就必须转波形，不要继续在日志里猜。

## 6. 首轮波形分析规则

### 先回答“哪一段错了”

对 read miss / memRsp 类问题，先做三段计数：

1. `memRsp.fire` 次数对不对
2. `MSHRMissRspOut.fire` 次数对不对
3. `coreRsp.fire` 次数对不对

只要这三段中有一段数量不守恒，根因范围就能明显收窄。

### 再回答“是不是同一笔事务”

对每一拍同时看：

- line 身份：`tag/setIdx/blockAddr`
- miss 身份：`a_source/d_source`
- core 指令身份：`instrId`
- 子集合身份：`activeMask`

不要只看地址值本身。

## 7. RTL 修改规则

- 一次只改一类根因。
- 优先修“握手/身份绑定”，再修“功能策略”。
- 不要用“多插一拍”代替“把事务原子绑定”。
- 如果修法依赖“这次刚好差 1 cycle”，基本上不是最终修法。
- 对带 backpressure 的路径，必须确认 `valid/ready/fire` 语义与“实际推进/实际采样”一致。

## 8. 常见非 DCache 误判点

- `nn64K` 里若首先看到 `x7/x8/x2` 一类地址寄存器相对 REF 整体偏移 `+0x1000`，优先不要当成 DCache 根因。
- 已确认第一条传播性偏差常出现在 `pc=0x80000020` 的 `csrr ..., 0x806`：
  - RTL 返回当前实际分配到的 LDS slot 基址
  - GVM/Spike REF 当前仍按固定 `0x70000000` 做比对
- 如果 block/warp 生命周期已经停在 kernel 启动序言，优先先排 CTA / warp done bookkeeping，不要先回到 DCache datapath。

## 9. 复测顺序

1. 编译通过
2. `gvm` 定向 case 复测
3. 检查是否越过旧错误点
4. 检查是否出现新的首个错误点
5. 目标 case 在 `gvm` 下完整 PASS
6. 目标 case 在 `rtl` 下完整 PASS
7. 相邻 case 或小回归
8. 全量回归

## 10. 成功 debug 的收尾动作

每次成功 debug 后必须执行：

1. 用 `templates/success_case_template.md` 更新 `07_success_casebook.md`
2. 用 `templates/hardware_change_template.md` 更新 `08_hardware_change_log.md`
3. 判断是否出现新的可推广规则：
  - 若有，更新 `06_self_iteration_rules.md` 的规则和迭代记录
  - 若无，在本轮成功案例中显式写明“无新增可推广规则”
