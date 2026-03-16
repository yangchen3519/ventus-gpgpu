# Ventus L1 Cache Context Summary

> 这份文档是 Ventus L1 cache 的全局背景源文档。  
> 若目标是调试 `ventus/src/L1Cache/DCache/DCachev2.scala` 这条主路径，请优先从 `ventus/src/L1Cache/DCache/AGENT_CONTEXT/DCachev2_AGENT_ENTRY.md` 进入；该目录负责提供 DCachev2 专用的代码地图、波形检查表、验收清单与案例入口。

这份文档面向“已经知道 Ventus 是一个 GPU，但不想再逐个翻 `ventus/src/L1Cache/*.scala`”的读者。

目标不是逐行解释实现，而是给出当前版本里 L1 cache / local memory 的真实结构、连接方式、关键状态机和文件地图，方便后续模型或开发者快速建立上下文。

## 1. 快速结论

- 每个 SM 当前真实接线里有 3 个本地存储模块：
  - `InstructionCache`
  - `DataCachev2`
  - `SharedMemory`
- 其中只有 `InstructionCache` 和 `DataCachev2` 走 L1 -> L2 路径；`SharedMemory` 完全是 SM 内本地 scratchpad。
- 当前顶层实际使用的是 `DataCachev2`，不是 `DCache.scala` 里的 `DataCache` 旧版实现。
- `L1MSHR.scala` 是当前 DCachev2 使用的 MSHR；`L1MSHRv2.scala` 目前不在主路径上。
- DCache 当前策略可概括为：
  - cached read miss: `read-allocate`
  - cached write hit: `write-back`
  - cached write miss: `no-write-allocate`，直接向下发 `PutPart`
  - uncached 访问: `bypass + 必要时 replay/evict`
- ICache 是独立实现，不和 DCache 共用 MSHR。
- MMU/TLB 接口已经预留，但 `top/parameters.scala` 当前默认 `MMU_ENABLED = false`。

## 2. 当前真实接线

当前真实接线在 `ventus/src/top/GPGPU_top.scala` 的 `SM_wrapper` 中。

### 2.1 SM 内连接

```text
pipe
  |- icache_req/rsp <-> InstructionCache
  |- dcache_req/rsp <-> DataCachev2
  |- shared_req/rsp <-> SharedMemory

InstructionCache --\
DataCachev2 ------- > L1Cache2L2Arbiter -> SM memReq/memRsp
SharedMemory ------/  (不经过这个仲裁器)
```

更完整一点：

```text
warp/pipeline
  -> pipe.scala
    -> InstructionCache
    -> DataCachev2
    -> SharedMemory

InstructionCache.memReq
DataCachev2.memReq
  -> L1Cache2L2Arbiter
  -> SM2clusterArbiter / l2Distribute / cluster2L2Arbiter
  -> AtomicUnit
  -> L2 cache
```

### 2.2 I/D cache 之间如何共享下行链路

`L1Cache2L2Arbiter` 负责两件事：

- 仲裁同一个 SM 里的 `ICache` 和 `DCache` 下行请求。
- 在 `a_source` 高位前缀里塞入 cache id，用于回包时再拆分回 ICache / DCache。

因此：

- `memRspVecOut(0)` 固定送回 ICache
- `memRspVecOut(1)` 固定送回 DCache

这个 arbiter 本身不理解 ICache/DCache 的内部语义，只做 source 编码和回包分发。

### 2.3 MMU/TLB 接法

如果启用 MMU：

- L1I、L1D 各自有一个 L1TLB
- cache miss 先发 TLB 请求，再用物理地址发 L1 -> L2 请求
- TLB 只包在 miss path 上，命中路径仍是本地 tag/data 访问

当前默认 `MMU_ENABLED = false`，所以大部分仿真里可以把 TLB 路径先忽略。

## 3. 关键几何参数

来自 `top/parameters.scala` 的当前默认值：

- `xLen = 32`
- `num_thread = 32`
- `num_lane = 16`
- `num_fetch = 2`

这里有一个必须单独强调的点：

