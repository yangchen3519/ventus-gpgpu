# DCache 成功 Debug 后的硬件改动账本

本文件只记录已经验证成功的硬件改动。

每条记录关注：

- 改了哪些模块
- 底层修改逻辑是什么
- 风险面在哪里
- 用什么结果证明它有效

## 1. 2026-01-15 `CoreReqPipe`：修正 `memRsp_coreRsp.ready` 的真实入队语义

- 相关模块：
  - `CoreReqPipe.scala`
- 改动逻辑：
  - `io.memRsp_coreRsp.ready` 不再只表示“下游理论 ready”，而是严格对齐 `CoreRsp_pipeReg_st1_st2` 是否会被 memRsp 路径实际占用
- 风险面：
  - 同拍 coreReq/memRsp 仲裁
- 验证：
  - 原先“4 个 memRsp 只吐 3 个 coreRsp”的问题消失

## 2. 2026-01-17 `L1TagAccess + MemRspPipe`：为 dirty replace 增加 victim 保护和 fill 提交对齐

- 相关模块：
  - `L1TagAccess.scala`
  - `MemRspPipe.scala`
- 改动逻辑：
  - 引入 replace-lock，fill commit 前禁止 victim line 命中
  - fill 写入与真正的 replace 提交时机对齐
- 风险面：
  - replace 窗口的 store hit、fill 丢失
- 验证：
  - victim line 不再在 replace 窗口 write-hit
  - fill/tag write 时序恢复一致

## 3. 2026-01-17 `MemRspPipe`：保留 MSHR 所需的 valid 语义，避免 ready 回环自锁

- 相关模块：
  - `MemRspPipe.scala`
- 改动逻辑：
  - 避免用 `memRsp.fire` 驱动会反馈到 ready 网络的 valid
  - 保留 MSHR miss response 释放 subentry 所需的 `valid` 语义
- 风险面：
  - 组合回环
  - `SecondaryFull` 自锁
- 验证：
  - `top.GPGPU_gen` 编译通过
  - `MSHRMissRsp.ready` 不再长期卡死

## 4. 2026-01-20 `DCachev2`：对 `CoreReqArb` 输入做一致 gate，消除重复注入

- 相关模块：
  - `DCachev2.scala`
- 改动逻辑：
  - `RTAB_full`/`almost_full` 既 gate 外部 `ready`，也 gate 内部实际消费 `io.coreReq` 的仲裁输入 `valid`
- 风险面：
  - 同一条 coreReq 在外部未握手时被内部重复消费
- 验证：
  - `io.coreReq.fire` 与 `CoreReqArb.io.in(1).fire` 重新对齐

## 5. 2026-01-21 `L1MSHR + CoreReqPipe`：修正 releasing/late-merge 窗口

- 相关模块：
  - `L1MSHR.scala`
  - `CoreReqPipe.scala`
  - `DCachev2.scala`
- 改动逻辑：
  - 让 MSHR 显式输出 releasing block 身份
  - 同块 releasing 时暂停 coreReq 继续推进，防止 late secondary merge
- 风险面：
  - MSHR entry 释放窗口内 secondary merge 卡死
- 验证：
  - 同块 releasing/merge 场景不再形成 MSHR 卡死

## 6. 2026-03-08 `DCachev2`：把 `a_source` 和 `a_addr` 一起锁存

- 相关模块：
  - `DCachev2.scala`
- 改动逻辑：
  - 新增独立的 `memReq_st3_source`
  - 顶层输出 `a_source` 不再复用 live `memReq_st3.a_source`
- 风险面：
  - split miss 下地址/身份串线
- 验证：
  - 同一组 split read miss 发出时 `a_addr/a_source` 一一对应
  - `nn64K` 不再卡在原 activeMask half-completion 死点

## 7. 2026-03-11 `CoreReqPipe`：为 stalled read-hit 增加 snapshot

- 相关模块：
  - `CoreReqPipe.scala`
- 改动逻辑：
  - `st1` 捕获旧 cacheline 数据
  - snapshot 跟随事务进入 `st2`
  - `st2` 优先消费 snapshot，而不是 live `io.dA_data`
- 风险面：
  - refill 覆盖命中事务数据
- 验证：
  - `gaussian` 旧的早期错误窗口被消除

## 8. 2026-03-11 `MemRspPipe`：把 miss completion 的 data 与当前事务原子绑定

- 相关模块：
  - `MemRspPipe.scala`
- 改动逻辑：
  - regular read data 按 `instrId` 暂存
  - completion 首拍支持同拍 live-data bypass
  - `coreRsp` 只消费已证明属于当前事务的 line data
- 风险面：
  - completion metadata/data 错配
- 验证：
  - `gaussian` 最终结果匹配并 PASS
