# DCachev2 Waveform Checklist

## 1. 通用原则

- 每次只抓足够回答一个判断的问题，不要先把全设计所有信号都拉出来。
- 必看 `valid/ready/fire`，只看 `valid` 不够。
- 对 `coreRsp` 一类不带地址的通路，必须额外抓身份字段。

## 2. 通用最小信号集

### CoreReq 入口

- `io.coreReq.(valid,ready,fire)`
- `io.coreReq.bits.(instrId,opcode,param,tag,setIdx)`
- `io.coreReq.bits.perLaneAddr(i).(activeMask,blockOffset)`

### MemReq 下发

- `io.memReq.get.(valid,ready,fire)`
- `io.memReq.get.bits.(a_addr,a_source,a_opcode,a_param)`

### MemRsp 接收

- `memRspPipe.io.memRsp.(valid,ready,fire)`
- `memRspPipe.io.memRsp.bits.(d_addr,d_source,d_opcode,d_param)`

### CoreRsp 返回

- `io.coreRsp.(valid,ready,fire)`
- `io.coreRsp.bits.(instrId,isWrite,activeMask)`

## 3. read-hit 被 stall 后读到错误 line

目标：判断 `st1` 命中的旧 line，是否在推进到 `st2` 前被 refill 写坏。

最小信号：

- `CoreReq_pipeReg_st0_st1.deq.(valid,ready,fire)`
- `ReadHit_st1`
- `io.read_Req_dA.(valid,bits)`
- `io.dA_data`
- `CoreRsp_pipeReg_st1_st2.enq/deq`
- refill 对 DataAccess 的写请求

应核对的关系：

- `st1` 首次命中时，读出来的是哪条 line
- stall 期间 DataAccess 同一个 `set+way` 是否被写入新 line
- `st2` 最终消费的是 live `io.dA_data` 还是 snapshot

一旦看到：

- offset 对
- `io.dA_data` 属于另一条 line

就优先怀疑事务快照缺失，而不是 lane 取词逻辑。

## 4. memRsp metadata/data 错位

目标：判断 `coreRsp` 使用的 metadata 和 data 是否属于同一笔 miss。

最小信号：

- `memRspPipe.io.memRsp.(valid,ready,fire)`
- `memRspPipe.io.memRsp.bits.(d_addr,d_source,d_data)`
- `io.MSHRMissRspOut.(valid,ready,fire)`
- `io.MSHRMissRspOut.bits.(instrId,blockAddr)`
- `io.memRsp_coreRsp.(valid,ready,fire)`
- `io.memRsp_coreRsp.bits.(instrId,Rsp.data,activeMask)`

应核对的关系：

- `MSHRMissRspOut` 第一次有效时，对应 line data 是否已经准备好
- 同拍返回的数据到底来自 live memRsp 还是暂存寄存器
- `instrId` 和 line data 的 cacheline 身份是否一致

常见错误特征：

- 当前 subentry 的 offset/activeMask 是对的
- 但 `Rsp.data` 来自另一条 line

## 5. 同一条指令 memRsp 数量对，coreRsp 数量少

目标：判断是 `MemRspPipe` 少吐了、还是 `CoreReqPipe` 吃了没入队。

最小信号：

- `memRspPipe.io.memRsp.fire`
- `io.MSHRMissRspOut.fire`
- `coreReqPipe.io.memRsp_coreRsp.fire`
- `coreReqPipe.CoreRsp_pipeReg_st1_st2.enq.valid`
- `coreReqPipe.CoreRsp_pipeReg_st1_st2.enq.bits.validFromCoreReq`

判定：

- `memRsp.fire` 数量对，但 `MSHRMissRspOut.fire` 不对：优先查 `MemRspPipe/MSHR`
- `MSHRMissRspOut.fire` 对，但 `coreRsp.fire` 少：优先查 `CoreReqPipe` 入队仲裁
- `memRsp_coreRsp.fire=1` 但同拍 `validFromCoreReq=1`：基本就是被同拍 coreReq 路径覆盖

## 6. dirty replace 窗口异常 hit / fill 丢失

最小信号：

- victim `setIdx/way`
- `needReplace_pulse`
- replace-lock 相关信号
- `allocateWriteTagSRAMWValid_st1`
- DataAccess/tag fill 写请求
- 同窗口内的 hit 判定

应核对的关系：

- victim line 在 fill commit 前是否仍可命中
- `MemRsp_pipeReg_st0_st1.deq.fire` 与 fill 写入是否同拍

## 7. 波形记录要求

每轮最少记录：

- 首个异常时间戳
- 相关 `pc`
- `instrId`
- 至少一个 line 身份字段
- 至少一条 `valid/ready/fire` 结论

推荐直接按 `templates/waveform_capture_template.md` 填。