- `top/parameters.scala` 里的执行宽度是 `num_lane = 16`
- 但 `ventus/src/L1Cache` 这棵子树大多数 bundle/参数用的是 `RVGParameters.NLanes = num_thread = 32`

所以在 L1 cache 语境里：

- `DCacheCoreReq/DCacheCoreRsp`
- `ShareMemCoreReq/ShareMemCoreRsp`
- `MSHR targetInfo`

这些“lane/thread 向量”的宽度通常都应按 `32` 去理解，而不是按 `16`。

### 3.1 ICache / DCache

- `NSets = 32`
- `NWays = 2`
- `BlockWords = 32`
- 每 word 4B
- cache line 大小 = `32 * 4B = 128B`
- cache 容量 = `32 sets * 2 ways * 128B = 8 KiB`

所以当前每个 SM 的 L1I 和 L1D 都是 `8 KiB / 2-way / 128B line`。

### 3.2 DCache 额外结构

- 向量 MSHR: `4 entries`
- 每个向量 MSHR entry 有 `2 subentries`
- WSHR: `4 entries`
- RTAB: `4 entries`

### 3.3 SharedMemory

- `sharedmem_depth = 1024`
- `BlockWords = 32`
- 总大小 = `1024 * 32 * 4B = 128 KiB`
- `NBanks = NLanes = 32`
- 每 bank 每 set 覆盖 `BankWords = 32 / 32 = 1 word`

## 4. 对外接口语义

### 4.1 DCacheCoreReq

`DCacheCoreReq` 的关键字段：

- `instrId`: 回包时给 LSU / pipeline 对应哪条访存指令
- `opcode` + `param`: 访问类型
- `tag` + `setIdx`: 地址高位已经由 LSU 算好
- `perLaneAddr[]`: 每 lane 的 `activeMask / blockOffset / wordOffset1H`
- `data[]`: 每 lane 写数据
- `asid`：仅 MMU 打开时存在

`opcode/param` 的当前语义由 `genControl` 定义：

| opcode | param | 含义 |
| --- | --- | --- |
| 0 | 0 | cached read |
| 0 | 1 | LR |
| 0 | 2 | uncached read |
| 1 | 0 | cached write |
| 1 | 1 | SC |
| 1 | 2 | uncached write |
| 2 | * | AMO |
| 3 | 0 | invalidate L1 + 下发 L2 flush/invalidate hint |
| 3 | 1 | flush L1 + 下发 L2 flush hint |
| 3 | 2 | 等待 MSHR/SMSHR drain |
| 3 | 3 | invalidate only L1 |
| 3 | 4 | flush only L1 |

AMO 的 `param` 再映射到 `add/xor/or/and/min/max/minu/maxu/swap`。

### 4.2 DCache mem source 编码

DCache 内部使用的 `a_source` 低位编码是：

- `type(3b) + entryIdx + setIdx`

当前主要约定：

- `type = 0`: 写 miss / 写回 / flush 之类经 WSHR 跟踪的请求
- `type = 1`: 普通 read miss，经向量 MSHR 跟踪
- `type = 2`: LR/SC/AMO 等 special miss，经 `SpecialMSHR` 跟踪

ICache 的 source 编码和 DCache 不同；它只需要让自己的 MSHR/warp id 能在回包时识别出来即可。SM 内仲裁器只关心“高位 cache id”。

## 5. ICache 微架构

ICache 的主模块是 `ICache/ICache.scala` 里的 `InstructionCache`。

### 5.1 组成

- `L1TagAccess_ICache`
  - tag SRAM
  - valid bits
  - 可选 ASID SRAM
  - 简单 round-robin replacement
- `dataAccess`
  - 每个 way 一条整行 SRAM，按 set 读
- `ICacheMSHR`
  - 合并多个 fetch miss
  - 负责决定何时真正向下发 miss

### 5.2 命中路径

取指请求在同一拍同时发起：

- tag read
- data read

随后：

- st1 做 tag/ASID compare
- 选中命中的 way
- 从整条 128B cache line 里抽出 `num_fetch` 条指令
- 在 `coreRsp` 上返回

ICache 的 `coreRsp.status` 非常重要：

