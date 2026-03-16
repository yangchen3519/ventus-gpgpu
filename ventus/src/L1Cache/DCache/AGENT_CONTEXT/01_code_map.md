# DCachev2 代码地图

## 1. 当前主路径文件

优先只看下面这些文件：

- `ventus/src/L1Cache/DCache/DCachev2.scala`
- `ventus/src/L1Cache/DCache/CoreReqPipe.scala`
- `ventus/src/L1Cache/DCache/MemRspPipe.scala`
- `ventus/src/L1Cache/L1MSHR.scala`
- `ventus/src/L1Cache/DCache/L1RTAB.scala`
- `ventus/src/L1Cache/DCache/DCacheWSHR.scala`
- `ventus/src/L1Cache/L1TagAccess.scala`

默认不要把 `ventus/src/L1Cache/DCache/DCache.scala` 当成当前 RTL 的修改入口。

## 2. 各模块职责边界

### `DCachev2.scala`

- DCache 顶层接线
- `CoreReqPipe` / `MemRspPipe` / `MSHR` / `WSHR` / `RTAB` 之间的连接
- 对外 `coreReq/coreRsp` 与 `memReq/memRsp` 接口

适合先看：

- 一个信号从哪来、到哪去
- `io.memRsp_coreRsp` / `io.memReq_coreRsp` / `io.read_Req_dA` 这类跨模块通路

### `CoreReqPipe.scala`

- 处理 core 发来的 load/store/flush 请求
- 管理 tag/data 读、命中判定、miss 请求生成、命中 coreRsp 返回
- 是“命中路径”和“read-hit 被 stall 后仍需保持事务一致性”的第一落点

优先怀疑它的场景：

- 命中请求返回错 line
- `coreRsp` 和同拍 `memRsp`/`memReq` 仲裁冲突
- `st1/st2` stall 后数据身份丢失

### `MemRspPipe.scala`

- 处理 L2 回包
- 驱动 fill、replace、writeback 后续动作
- 向 core 生成 miss completion 的 response

优先怀疑它的场景：

- `memRsp` 已收到，但 `coreRsp` 少返回/错返回
- metadata 和 data 似乎不是同一笔事务
- dirty replace/fill 时序不对

### `L1MSHR.scala`

- 管理 read miss entry 与 subentry
- 合并 miss
- miss request / miss response 的身份跟踪

优先怀疑它的场景：

- `missRsp.ready` 长低
- secondary full / subentry 弹不掉
- 同一条 miss 的 targetInfo 数量不对

### `DCacheWSHR.scala`

- write miss / writeback 类事务跟踪

优先怀疑它的场景：

- write miss completion 的 `activeMask` 异常
- writeback / flush 回包和 core 完成数量对不上

### `L1RTAB.scala`

- 维护某些返回表项/地址相关辅助状态

适合在 source/entry 映射异常时一并核对，但通常不是第一嫌疑点。

### `L1TagAccess.scala`

- tag / valid / dirty / victim 选择
- replace-lock 一类“line 生命周期控制”问题的根入口

优先怀疑它的场景：

- victim line 在 replace 窗口仍然可 hit
- tag compare 看起来对，但语义上不该命中

## 3. 用问题类型反推入口

### A. load hit 返回了“对的 offset，错的 line”

先看：

- `CoreReqPipe.scala`
- `L1TagAccess.scala`
- `MemRspPipe.scala`

典型信号：

- `read_Req_dA`
- `io.dA_data`
- `CoreRsp_pipeReg_st1_st2`
- refill 对 DataAccess 的写请求

### B. memRsp 地址都回来了，但 coreRsp 次数少/内容不对

先看：

- `MemRspPipe.scala`
- `CoreReqPipe.scala`
- `L1MSHR.scala`

典型信号：

- `memRsp.fire`
- `MSHRMissRspOut.fire`
- `memRsp_coreRsp.fire`
- `CoreRsp_pipeReg_st1_st2.enq`

### C. write miss/flush 完成后 LSU 迟迟不清 mask

先看：

- `DCacheWSHR.scala`
- `CoreReqPipe.scala`
- `MemRspPipe.scala`

### D. dirty replace 窗口出现本不该发生的 hit / fill 没写入

先看：

- `L1TagAccess.scala`
- `MemRspPipe.scala`

## 4. 不要再踩的错误入口

- 看到 `coreRsp` 不带地址，不要直接在 LSU 里猜。
- 看到向量指令跨两个 cacheline，不要先怀疑 lane 重排；先确认 LSU 是否已经 split。
- 看到 `x29` 或 `lds` 基址偏差，不要第一时间回到 DCachev2；先排除 `0x806` CSR / reference 语义问题。
