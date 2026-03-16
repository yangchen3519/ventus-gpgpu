# DCache 成功 Debug 案例库

本文件只记录已经形成证据闭环、并且目标问题已成功修复的案例。

每条案例保留 5 件事：

- 现象
- 最小证据
- 根因
- 修改逻辑
- 可复用启发

## 1. `memRsp_coreRsp` 同拍被消费但没有真正入队

### 现象

- 同一条指令返回 4 个 `memRsp` 地址，但只看到 3 个 `coreRsp`
- 波形里出现 `coreReqPipe.io.memRsp_coreRsp.fire=1`，但同拍 `CoreRsp_pipeReg_st1_st2` 实际入队的是来自 coreReq 的 response

### 最小证据

- `memRspPipe.io.memRsp.fire`
- `io.MSHRMissRspOut.fire`
- `coreReqPipe.io.memRsp_coreRsp.fire`
- `coreReqPipe.CoreRsp_pipeReg_st1_st2.enq.bits.validFromCoreReq`

### 根因

- `io.memRsp_coreRsp.ready` 的语义和“实际能否进入 `CoreRsp_pipeReg_st1_st2`”不一致
- 导致 memRsp 路径被 ready 消费，但同拍真正占住 enq 的是 coreReq 路径

### 修改逻辑

- 让 `io.memRsp_coreRsp.ready` 与 `CoreRsp_pipeReg_st1_st2` 的真实可入队条件严格对齐
- coreReq 路径占用 enq 时，显式拉低 memRsp 路径 ready

### 可复用启发

- 任何 Decoupled 路径，只要 `ready` 不再等价于“真的会推进”，就很容易出现“被吃掉但没入队”的伪握手

## 2. dirty replace 窗口 victim line 仍可 hit，且 fill 写入缺失

### 现象

- dirty victim 的 replace memReq 已经发出，但随后对 victim line 仍能出现 write hit
- 同时 `MemRsp_pipeReg_st0_st1` 出队后，没有对应的 tag/data fill 写入

### 最小证据

- `TagAccess.io.needReplace`
- `memRspPipe.io.(dAReplace_rReq_valid, dAmemRsp_wReq_valid)`
- victim way 的 hit 判定
- fill 写请求

### 根因

- replace 期间 victim line 没有被逻辑锁住
- fill 写入使能只覆盖 `idle && st1_valid && st1_ready`，没覆盖真实 replace 提交点

### 修改逻辑

- 在 `L1TagAccess` 引入 replace-lock，fill commit 前屏蔽 victim line 命中
- 让 fill 写入与真正的 memRsp 出队/replace 提交同拍

### 可复用启发

- replace 问题要同时看“line 生命周期语义”和“提交时序”，不能只看其中一边

## 3. `MemRspPipe` 反压修复引入 ready 回环和 MSHR 自锁

### 现象

- 为了让 st0 副作用只在 `memRsp.fire` 时发生，修改后出现：
  - `firtool` 报组合回环
  - `MSHRMissRsp.ready` 长低，`SecondaryFull` 自锁

### 最小证据

- `coreReqPipe.io.memRsp_coreRsp.ready`
- `memRspPipe.io.MSHRMissRsp.valid/ready`
- `mshrStatus_st1_w`

### 根因

- 把会反馈到 ready 网络的 valid 信号改成 `memRsp.fire` 驱动，打破了原本依赖 `valid` 推进 subentry 的语义

### 修改逻辑

- 保留 MSHR 侧需要的 `valid` 驱动语义
- 避免用 `memRsp.fire` 直接驱动会反馈到 `ready` 网络的 valid

### 可复用启发

- 不要把“副作用只在 fire 时发生”和“上游必须看到 valid 才能释放内部状态”混为一谈

## 4. `RTAB_full` 时外部未握手，但 `CoreReqArb` 内部重复消费请求

### 现象

- `io.coreReq.fire=0`，但 `CoreReqArb.io.in(1).fire=1`
- 同一条 coreReq 被内部重复注入

### 最小证据

- `ReplayTable.io.RTAB_full`
- `io.coreReq.(valid,ready,fire)`
- `CoreReqArb.io.in(1).(valid,ready,fire)`

### 根因

- 外部 `ready` 被 `RTAB_full` gate 了，但内部仲裁输入 `valid` 没有同步 gate

### 修改逻辑

- 对 `CoreReqArb.io.in(1).valid` 和 `io.coreReq.ready` 使用同一条件 gate

### 可复用启发

- 对外 Decoupled 接口做额外 gate 时，必须同步作用到内部真正消费这条请求的那一侧

## 5. split read miss 的 `a_source` 在 `DCachev2` 输出级串线

### 现象

- 跨两个 cacheline 的 split read miss 发出时：
  - 地址不同
  - 但 `a_source` 被后一条请求覆盖
- 下游 completion 回错身份，表现成 activeMask 清不完或 half-completion 卡死

### 最小证据

- `io.memReq.get.bits.(a_addr,a_source)`
- 同一组 split miss 的发出顺序

### 根因

- `a_addr` 经过单独 staging，但 `a_source` 仍复用 live 的 `memReq_st3.a_source`

### 修改逻辑

- 为 `a_source` 增加与 `a_addr` 同级的独立锁存
- 让顶层输出的 `a_addr/a_source` 来自同一笔 dequeued request

### 可复用启发

- 任何“地址和身份分开走”的输出级，都是 split 请求最容易串线的地方

## 6. `gaussian`：`st1` read-hit 在 stall 期间读到 refill 后的新 line

### 现象

- `st1` 命中的请求因为 `memRsp`/replace 顶住不能前进
- stall 期间 DataAccess 被 refill 写入新 line
- `st2` 最终返回的是“当前事务 offset + 新 line 数据”

### 最小证据

- `ReadHit_st1`
- `io.read_Req_dA`
- `io.dA_data`
- `CoreRsp_pipeReg_st1_st2`
- refill 对 DataAccess 的写请求

### 根因

- 命中事务的数据没有跨 `st1 -> st2` 绑定，`st2` 直接回头消费 live `io.dA_data`

### 修改逻辑

- 对被 stall 的 read-hit 增加 snapshot
- snapshot 随事务进入 `st2`

### 可复用启发

- 当现象是“offset 对、line 错”时，优先怀疑 live data 没和事务一起推进

## 7. `gaussian`：miss completion 的 metadata/data 未原子绑定

### 现象

- 当前 `instrId/activeMask/offset` 是对的
- 但 `coreRsp` 的 `Rsp.data` 来自另一条 cacheline

### 最小证据

- `io.MSHRMissRspOut.(valid,ready,fire)`
- `io.memRsp.bits.d_data`
- `io.memRsp_coreRsp.bits.(instrId,Rsp.data,activeMask)`

### 根因

- completion metadata 来自 `MSHRMissRspOut`
- data 来自另一条 staging 路径
- backpressure 下两者没有被原子绑定

### 修改逻辑

- regular read data 按 `instrId -> cacheline` 保存
- `MSHRMissRspOut` 首拍有效时支持同拍 live-data bypass
- `coreRsp` 使用的是“已证明属于当前事务”的 line data

### 可复用启发

- 只要 metadata/data 的来源不同，就不能默认它们天然属于同一笔 completion