- `00`: hit
- `01`: miss accepted
- `11`: miss but current cycle没能被 MSHR 接收
- `10`: 本次请求被 flush / order-violation 作废

也就是说，ICache miss 时并不是“等数据回来了再返回给前端”，而是先返回一个 miss 状态，让前端/warp 控制逻辑之后重试。

### 5.3 miss 路径

ICache miss 进入 `ICacheMSHR`：

- 相同 `blockAddr(+ASID)` 的 miss 会合并
- 只有 primary miss 才真的向下发读请求
- 回包后：
  - 根据 replacement 选择一个 victim way
  - 写 data SRAM
  - 写 tag/ASID
  - MSHR 释放对应 entry

### 5.4 flush / invalidate / order-violation

ICache 还有两套“让取指失效”的机制：

- `invalidate`: 全局清空 valid bits
- `externalFlushPipe`: 以 warp 为粒度取消正在路上的取指应答

另外，ICache 里有显式的 `OrderViolation` 逻辑：

- 如果同一个 warp 在前两拍里已经出现 miss
- 当前拍又继续用老 PC 发请求
- 那么当前这拍会被标成无效，要求上游重发

这个机制是为了解决取指 miss 与 warp 控制之间的因果性问题。

## 6. DCache 微架构

当前真实路径使用的是 `DCache/DCachev2.scala`。

### 6.1 总体结构

`DataCachev2` 不是单一流水线，而是“主访存流水 + 多个表项/回包辅助模块”的组合：

- `CoreReqPipe`
- `MemRspPipe`
- `L1TagAccess`
- `L1MSHR`（向量 read miss）
- `SpecialMSHR`（LR/SC/AMO）
- `DCacheWSHR`（in-flight writes）
- `L1RTAB`（replay table）
- 32 个按 word 切开的 data bank SRAM

### 6.2 cache 策略总结

当前 DCache 行为可以概括成：

- cached read hit: 直接返回
- cached read miss: 分配/合并到 MSHR，回包后 refill cache line
- cached write hit: 只写本地 data SRAM，置 dirty
- cached write miss: 不分配 cache line，直接向下发 `PutPart`
- uncached read/write:
  - 如果命中 clean line，可以当成 bypass 请求处理，并把本地 line 失效
  - 如果命中 dirty line，要先把 dirty victim 写回，再 replay 原始 uncached 请求
- flush/invalidate:
  - 通过 tag 阵列扫描 dirty line
  - 逐条写回 dirty line
  - 最后按请求类型做 invalidate 或 flush hint

因此它更接近：

- `read-allocate`
- `write-back on hit`
- `write-no-allocate on miss`

### 6.3 三段主流水

#### st0: probe / 冲突探测

`CoreReqPipe` 的 st0 同时做：

- probe tag array
- probe MSHR
- probe WSHR
- probe RTAB hit

这个阶段主要回答三个问题：

- 这是不是 cache hit？
- 如果 miss，应该新建 entry、合并 secondary miss，还是进 replay？
- 当前有没有 refill / release / dirty replace 等结构性冲突，必须先挡住新请求？

st0 还会挡掉几类危险情况：

- RTAB 满或快满
- `MemRspPipe` 正在做 dirty replacement
- 正在 refill 同一条 cache line
- MSHR 正在释放与本请求同一 block 的 entry

#### st1: 分类 / 发起 miss / 产生写 hit

st1 根据 tag hit、MSHR probe 状态、WSHR 命中、special conflict 等，把请求分成几类：

- 普通 read/write hit
- read miss
- write miss
- uncached hit clean / hit dirty
- LR/SC/AMO
- flush / invalidate
- 需要 replay 的请求

这个阶段会做几类动作：

- 对 read hit 发 data SRAM read
- 对 write hit 直接发 data SRAM byte write，并在 tag 里置 dirty
- 对 cached read miss 向 `MSHR` 注入 missReq
- 对 LR/SC/AMO 向 `SpecialMSHR` 注入 missReq
- 对需要 replay 的请求把原始 `DCacheCoreReq` 塞进 `RTAB`
- 对 write miss / evict / flush / invalidate 生成下行 `memReq`

