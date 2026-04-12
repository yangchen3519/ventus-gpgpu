# 乘影 GPGPU 性能计数器（PMU）整体方案（全局优先版）

> 状态：**已实现：A/B/C + L1D Cache 计数 + per-kernel 启停/清零/汇总**；**待实现：D/E/F/G/H/I**  
> 最近更新：2026-04-12  
> 代码基线：`ventus/src/`（Chisel）

---

## 1. 全局目标与约束

### 1.1 目标

PMU 目标不是“多加计数器”，而是形成一套可闭环分析链路：

1. **先看吞吐**（A：active/issue）
2. **再看停顿归因**（B）
3. **再看负载结构**（C）
4. **最后定位专项瓶颈**（D~I：SIMT / ICache / LSU / Warp / L2 / OperandCollector）

### 1.2 全局统计边界

- 统计粒度：**per-SM 采集，top 层聚合**
- 生命周期：复用 `GPGPU_top` 现有 `perfStartPulse/perfEnable/perfReset/perfDump` 机制
- 输出粒度：**按 kernel 输出**（每次 kernel 开始清零，dump 时打印）

### 1.3 已实现基线（当前 RTL 事实）

- `pipeline/PerfCounters.scala`：
  - `PipelinePerfCounters`（A+B 主体字段）
  - `InstClassPerfCounters`（C）
- `pipeline/pipe.scala`：A/B/C 的寄存器更新逻辑已接入
- `top/GPGPU_top.scala`：已做多 SM 汇总、启停与 per-kernel 打印状态机
- `L1Cache/DCache/DCachev2.scala`：已有完整 L1D perf bundle（独立于 A/B/C）

---

## 2. 全局分组视图（先总览，后分部）

| 组 | 类型 | 当前状态 | 存在合理性（为何需要） | 核心挂载模块 |
|---|---|---|---|---|
| A | 计算 | 已实现 | 没有吞吐基线就无法判断后续任何优化是否有效 | `pipe` |
| B | 控制 | 已实现 | 给“为什么发不出去”做结构化归因 | `pipe` + `warp_scheduler`/`ibuffer`信号 |
| C | 计算 | 已实现 | 区分工作负载是算力瓶颈还是访存/控制瓶颈 | `pipe`（读取 `CtrlSigs`） |
| D | 计算 | 待实现 | GPU 独有瓶颈来自 SIMT 散度，不可由 A/B/C 替代 | `SIMT_STACK` + `issueV` + `warp_scheduler` |
| E | 内存 | 待实现 | 前端供给瓶颈（I$ miss）需与后端 stall 分离 | `InstructionCache` |
| F | 内存 | 待实现 | LSU 行为（load/store/atomic/shared/global）决定访存效率 | `LSU` |
| G | 计算 | 待实现 | 占用率是吞吐上限的硬约束（TLP 不足则 IPC 上不去） | `warp_scheduler` |
| H | 内存 | 待实现 | L2 是 L1D miss 后主瓶颈，不补齐会断链 | `L2cache/Scheduler` 等 |
| I | 计算 | 待实现 | OperandCollector/RF 冲突是向量核隐性瓶颈 | `operandCollector` |

---

## 3. 全局参数组织（按“计算/控制/内存”分组）

> 说明：当前 `parameters.scala` 已有 `PMU_PIPELINE`、`PMU_INST_CLASS`。下面是完整扩展建议，保持分类集中管理。

### 3.1 计算相关开关

```scala
val PMU_PIPELINE:   Boolean = true   // A（吞吐基础）
val PMU_INST_CLASS: Boolean = true   // C（指令分类）
val PMU_SIMT_DIV:   Boolean = true   // D（SIMT散度）
val PMU_WARP_OCC:   Boolean = true   // G（warp占用率）
val PMU_OC:         Boolean = false  // I（operand collector，默认关）
```

### 3.2 控制相关开关

```scala
val PMU_STALL_CTRL: Boolean = true   // B（控制/调度停顿细分，默认与PIPELINE同开）
```

### 3.3 内存相关开关

```scala
val PMU_DCACHE:   Boolean = true   // 现有 L1D perf（若已有固定导出可不显式加）
val PMU_ICACHE:   Boolean = true   // E
val PMU_LSU_EXT:  Boolean = true   // F
val PMU_L2:       Boolean = false  // H（改动面较大，默认关）
```

---

## 4. 分部方案（按“计算 / 控制 / 内存”分别展开）

## 4.1 计算相关

### A. 流水线吞吐（已实现）

**意义**：吞吐地基，直接提供 IPC 分母与分子。  
**字段**：`activeCycles`、`totalScalarIssued`、`totalVectorIssued`。

