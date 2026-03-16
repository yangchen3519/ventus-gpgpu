# DCache 文档系统自我迭代规则

## 1. 目的

这份文档规定：`AGENT_CONTEXT/` 不只是静态说明书，而是要根据每次**成功** debug 的经验持续迭代。

这里的“迭代”指的是：

- 新的最小证据链被沉淀成 checklist
- 新的误判点被沉淀成排雷规则
- 新的高价值修复模式被沉淀成方法论

## 2. 什么情况下允许迭代系统

只有满足下面 3 个条件，才允许把经验升级成系统规则：

1. 目标 case 已最终 PASS
2. 至少完成了最小验收
3. 根因和修复逻辑已经用波形/日志证据闭环

只“越过旧故障点”但还没最终通过，不算可沉淀成功经验。

## 3. 每次成功 debug 后的固定动作

必须按顺序执行：

1. 更新 `07_success_casebook.md`
2. 更新 `08_hardware_change_log.md`
3. 判断这次有没有新增的可推广规则
4. 若有，更新本文件和对应的入口文档：
  - `01_code_map.md`
  - `02_debug_playbook.md`
  - `03_waveform_checklist.md`
  - `04_acceptance_checklist.md`
  - `05_debug_methodology.md`

## 4. 什么经验应该升级成长期规则

- 能显著缩短定位路径的最小信号组合
- 能避免高频误判的排雷规则
- 对同类事务都成立的握手/身份绑定原则
- 能防止“临时修好、下次又踩”的验收规则
- 能降低后续误读 RTL 成本的注释规范

## 5. 什么经验不能升级成长期规则

- 只在单个 case/单个 commit 上成立的偶发现象
- 没有证据闭环的猜测
- 只是在当前设计上“刚好错开 1 拍”的临时绕法
- 已被后续事实推翻的中间判断

## 6. 规则更新时的写法要求

- 规则必须写成“以后怎么做”，不是“这次发生了什么”
- 规则必须指向至少一个落地位置：
  - 流程
  - 波形检查
  - 验收
  - 方法论
- 每次新增规则都要写明来源于哪次成功 debug

## 7. 迭代记录

### 2026-03-11 `gaussian/gvm`

- 新增规则：首轮分析必须至少做一次真实波形核对，不能只依赖过滤日志。
- 规则落点：
  - `02_debug_playbook.md`
  - `03_waveform_checklist.md`
- 原因：这次问题的最终根因都是“事务身份和数据未绑定”，只看日志可以收敛范围，但不足以形成短闭环。

### 2026-03-11 `gaussian/gvm`

- 新增规则：成功 debug 后必须同时维护“成功案例库”和“硬件改动账本”，不能继续把两者混在单一长日志里。
- 规则落点：
  - `DCachev2_AGENT_ENTRY.md`
  - `04_acceptance_checklist.md`
- 原因：后续检查硬件改动时，需要直接看到“改了什么”；后续复用定位经验时，需要直接看到“为什么这样改”。

### 2026-03-11 `gaussian/gvm`

- 新增规则：当现象表现为“offset 对、返回 line 错”时，优先检查 live data 是否和事务一起推进。
- 规则落点：
  - `03_waveform_checklist.md`
  - `05_debug_methodology.md`
- 原因：这次的 `CoreReqPipe` 与 `MemRspPipe` 两条问题，底层都属于 live data 与事务元信息解耦。

### 2026-03-11 `gaussian/gvm`

- 新增规则：新的 RTL 修改如果引入了非直观的握手、时序或事务绑定逻辑，必须在代码旁补充简洁中文注释。
- 规则落点：
  - `DCachev2_AGENT_ENTRY.md`
  - `04_acceptance_checklist.md`
- 原因：这类修复的正确性依赖底层协议语义，后续 debug 若只看到代码而没有中文注释，容易再次误判或回退到错误修法。

### 2026-03-12 `nn64K/backend-check`

- 新增规则：分析任何 `run_console.log` 或宣称某个 case 已通过之前，必须先确认是否已经 `source /home/sunhn/ventus-env/env.sh`，并明确记录当前后端是宿主机 OpenCL/NVIDIA、`Spike`、`gvm` 还是 `rtl`。
- 规则落点：
  - `DCachev2_AGENT_ENTRY.md`
  - `02_debug_playbook.md`
  - `04_acceptance_checklist.md`
  - `05_debug_methodology.md`
- 原因：如果运行路径落在宿主机 OpenCL/NVIDIA GPU，或者实际只跑了 `Spike`，就会把不属于 Ventus 的结果误当成 `gvm/rtl` 结论。

### 2026-03-12 `nn64K/gvm-build-discipline`

- 新增规则：除非 `sim-verilator/gvm.mk` 存在非常明确的脚本错误，否则不要把修改 `gvm.mk` 当成常规 debug 步骤。
- 规则落点：
  - `DCachev2_AGENT_ENTRY.md`
  - `02_debug_playbook.md`
  - `04_acceptance_checklist.md`
  - `05_debug_methodology.md`
- 原因：构建脚本改动很容易掩盖“后端跑错”或“验证路径不一致”的问题，导致错误地宣称 `gvm` 已通过。