#### st2/st3: coreRsp / memReq 发射

st2 负责把数据和元信息重新拼成 `DCacheCoreRsp`：

- hit 路径的数据来自 data SRAM
- refill / uncached read / special path 的数据来自 `MemRspPipe`
- cached write miss 的“完成应答”不等写 ack，而是在真正向下发出写请求时，经 `memReq_coreRsp` 旁路给 core

真正的 L1 -> L2 请求发射在 `DCachev2` 顶层的 `memReq_Q + st3 launch buffer`：

- `MemReqArb` 先合并两类请求：
  - 来自 `CoreReqPipe` 的 miss / evict / flush / invalidate
  - 来自 `MemRspPipe` 的 dirty replacement writeback
- 之后进入 `memReq_Q`
- 最后由单发射的 st3 launch buffer 往外发
- 如果启用 MMU，st3 里还要多过一层 TLB 请求/回包

### 6.4 `L1TagAccess` 在 DCache 中的职责

`L1TagAccess` 是 DCache 的 tag side 核心：

- tag SRAM
- valid bits
- dirty bits
- replacement metadata（按访问时间近似 LRU）
- dirty line 扫描
- per-line dirty byte mask

这里有两个重要点：

#### 1. replacement 策略

DCache 不像 ICache 用 round-robin，它维护 `timeAccess` SRAM，并选最小时间戳的 victim，属于“近似 LRU”。

#### 2. dirty byte mask 已经存在，但当前写回仍是整行

`L1TagAccess` 已经维护了每条 cache line 的 dirty byte mask，用来记录哪些字节被写脏。

但当前 `DataCachev2` 的实际写回请求仍然是：

- `PutFull`
- `a_mask = 全 1`

也就是说：

- dirty byte mask 基础设施已经在 tag 侧实现了
- 但 DCachev2 当前写回行为仍然是“整行写回”，没有真正做按 dirty byte 的部分写回

这点对后续改窗口模型或做 cache policy 修改时很重要，不要误以为“已有 dirty mask = 已经做部分写回”。

### 6.5 MSHR / SpecialMSHR / WSHR / RTAB 分工

#### 向量 MSHR (`L1MSHR.scala` 里的 `MSHR`)

只负责“普通 read miss”：

- `4 entries`
- 每 entry `2 subentries`
- 支持 secondary miss merge
- 能区分 cached read miss 和 uncached read miss

注意一个容易混淆的命名：

- `missCached_st1` 这个信号在当前 DCachev2 里传的是 `Control_st1.isUncached`
- 也就是名字看着像“cached”，实际语义更接近“这次 miss 是 uncached”

读代码时不要被这个名字误导。

#### SpecialMSHR

专门处理：

- LR
- SC
- AMO

这些请求默认走 uncached / special path，不参与普通 cached read miss 的 refill 合并。

#### WSHR

`DCacheWSHR` 只跟踪“已经向下发出、但还没收到写 ack”的写请求。

它的作用是：

- 防止后续 read/write 再访问同一个 block 时与未完成写请求冲突
- 在写 ack 回来时释放 entry

#### RTAB

`L1RTAB` 是 replay table，不是 miss storage。

它保存“本拍不能继续执行，但之后还应该重放”的原始 core request。

典型 replay 原因：

- MSHR entry 满
- secondary subentry 满
- WSHR 命中
- uncached 请求命中 dirty line，需要先写回 victim
- special path 冲突

RTAB 的 replay 请求在 `CoreReqArb` 中优先级高于新的外部 coreReq。

## 7. DCache 里的几条关键请求流

### 7.1 cached load miss

```text
coreReq
  -> st0 probe tag/MSHR
  -> st1 命中 PrimaryAvail/SecondaryAvail
  -> primary miss: 发 Get 到 L2；secondary miss: 只挂到 MSHR subentry
  -> memRsp 回来
  -> MemRspPipe 触发 tag allocate + data refill
  -> coreRsp 返回 lane 选择后的数据
```

### 7.2 cached store hit