实现要点：
- `activeCycles`：`perfEnable` 周期累加
- 标量/向量发射：`issueX.io.in.fire`、`issueV.io.in.fire` 累加

衍生指标：
- `IPC = (totalScalarIssued + totalVectorIssued) / activeCycles`
- `scalarSlotUtil = totalScalarIssued / activeCycles`
- `vectorSlotUtil = totalVectorIssued / activeCycles`

---

### C. 指令分类（已实现）

**意义**：解释吞吐构成，判断是否“算力主导”。

**三类互斥定义**（基于 `CtrlSigs`）：
- `computeIssued`
- `memIssued`
- `ctrlIssued`

不变量：
```text
computeIssued + memIssued + ctrlIssued
= totalScalarIssued + totalVectorIssued
```

实现注意：
- 同周期可能 X/V 双发射，分类更新必须按“增量求和”写法，避免 last-write-wins 覆盖。

---

### D. SIMT 散度与线程效率（待实现，合理且必要）

**存在合理性**：A/B/C 无法回答“向量指令发出了，但多少线程在工作”。该组是 GPU 特有核心指标。  
**价值**：直接给出线程效率与分支散度程度。

建议字段：
- `totalActiveThreadSlots`
- `totalPossibleThreadSlots`
- `divergentBranchCount`
- `reconvergenceCount`
- `barrierWaitCycles`
- `barrierCount`

实现方案：
1. `issueV.fire && ctrl.isvec` 时累加 `PopCount(mask)` -> `totalActiveThreadSlots`
2. 同时累加 `num_thread` -> `totalPossibleThreadSlots`
3. `SIMT_STACK`：在分支处理点（`opcode===0` 且分支/掩码有效）检测 taken/fallthrough 同时非空 -> `divergentBranchCount`
4. `SIMT_STACK` pop/rejoin 路径统计 -> `reconvergenceCount`
5. `warp_scheduler` 的 barrier 位图（`warp_bar_data/warp_bar_cur/warp_bar_exp`）统计等待周期和完成次数

衍生指标：
- `threadEfficiency = totalActiveThreadSlots / totalPossibleThreadSlots`

---

### G. Warp 占用率（待实现，合理且必要）

**存在合理性**：即使 D 不散度，若活跃 warp 不足也会导致前端/执行单元喂不满。  
**价值**：解释“为何 IPC 达不到理论值”。

建议字段：
- `activeWarpCyclesSum`
- `warpLaunched` / `warpCompleted`
- `wgLaunched` / `wgCompleted`
- （可选）`warpScheduledCount(Vec)`

实现方案：
1. `warp_scheduler` 每周期 `PopCount(warp_active)` 累加 `activeWarpCyclesSum`
2. `warpReq.fire` / `warpRsp.fire` 统计 warp launch/complete
3. 新 WG 首 warp 发出时记 `wgLaunched`
4. 一组 WG 全部 endprg 并触发 flush 时记 `wgCompleted`

衍生指标：
- `avgActiveWarp = activeWarpCyclesSum / activeCycles`
- `occupancy = avgActiveWarp / num_warp`

---

### I. Operand Collector（待实现，方案合理）

**存在合理性**：RF bank/端口冲突会把 issue 侧表现成“似乎随机停顿”，需单独证据链。  
**价值**：区分“执行单元忙”与“操作数供应受限”。

建议字段：
- `ocStallCycles`
- `ocTotalReads`

实现方案：
1. 在 `operandCollector` 中定位“请求有效但读口/Bank 冲突无法前推”条件累计 `ocStallCycles`
2. 每次成功寄存器读累计 `ocTotalReads`

---

## 4.2 控制相关

### B. 流水线停顿细分（已实现主干，建议继续精化）

**意义**：把“发不出去”拆成可行动原因。

已定义字段：
- `execStructuralHazardCyclesX`
- `execStructuralHazardCyclesV`
- `dataDepStallCycles`
- `barrierStallCycles`
- `controlHazardFlushCount`
- `frontendStallCycles`
- `lsuBackpressureCycles`
- `ibufferFullCycles`

关键判定框架：
1. 槽级结构冒险：`valid && !ready`
2. 无输入时再做上游归因：data dependency / barrier / frontend
3. `controlHazardFlushCount` 记事件数，不与 stall cycle 强行混算

可验证关系（用于回归）：
```text
activeCycles ≈ issued + stall_breakdown
```
（允许由于槽级与周期级统计粒度差异出现边界差）

---

## 4.3 内存相关

### 现有基线：L1D（已实现）

`DCachev2` 已有：req/read/write/miss/replacement/mshrFull/bankConflict 等统计。  
该组已能支撑 L1D 层面分析，应保留为默认输出项。

