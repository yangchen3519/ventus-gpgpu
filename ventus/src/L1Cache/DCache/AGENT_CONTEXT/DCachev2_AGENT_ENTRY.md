# Ventus DCache Agent Entry

这套文件面向“第一次接手 `DataCachev2` debug 的 Agent / 工程师”。

目标不是替代源码，而是把进入调试前必须知道的上下文、入口、流程、成功案例、硬件改动账本和自我迭代规则放在同一个目录下，避免再回到单一长日志里搜索历史。

## 推荐阅读顺序

1. `00_arch_overview.md`
2. `01_code_map.md`
3. `02_debug_playbook.md`
4. `03_waveform_checklist.md`
5. `04_acceptance_checklist.md`
6. `05_debug_methodology.md`
7. `06_self_iteration_rules.md`
8. `07_success_casebook.md`
9. `08_hardware_change_log.md`

## 每个文件负责回答什么问题

- `00_arch_overview.md`
  - 当前真实接线是什么
  - DCachev2 在整机里扮演什么角色
  - 哪些几何参数和接口语义最重要
- `01_code_map.md`
  - 某类 bug 应该先看哪个模块
  - 哪些文件是当前主路径，哪些不是
- `02_debug_playbook.md`
  - 调试一轮应按什么顺序做
  - 编译/运行/日志过滤/快速排雷规则是什么
- `03_waveform_checklist.md`
  - 每类问题最小应该抓哪些信号
  - 什么现象意味着事务身份错配
- `04_acceptance_checklist.md`
  - 修改后最低限度必须验证什么
  - 成功 debug 之后最低限度必须归档什么
- `05_debug_methodology.md`
  - 这次 `gaussian` 调试沉淀出的底层方法论是什么
- `06_self_iteration_rules.md`
  - 文档系统如何根据每次**成功** debug 自我迭代
  - 哪些经验能升级成长期规则，哪些不能
- `07_success_casebook.md`
  - 过去成功 debug 的案例库
  - 每条案例的最小证据、根因、修改逻辑和可复用启发
- `08_hardware_change_log.md`
  - 每次成功 debug 后，RTL 实际改了什么
  - 后续检查硬件改动时先看这里

## 执行后端约定

常见执行后端一共按下面 4 种情况区分，开始分析任何 `run_console.log` 之前，必须先确认自己属于哪一种：

1. 没有 `source /home/sunhn/ventus-env/env.sh`
  - 程序会落到宿主机的 OpenCL/NVIDIA GPU 路径
  - 这类日志不能当成 Ventus `Spike/GVM/RTL` 的调试证据
2. 已经 `source /home/sunhn/ventus-env/env.sh`，但没有显式指定后端
  - 默认按 `Spike` 后端执行
  - 适合做基础功能核对，但不适合作为 `gvm/rtl` 波形调试证据
3. `VENTUS_BACKEND=gvm`
  - 这是第一轮定位和修改时的主后端
  - 先用它生成 `log + wave`，定位问题、修改 RTL、重编 `gvm` 并跑通
4. `VENTUS_BACKEND=rtl`
  - `gvm` 跑通后，必须再用 `rtl` 后端通过
  - 有些问题只会在 `rtl` 后端出现，不能把 `gvm` PASS 当最终验收

## 强制约束

- 当前 DCache 主路径是 `DCachev2.scala`，不是 `DCache.scala`。
- 没有先读完 `01_code_map.md` 之前，不要直接改 RTL。
- 开始分析日志或波形之前，必须先记录本次运行是否已经 `source /home/sunhn/ventus-env/env.sh`，以及当前后端到底是 `NVIDIA/OpenCL`、`Spike`、`gvm` 还是 `rtl`。
- 首轮分析必须至少做一次真实波形核对，不能只看过滤日志。
- 新增 RTL 修改如果涉及非直观的握手、时序、事务绑定逻辑，必须补充简洁中文注释。
- 除非 `sim-verilator/gvm.mk` 存在非常明确的脚本错误，否则不要把修改 `gvm.mk` 当成常规 debug 手段。
- 每次**成功** debug 后，必须同时更新：
  - `07_success_casebook.md`
  - `08_hardware_change_log.md`
  - `06_self_iteration_rules.md` 中的“迭代记录”或对应规则

## 背景文档

- `ventus_l1cache_context.md`
  - 当前目录下的背景副本，供 DCache 调试时快速查看全局 cache 设计信息

## 已退役文档

- `cache_debug_log.md`
  - 旧系统里的单文件长日志入口已退役
  - 其中仍有长期价值的信息已拆分迁移到本目录各文件中