```text
coreReq
  -> st1 write hit
  -> data SRAM byte write
  -> tag dirty 置位
  -> coreRsp 直接完成
```

### 7.3 cached store miss

```text
coreReq
  -> st1 write miss
  -> 生成 PutPart
  -> 发往下层并 push WSHR
  -> 请求真正发出时，经 memReq_coreRsp 旁路对 core 完成
  -> 写 ack 回来后 pop WSHR
```

这说明当前 cached store miss 不会分配本地 line。

### 7.4 uncached 请求命中 dirty line

```text
uncached req
  -> 发现本地 hit 且 dirty
  -> 先把本地 dirty line 写回
  -> 原始请求进 RTAB
  -> 写回 ack 后 replay 原始请求
```

### 7.5 flush / invalidate

```text
flush/invalidate req
  -> 等普通 miss 结构清空
  -> 如果还有 dirty line，TagAccess 逐条选出 dirty victim
  -> 对每条 dirty line 发 PutFull
  -> 如需要，再对 L2 发 flush/invalidate hint
  -> 最后给 core 返回完成应答
```

## 8. MemRspPipe 的真实作用

`MemRspPipe` 不是简单的“把回包送回 core”，它实际上做 4 件事：

- 按 `d_opcode + source type` 把回包分成：
  - cached read refill
  - write ack
  - special ack / data
  - flush/invalidate ack
- 驱动 tag allocate / data refill
- 如果 replacement 需要 victim writeback，先读出 victim data，再单独生成 dirty replace memReq
- 把应该回 core 的数据和 lane 信息重新整理成 `memRsp_coreRsp`

另外，`MemRspPipe` 会拉高 `blockCoreReq`，在 dirty replacement 期间临时阻止新 coreReq 进入主流水，避免 victim line 被写回过程中又发生 write hit 干扰数据一致性。

## 9. SharedMemory（LDS）不是 DCache 的一个模式

`ShareMem/SharedMemory.scala` 是独立模块，不是 DCache 的特殊 case。

它的特点：

- direct-mapped，本地 scratchpad
- 不走 L2，不参与 coherence
- 32 bank
- 一个 32-thread 请求会先按 lane 映射到 bank
- `BankConflictArbiter` 检测同拍多 lane 访问同一 bank 的冲突
- 有冲突时把请求拆成多拍依次执行

数据路径上：

- 写请求通过 `DataCrossbarForWrite` 送到对应 bank
- 读请求先从各 bank 读出，再经 `DataCrossbarForRead` 重新拼回 lane 顺序

因此 SharedMemory 的关键点不是 miss/replay，而是 bank conflict。

## 10. AtomicUnit 在整个上下文里的位置

`AtomicUnit/AtomicUnit.scala` 也在 `ventus/src/L1Cache` 目录下，但它不在 SM 内部。

它的位置是：

```text
SM/L1 caches -> cluster/l2 arb -> AtomicUnit -> L2 cache
```

它的职责是截获并整理：

- LR / SC
- AMO arithmetic / logic

大体机制：

- LR/SC 通过 reservation table 和 inflight write table 做成功性判断
- AMO 会先向 L2 发一个 `Get` 取旧值，再在 AtomicUnit 内部计算结果，再发 `PutPart`
- 对 L1 来说，它只是“下层总线上的一个原子语义适配层”

所以如果你只想理解 SM 内的 L1 行为，AtomicUnit 不是第一优先级；但如果你在看 LR/SC/AMO 的端到端路径，它必须纳入上下文。

## 11. 文件地图

### 11.1 当前主路径会用到的文件

#### 公共层

- `ventus/src/L1Cache/RVGParameter.scala`
  - RVG/L1 共用参数基类
- `ventus/src/L1Cache/L1CacheParameters.scala`
  - L1 通用参数、地址切分工具函数
- `ventus/src/L1Cache/L1Interfaces.scala`
  - DCache/下行 memReq/memRsp 的 bundle 定义
- `ventus/src/L1Cache/L1TagAccess.scala`
  - DCache 的 tag/dirty/replacement；同文件内也包含 ICache 版 tag access