---

### E. ICache 性能（待实现，方案合理）

**存在合理性**：B 中 frontend stall 只能看到“结果”，E 才能确认是不是 I$ 导致。  
**价值**：把前端停顿从“黑箱”变“可量化”。

建议字段：
- `icacheReqs`
- `icacheMisses`
- `icacheFetchedInsts`
- `icacheMSHRFullCycles`

实现方案：
1. 在 `ICache.scala` 增加 perf bundle 输出
2. `io.coreReq.fire` 记 `icacheReqs`
3. miss 分配到 MSHR 时记 `icacheMisses`
4. 根据 req mask 统计 `icacheFetchedInsts`
5. MSHR 满且有 miss 请求时计 `icacheMSHRFullCycles`

---

### F. LSU 访存行为（待实现，方案合理）

**存在合理性**：L1D miss 不等于访存模式差，必须在 LSU 分解 load/store/atomic/shared/global。  
**价值**：定位 coalescing 差、shared 利用低、atomic 过多等问题。

建议字段：
- `lsuTotalReqs`
- `lsuLoadReqs` / `lsuStoreReqs` / `lsuAtomicReqs`
- `lsuGlobalReqs` / `lsuSharedReqs`
- `lsuActiveThreadsSum`
- `lsuMSHRStallCycles`

实现方案：
1. 在 LSU 请求接收点（`lsu_req.fire` 对应路径）按 `mem_cmd/atomic` 分类
2. 用现有地址判定路径（`LDS_BASE` 区间）区分 shared/global
3. `PopCount(mask)` 累加到 `lsuActiveThreadsSum`
4. 以 `to_mshr`/dcache 反压条件计 `lsuMSHRStallCycles`

衍生指标：
- `coalescing ≈ lsuActiveThreadsSum / (lsuGlobalReqs * num_thread)`

---

### H. L2 性能（待实现，方案合理但优先级后置）

**存在合理性**：L1D/L2 是串联关系，不补 L2 会导致 miss 后路径不可见。  
**价值**：明确瓶颈在 L1、L2 还是更后端。

建议字段：
- `l2TotalReqs`
- `l2ReadReqs` / `l2WriteReqs`
- `l2Misses`
- `l2Writebacks`
- `l2MSHRFullCycles`

实现方案：
1. 在 `L2cache/Scheduler.scala`（必要时联动 `SinkA/SourceD/MSHR`）增加全局计数
2. `in_a.fire` 按 opcode 区分读写
3. tag miss 且向下游发请求计 `l2Misses`
4. 脏替换路径计 `l2Writebacks`
5. MSHR/请求缓冲塞满计 `l2MSHRFullCycles`

注意：L2 为共享资源，建议直接作为全局计数，不按 SM 求和。

---

## 5. 实施优先级（更新版）

### P0（已完成）
- A、B、C 与 per-kernel dump 基础链路

### P1（下一批，优先实现）
1. D（SIMT 散度）
2. E（ICache）
3. F（LSU 扩展）

### P2
4. G（Warp 占用率）
5. I（OperandCollector）

### P3
6. H（L2）

---

## 6. 文件落点（实施清单）

- `ventus/src/top/parameters.scala`：按“计算/控制/内存”补齐 PMU 开关
- `ventus/src/pipeline/PerfCounters.scala`：扩展 D/E/F/G/H/I 对应 Bundle
- `ventus/src/pipeline/pipe.scala`：继续聚合与导出 A/B/C + D/F
- `ventus/src/pipeline/SIMT_STACK.scala`：D 的散度/汇聚计数
- `ventus/src/pipeline/warp_schedule.scala`：G 与 barrier 相关计数
- `ventus/src/pipeline/operandCollector.scala`：I
- `ventus/src/L1Cache/ICache/ICache.scala`：E
- `ventus/src/L2cache/Scheduler.scala`（及关联模块）：H
- `ventus/src/top/GPGPU_top.scala`：顶层聚合与打印格式扩展

---

## 7. 输出规范（建议）

按 kernel 打印，顺序固定为：

1. 计算（A/C/D/G/I）
2. 控制（B）
3. 内存（L1D/E/F/H）

保证跨版本日志可直接 diff。

---

## 8. 结论

- **除 A/B/C 外的 D~I 方案均合理**，且分别覆盖了 A/B/C 不可替代的观测盲区。  
- 若只停在 A/B/C，可回答“慢”，但无法系统回答“慢在哪里、为什么慢、先优化哪一块”。  
- 推荐按 P1→P2→P3 渐进落地，优先打通 `SIMT + ICache + LSU` 三条高收益链路。
