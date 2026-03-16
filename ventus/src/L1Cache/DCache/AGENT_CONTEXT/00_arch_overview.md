# DCachev2 架构总览

## 1. 先记住 5 个结论

- 当前真实接线里，SM 侧的数据 cache 是 `DataCachev2`。
- `ventus/src/L1Cache/DCache/DCache.scala` 不是当前主调试落点。
- DCachev2 和 ICache 共享 L1 -> L2 下行链路，但内部结构独立。
- DCache 当前主策略是：
  - cached read miss: `read-allocate`
  - cached write hit: `write-back`
  - cached write miss: `no-write-allocate`
  - uncached: `bypass`
- DCache debug 最常见的根因不是“数据算错”，而是“事务身份和数据没有一路绑定”。

## 2. 当前真实接线

顶层实际接线在 `ventus/src/top/GPGPU_top.scala` 的 `SM_wrapper`。

```text
pipe
  |- icache_req/rsp <-> InstructionCache
  |- dcache_req/rsp <-> DataCachev2
  |- shared_req/rsp <-> SharedMemory

InstructionCache --\
DataCachev2 ------- > L1Cache2L2Arbiter -> SM memReq/memRsp
SharedMemory ------/  (不经过这个仲裁器)
```

`L1Cache2L2Arbiter` 只做两件事：

- 仲裁 ICache / DCache 下行请求
- 在 `a_source` 高位编码 cache id，回包时再拆回各自 cache

## 3. 关键几何参数

- `NSets = 32`
- `NWays = 2`
- `BlockWords = 32`
- 每 word 4B
- line size = `128B`
- 每个 SM 的 L1D 容量 = `8 KiB`

这里有一个容易误判的点：

- `top/parameters.scala` 的 `num_lane = 16`
- 但 L1 cache 子树里很多 lane/thread 向量按 `RVGParameters.NLanes = num_thread = 32` 理解

所以 DCache 调试里：

- `DCacheCoreReq/DCacheCoreRsp`
- MSHR targetInfo
- `perLaneAddr`

通常都按 32 个 thread/lane 语义读。

## 4. 对外接口里最重要的身份字段

### `DCacheCoreReq`

- `instrId`
- `opcode/param`
- `tag/setIdx`
- `perLaneAddr[].activeMask`
- `perLaneAddr[].blockOffset`

### L2 回包

- `d_addr`
- `d_source`
- `d_data`

### 调试时最关键的绑定关系

- `instrId`：同一条 core 指令的身份
- `a_source/d_source`：miss/回包和 MSHR/WSHR entry 的身份
- `blockAddr/tag+setIdx`：cacheline 身份
- `activeMask`：同一条向量指令的子集合身份

如果这 4 类身份没有两两对上，不要先怀疑算术或 lane 重排。

## 5. 当前 DCachev2 的主数据通路

### 命中路径

- `CoreReqPipe` 同拍发 tag/data 读
- `st1` 做命中判断和控制决策
- 命中后进入 `coreRsp` 路径返回

### read miss 路径

- `CoreReqPipe` 生成 miss memReq
- `L1MSHR` 记录 targetInfo / subentry
- `MemRspPipe` 接收回包
- `CoreReqPipe`/`MemRspPipe` 向 core 回应完成
- 同时执行 tag/data fill 与 replace 相关动作

### write miss / dirty replace 路径

- write miss 走 `WSHR`
- dirty victim replace 由 `MemRspPipe` 负责协调 DataAccess/tag 写回与 fill

## 6. 为什么要区分“事务身份错配”和“数值错误”

这次 `gaussian` 暴露的两个根因都属于前者：

- `st1` 的 read-hit 已经命中旧 line，但 `st2` 最终取到的是 refill 后的新 line
- `memRsp_coreRsp` 的 metadata 和 data 在 backpressure 下没有原子绑定，导致“当前 subentry 的元信息 + 另一笔 line 数据”

这类 bug 的典型现象是：

- offset 看起来对
- 返回的 word 数值也“像真的”
- 但 cacheline 基底或事务身份错了

所以第一优先级不是去看 ALU 或 lane remap，而是确认：

- 当前返回的数据到底属于哪条 line
- 当前 response 到底属于哪条 miss/coreReq