- `ventus/src/L1Cache/L1Cache2L2Arbiter.scala`
  - SM 内 ICache/DCache 到 L2 的仲裁与 source 编码

#### ICache

- `ventus/src/L1Cache/ICache/ICache.scala`
  - `InstructionCache` 主体
- `ventus/src/L1Cache/ICache/ICacheMSHR.scala`
  - ICache miss merge / miss2mem
- `ventus/src/L1Cache/ICache/ICacheParameters.scala`
  - ICache 参数

#### DCache

- `ventus/src/L1Cache/DCache/DCachev2.scala`
  - 当前真实使用的 DCache 顶层
- `ventus/src/L1Cache/DCache/CoreReqPipe.scala`
  - coreReq 主流水
- `ventus/src/L1Cache/DCache/MemRspPipe.scala`
  - memRsp/refill/writeback 流水
- `ventus/src/L1Cache/L1MSHR.scala`
  - 向量 MSHR + SpecialMSHR
- `ventus/src/L1Cache/DCache/DCacheWSHR.scala`
  - write status holding register
- `ventus/src/L1Cache/DCache/L1RTAB.scala`
  - replay table
- `ventus/src/L1Cache/DCache/DCacheParameters.scala`
  - DCache 参数、TileLink opcode/param 常量

#### SharedMemory

- `ventus/src/L1Cache/ShareMem/ShareMem.scala`
  - SharedMemory 主体
- `ventus/src/L1Cache/ShareMem/BankConflictArbiter.scala`
  - bank conflict 检测与 lane/bank crossbar
- `ventus/src/L1Cache/ShareMem/ShareMemParameters.scala`
  - SharedMemory 参数

#### L1/L2 之间的原子路径

- `ventus/src/L1Cache/AtomicUnit/AtomicUnit.scala`
  - LR/SC/AMO 在 L1-L2 之间的适配层

### 11.2 目录里存在，但当前主路径不使用/不是重点的文件

- `ventus/src/L1Cache/DCache/DCache.scala`
  - 旧版 DCache 顶层，顶层当前不实例化
- `ventus/src/L1Cache/L1MSHRv2.scala`
  - 旧版/备选 MSHR 实现，当前主路径未接入
- `ventus/src/L1Cache/L1CacheTest.scala`
  - 本地测试封装

## 12. 容易混淆的点

- `pipeline/MSHR.scala` 不是 L1 cache 的 MSHR。
  - 它位于 `ventus/src/pipeline`
  - 用途是 LSU 侧把 DCache/SharedMemory 的 lane 响应重新聚合给 warp
- `top/parameters.num_lane` 和 `L1Cache.RVGParameters.NLanes` 不是同一个概念。
  - 前者当前是执行/算术宽度 `16`
  - 后者在 L1 子树里大多数情况下等于 `num_thread = 32`
  - 少数模块（例如 `L1TagAccess` 的部分输入）仍残留对 `num_lane` 的直接引用，命名不完全统一
- `SharedMemory` 不是 DCache 的一种访问模式。
  - 它是独立模块、独立端口、独立仲裁
- `L1TagAccess` 里已经有 dirty byte mask。
  - 但当前 `DataCachev2` 还没有真正按 dirty mask 做部分写回
- ICache miss 的返回不是“等 refill 完才把指令给前端”。
  - 它先返回 miss status，让前端之后重试

## 13. 如果后续还要继续看源码，建议的最小阅读顺序

1. `top/GPGPU_top.scala`
2. `L1Cache/L1Cache2L2Arbiter.scala`
3. `L1Cache/ICache/ICache.scala`
4. `L1Cache/DCache/DCachev2.scala`
5. `L1Cache/DCache/CoreReqPipe.scala`
6. `L1Cache/DCache/MemRspPipe.scala`
7. `L1Cache/L1TagAccess.scala`
8. `L1Cache/L1MSHR.scala`
9. `L1Cache/ShareMem/ShareMem.scala`
10. `L1Cache/AtomicUnit/AtomicUnit.scala`

如果只是为了建立“当前 cache 上下文”，读到第 6 步通常已经够了。
