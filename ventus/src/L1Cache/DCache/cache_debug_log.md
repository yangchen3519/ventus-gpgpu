# DCachev2 Debug Notes（本目录）

> 本文件专注记录 `ventus/src/L1Cache/DCache` 相关的调试入口、最小波形观测点与新增问题定位结果，避免每次全仓库搜索。

## 后续窗口快速接手流程（优先看）

- 先读完本文件前 4 个部分：本节、`编译与测试流程`、`重要约束`、`代码入口`，再开始跑 case；不要先凭记忆改 RTL。
- 先确认约束：`ventus/src/L1Cache/DCache/DCache.scala` 不是这轮 DCachev2 RTL debug 的修改落点，优先只看 `DCachev2.scala`、`CoreReqPipe.scala`、`MemRspPipe.scala`、`L1MSHR.scala`、`L1RTAB.scala`。
- 调试 `nn64K` 这类卡死问题时，优先先跑“当前坏版本”和“上一个不会在同一点卡死的版本”做对照，不要只盯坏版本单边分析。
- 先从 `gvm.log` 找 3 个锚点：最后一次 retire 前进点、最后一个关键地址、第一次出现 heartbeat-only 的周期；先把“停在哪里”说清楚，再开波形。
- 如果表面现象是 LSU / `Coalscer.currentMask` / `activeMask` 清不掉，不要先假设 bug 在 LSU。DCache coreRsp 本身不带地址，任何更前面的 request identity 错配，最后都可能表现成 LSU 等不到 completion。
- 顺着下面这条链只看最小信号即可，通常足够定位：
  - `CoreReqPipe`：`missMemReq_st1.(a_addr,a_source,activeMask,hasCoreRsp,coreRspInstrId)`
  - `DCachev2`：`MemReqArb -> memReq_Q.enq/deq -> io.memReq.(a_addr,a_source)`
  - `MSHR/WSHR`：按 `a_source` 分配/释放 entry
  - `MemRspPipe/CoreReqPipe`：`memRsp_coreRsp` 或 `memReq_coreRsp`
  - `LSU.Coalscer`：`from_dcache.activeMask` 和 `currentMask`
- 对 write miss，先核对 `memReq_coreRsp.activeMask` 是否真跟着请求一路带回；对 read miss，先核对 split miss 的 `a_addr` 和 `a_source` 是否一一对应。这两类问题外表都像 activeMask 死锁，但根因路径不同。
- 发现怀疑点后，优先做“最小波形对照”而不是大改：例如只比同一对地址在旧/新波形里的 `a_source`，或只比同一条 write miss completion 的 `activeMask`。
- 每次定向复测都在 case 目录下新开隔离子目录，保留 `run_console.log`、`gvm.log`、`waveform.fst`，避免覆盖上一轮证据。
- 修完后先验证“是否越过旧停点”，再看是否还有新错误；不要把“旧死锁已解”和“程序已完全正确”混为一谈。

## 编译与测试流程（基础指令版，参考 `skills/ventus-cache-debug/SKILL.md`）

- 所有 build / case run / 日志过滤 / regression 命令都必须在**同一个 shell** 先执行：

```bash
source ~/ventus-env/env.sh
```

- 需要重编译 GVM 时，在 `gpgpu` 根目录执行：

```bash
cd /home/sunhn/ventus-env/gpgpu
bash build-ventus.sh --build gvm
```

- 调试阶段优先跑定向 case，而不是一上来全量回归。每个 case 的可运行 level 都写在 `/home/sunhn/ventus-env/rodinia/opencl/<case>/run` 里；通常按从小到大排列，直接手工拷贝其中一条命令执行即可。

```bash
cd /home/sunhn/ventus-env/rodinia/opencl/nn
sed -n '1,120p' run
```

- 手工运行单个 case / 单个 level 时，显式带上 `VENTUS_BACKEND=gvm` 和 `VENTUS_WAVEFORM=1`，并把控制台输出保存成日志：

```bash
cd /home/sunhn/ventus-env/rodinia/opencl/nn
VENTUS_BACKEND=gvm VENTUS_WAVEFORM=1 \
./nn.out ../../data/nn/list64k.txt -r 20 -lat 30 -lng 90 -f ../../data/nn -t -p 0 -d 0 --ref nvidia-result-64k-lat30-lng90 \
2>&1 | tee nn_L03.log
```

- 其它 case 同理，直接把 `run` 文件中的目标命令原样拿出来执行。例如 `gaussian`：

```bash
cd /home/sunhn/ventus-env/rodinia/opencl/gaussian
VENTUS_BACKEND=gvm VENTUS_WAVEFORM=1 \
./gaussian.out -p 0 -d 0 -f ../../data/gaussian/matrix16.txt -v \
2>&1 | tee gaussian_L03.log
```

- 跑完后用最基础的日志过滤脚本生成过滤结果：

```bash
cd /home/sunhn/ventus-env/rodinia/opencl/nn
python3 /home/sunhn/ventus-env/gpgpu/sim-verilator/gvm-log-script.py \
  --input nn_L03.log \
  --output nn_L03_filter.log
```

- 如果只是复盘已有日志，不需要重新运行 case，直接对已有 `.log` 执行上面的过滤命令，然后结合 `waveform.gvm.fst`、`object0.dump`、`object0.riscv.log` 做定位即可。

- 只有在 targeted case 已通过后，才执行完整回归：

```bash
cd /home/sunhn/ventus-env/gpgpu
python3 regression-test.py
```

- 推荐顺序：`source env` -> 可选 `build gvm` -> 进入 case 目录查看 `run` -> 手工执行目标 level -> `gvm-log-script.py` 过滤日志 -> 定位并修复 -> 定向复测 -> `python3 regression-test.py` 全量回归。

## 重要约束（强制）

- 禁止在未征得用户明确同意的情况下执行任何会丢失本地改动的操作，包括但不限于：`git reset --hard`、`git reset HEAD`、`git checkout -- <file>`、`git restore --source=HEAD ...`、`git clean -fd` 等。
- 如需回退或切换方案，优先使用：最小范围的 `git diff`/手工补丁、`git stash push -u`（并说明恢复方式）、或在用户确认后再做 destructive 操作。
- `ventus/src/L1Cache/DCache/DCache.scala` 不加入当前 RTL 调试目标的数据通路，不允许作为本轮 DCachev2 debug 的修改落点；若 v2 需要新增类型或逻辑，优先放到独立文件，或落在 `DCachev2.scala` / `CoreReqPipe.scala` / `MemRspPipe.scala` 等实际编译路径中。

## 记录要求（强制）

- 每次 debug（复现/定位/修复/回归）都必须在本文件追加记录：现象（含关键周期/地址）、最小信号、根因、修改点、验证结果与残留风险。
- 时序现象建议用 `c0/c1/c2...` 的格式描述“随周期变化”，并在每个周期点列出关键 `valid/ready/fire` 与地址/ID，方便对齐波形与日志。
- 代码/文档新增或修改的注释一律使用中文（保留历史英文/协议原文不强制重写）。

## 代码入口（v2）

- 顶层：`ventus/src/L1Cache/DCache/DCachev2.scala`
- v2 专用类型：定义在 `ventus/src/L1Cache/DCache/DCachev2.scala` 文件头
- CoreReq 流水：`ventus/src/L1Cache/DCache/CoreReqPipe.scala`
- MemRsp/replace：`ventus/src/L1Cache/DCache/MemRspPipe.scala`
- MSHR/RTAB/WSHR：`ventus/src/L1Cache/L1MSHR.scala`、`ventus/src/L1Cache/DCache/L1RTAB.scala`、`ventus/src/L1Cache/DCache/DCacheWSHR.scala`
- TagAccess：`ventus/src/L1Cache/L1TagAccess.scala`

## 常用“最小信号”集合（先看这几个就够定位 80%）

**1) CoreReq 接收**

- `io.coreReq.(valid,ready,fire)`
- `io.coreReq.bits.(instrId,opcode,param,tag,setIdx)`
- `io.coreReq.bits.perLaneAddr(i).(activeMask,blockOffset,wordOffset1H)`（通常只看 `activeMask` + `blockOffset`）

**2) MemReq 发出**

- `io.memReq.get.(valid,ready,fire)`
- `io.memReq.get.bits.(a_opcode,a_param,a_addr,a_source)`（重点看 `a_addr/a_source`）

**3) MemRsp 被 cache 消费**

- `memRspPipe.io.memRsp.(valid,ready,fire)`
- `memRspPipe.io.memRsp.bits.(d_opcode,d_param,d_addr,d_source)`（重点看 `d_addr/d_source`）

**4) CoreRsp 吐回**

- `io.coreRsp.(valid,ready,fire)`
- `io.coreRsp.bits.(instrId,isWrite,activeMask)`（注意 coreRsp **不带地址**，必须用 activeMask/内部 blockAddr 关联）

## 新问题：同一条指令返回 4 个 memRsp 地址，但只吐出 3 个 coreRsp

现象描述（你的复现）：`c0/c2/c4/c6` 出现 4 个返回地址 `addr0~3`（同一条 `instrId`），但 `coreRsp` 只看到 `addr1~3` 对应的 3 次返回（`addr0` 对应返回缺失）。

### 用最少信号做“分段计数”定位丢失发生在哪一段

**A. L2->L1 的返回是否真的被 DCache 接收了 4 次？**

- 看 `memRspPipe.io.memRsp.fire` 发生次数（应为 4）
- 同时记录 `memRspPipe.io.memRsp.bits.(d_addr,d_source)`，确认 4 个地址都被“fire”消费（不是只 `valid`）

**B. MSHR 是否产生了 4 次 missRspOut？（把地址和 instrId 绑定起来）**

- 看 `MshrAccess.io.missRspOut.(valid,ready,fire)`
- 记录 `MshrAccess.io.missRspOut.bits.(blockAddr,instrId)`（`blockAddr` 对应 cacheline，`instrId` 对应那条指令）

**C. coreReqPipe 最终是否吐了 4 次 coreRsp？**

- 看 `io.coreRsp.fire` 发生次数（应为 4）
- 记录 `io.coreRsp.bits.(instrId,activeMask)`：每次返回应覆盖一个“子集合 lane”（由 `activeMask` 体现）

**判定：**

- 若 **A=4，但 B<4**：问题在 `MemRspPipe -> MSHR.missRspIn/out` 链路（常见是 backpressure 下用 `valid` 当事件导致状态/数据错位）。
- 若 **B=4，但 C<4**：问题在 `CoreReqPipe` 的 coreRsp 组包/仲裁/队列阶段（常见是与其他来源 coreRsp 同拍冲突导致“消费了但没入队”）。

### 只在需要时加 2 个信号，快速判断是不是“同拍冲突被吃掉”

当你怀疑 **B=4 但 C=3** 时，加看：

- `coreReqPipe.io.memRsp_coreRsp.fire`（表示 memRsp->coreReqPipe 这拍真的被消费）
- `coreReqPipe.CoreRsp_pipeReg_st1_st2.enq.bits.validFromCoreReq`（1=本拍 enq 的 rsp 来自 coreReq，0=来自 memRsp）

若出现：`coreReqPipe.io.memRsp_coreRsp.fire=1` 但同拍 `validFromCoreReq=1`，基本就能判定：**memRsp 这拍被 ready 消费了，但 coreReqPipe 的 st1->st2 enq 选了 coreReq 路径，导致 memRsp 响应被“吃掉/覆盖”。**

（进一步确认冲突来源时，再补看 `coreReqPipe.CoreReq_pipeReg_st0_st1.deq.bits.Ctrl.isWrite` / `WriteMiss_st1` 即可。）

### 进展（修复）

- 2026-01-15：修复 `memRsp_coreRsp.fire=1` 但 `validFromCoreReq=1` 导致 memRsp 响应被“消费但未入队”的丢包问题：
  - `ventus/src/L1Cache/DCache/CoreReqPipe.scala`：为 write-miss 与 flush/responding 的 `st1_ready` 增加 `!io.memRsp_coreRsp.valid`，避免与 memRsp 同拍争用 `CoreRsp_pipeReg_st1_st2`。
  - `ventus/src/L1Cache/DCache/CoreReqPipe.scala`：`io.memRsp_coreRsp.ready` 改为基于 `CoreRsp_pipeReg_st1_st2.enq.ready`，并在 coreReq 路径占用该 enq 时拉低 ready，保证 ready 语义与“实际入队”一致。

## 新问题：dirty replace 期间 victim line 仍可 write-hit（潜在丢写）

### 现象（你的复现）

- 一次指令包含多个 addr（addr0~6），其中 `addr5=0x9000032C`（lineBase=`0x90000300`）。
- 已经看到 dirty replace 写回请求发出（`MemReqArb.in(0).fire` / `io.memReq.fire` 地址均为 `0x90000300`），但随后 `addr5` 仍出现 **write hit**（预期应被阻塞/重放，不能继续写到即将被替换的 victim line）。
- 同时观察到：replace 读出 victim 数据后，中间隔了多个 cycle 才出现 `memRspPipe.io.dAmemRsp_wReq_valid`（根因是 `st1_ready` 被拉低）。

### 根因 1（功能语义）：replace 期间没有对 victim line 做“命中屏蔽/锁”

- 当前 `L1TagAccess` 在 `needReplace` 场景只清 `way_dirty`，并不会清 `way_valid`，因此 victim line 在 replace 窗口仍能被 tag 命中，导致后续写 hit 有机会写入 Data SRAM，但随后被 fill 覆盖 → 丢写风险。
- 修复思路：对 victim line 加 replace-lock，在 fill commit（写 tag/data）前禁止命中（让访问走 replay/重放）。

### 根因 2（实现 bug）：replace 路径下 fill 写入使能条件不覆盖 `memReq` 状态

- `MemRspPipe` 原逻辑 `dAReq_valid := (tagRequestStatus===idle && st1_valid && st1_ready)`。
- 但 dirty replace 正常会在 `tagRequestStatus==memReq` 的周期完成 `memReq_ready` 握手，同时该拍 `st1_ready` 可能被拉高从而把 `MemRsp_pipeReg_st0_st1` 出队。
- 结果：整段流程中没有出现 `idle && st1_valid && st1_ready` 的组合，导致 `dAmemRsp_wReq_valid/allocateWriteTagSRAMWValid_st1` 永远不拉高，fill 的 tag/data 不会写入。

### 进展（修复）

- 2026-01-17：修复 dirty replace 窗口的 write-hit 与 fill 写入缺失问题：
  - `ventus/src/L1Cache/L1TagAccess.scala`：新增 replace-lock（setIdx/wayMask），在 fill commit 前对 victim way 做 `way_valid` 掩码，避免 replace 窗口 write-hit。
  - `ventus/src/L1Cache/DCache/MemRspPipe.scala`：
    - st0 侧所有“有副作用”的信号改为基于 `memRsp.fire` 推进（`tAAllocateWriteReq.valid` / `MSHRMissRsp.valid` / `MSHRData_pipeReg.enq.valid` / `MemRsp_pipeReg_st0_st1.enq.valid` 等），避免反压周期“未接收也推进”导致下一条 memRsp 污染当前 replace 流程。
    - `dAmemRsp_wReq_valid` 直接等价于 `MemRsp_pipeReg_st0_st1.deq.fire`（`st1_valid && st1_ready`），保证“出队给 coreRsp”与“写入 tag/data”严格同拍，不再出现 deq 了但 tag/data 没写的丢填充问题。
    - 2026-01-17（后续调整）：由于 `L1MSHR.scala` 的 subentry 弹出依赖 `missRspIn.valid`（不是 `fire`），`MSHRMissRsp.valid` 不能仅用 `memRsp.fire` 驱动，否则在 `ready=0` 时会出现“valid 起不来→subentry 不清→SecondaryFull 自锁”的死锁式停顿；因此 `MSHRMissRsp.valid` / `tAAllocateWriteReq.valid` / `MemRsp_pipeReg_st0_st1.enq.valid` 已恢复为基于 `memRsp.valid`，replace 丢脉冲问题由 `needReplace_pending` 与 replace 上下文锁存解决（详见后文 `MSHRMissRsp.ready` 小节）。
- 2026-01-18：补全 replace-lock 语义以覆盖 st1 stall 场景：
  - `ventus/src/L1Cache/L1TagAccess.scala`：新增 `replace_lock_*`（valid/setIdx/waymask），在 `needReplace_pulse` 时锁存 victim，并在 `allocateWriteTagSRAMWValid_st1` 清锁。
  - `ventus/src/L1Cache/L1TagAccess.scala`：对 `iTagChecker.io.way_valid` 做 victim way 掩码，且在 `cachehit_hold` 输出后再用 lock 覆盖，保证 **st1 backpressure** 时也不会继续 hit victim line。

### 备注（可选方案）

- 也可以在发起 replace memReq 后直接把 victim 的 `way_valid` 拉低；但当前实现 replacement 场景并不会再“置回” `way_valid`，因此若要采用该方案，需要补充在 fill commit（`allocateWriteTagSRAMWValid_st1`）时把对应 way_valid 重新置 1（否则会把 line 永久 invalid）。

## 新问题：Chisel->(FIRRTL)->(firtool) 检测到组合逻辑闭环（memRsp_coreRsp_ready 回环）

### 现象

- 在生成 SystemVerilog（`./mill -i ventus[6.4.0].runMain top.GPGPU_gen`）时，`firtool` 报错：
  - `error: detected combinational cycle in a FIRRTL module, sample path: DataCachev2.{... memRsp_coreRsp_ready ...}`
- 回环样例路径出现两类：
  - `coreReqPipe.memRsp_coreRsp.ready -> SMshrAccess.probeOut(hitblock/LRexist) -> ... -> memRspPipe.SMSHRMissRsp.valid/ready -> ... -> coreReqPipe.memRsp_coreRsp.ready`
  - `coreReqPipe.memRsp_coreRsp.ready -> WshrAccess.checkresult.Hit -> ... -> memRspPipe.WSHRPopReq.valid -> ... -> coreReqPipe.memRsp_coreRsp.ready`

### 根因

- `MemRspPipe` st0 为了避免反压周期“未接收也推进”，把多个信号改成基于 `io.memRsp.fire`（`valid && ready`）触发。
- 但 `memRsp.fire` 结构上依赖 `st0_ready`，而 `st0_ready` 又通过（1）`MemRsp_pipeReg` 的 `pipe=true`（`enq.ready` 依赖 `deq.ready`）以及（2）`SMSHR` ready 链路（`missRspIn.ready` 依赖 `missRspOut.ready`）间接依赖 `coreReqPipe.io.memRsp_coreRsp.ready`。
- 同时 `SpecialMSHR` 的 `probeOut_st1`（`hitblock/LRexist`）组合逻辑依赖 `missRspIn.valid`，`WSHR` 的 `checkresult` 组合逻辑依赖 `popReq.valid`，而这些信号又会反馈影响 `coreReqPipe.io.memRsp_coreRsp.ready`，形成组合闭环。

### 修复

- 2026-01-17：消除 `DataCachev2` 组合闭环并保持 st0“副作用只在真正接收 memRsp 时发生”的语义：
  - `ventus/src/L1Cache/DCache/MemRspPipe.scala`：新增 `SMSHRMissRsp_pipeReg`（`Queue(pipe=false, flow=false)`）缓存 special 的 `missRspIn`，`io.SMSHRMissRsp` 由该队列出队驱动，避免 `missRspIn.valid` 直接受 `ready` 影响。
  - `ventus/src/L1Cache/DCache/MemRspPipe.scala`：`MemRsp_pipeReg_st0_st1` 改为 `pipe=false`，移除 `deq.ready -> enq.ready` 的组合路径，降低 ready 网络回环风险。
  - `ventus/src/L1Cache/DCache/MemRspPipe.scala`：`io.WSHRPopReq.valid` 改为 `io.memRsp.valid && memRspisWrite`（write rsp st0 始终可接收），避免 `memRsp.fire` 的 ready 依赖在结构上引入回环。

### 验证

- `./mill -i ventus[6.4.0].runMain top.GPGPU_gen`：通过（不再报 combinational cycle）。
  - 2026-01-17（后续调整）：为避免 `MSHRMissRsp.ready` 自锁，`MemRsp_pipeReg_st0_st1` 已恢复为 `pipe=true`，且移除了临时的 `SMSHRMissRsp_pipeReg`；当前组合闭环的规避点主要是避免用 `memRsp.fire` 直接驱动会反馈到 `coreReqPipe.io.memRsp_coreRsp.ready` 的 `valid` 信号（例如保持 `WSHRPopReq.valid := memRsp.valid && isWrite`），并确保上游对 `ValidIO` 的 `probe*` 信号使用 `fire`（而非 `valid`）进行驱动。

## 新问题：`MSHRMissRsp.ready` 常低 / `mshrStatus_st1_w` 常为 3（SecondaryFull）

### 现象

- 观察到 `memRspPipe.io.MSHRMissRsp.ready` 长时间为 0，read memRsp 无法被接收。
- 继续向上追到 `MshrAccess`，发现 `mshrStatus_st1_w` 长时间等于 `3`（`MSHRStatus.SecondaryFull`），导致 `io.missRspIn.ready` 被拉低。

### 根因

- `L1MSHR.scala` 的 `io.missRspIn.ready` 逻辑会在 `subentryStatusForRsp.used >= 2` 等条件下拉低 ready，用于“在同一条 memRsp 数据被重复使用时”逐个弹出 subentry（其内部用的是 `io.missRspIn.valid` 驱动，而非 `fire`）。
- 我之前在 `MemRspPipe` 把 `io.MSHRMissRsp.valid` 改成了基于 `io.memRsp.fire`（也就是 ready 高才给 valid），这会导致：
  - 当 MSHR 处于 SecondaryFull 且 ready 拉低时，`MSHRMissRsp.valid` 永远起不来 → MSHR 无法依靠 `valid` 驱动去清 subentry → used 不下降 → `mshrStatus_st1_w` 持续为 3 → ready 永远不回升（死锁式自洽）。

### 修复

- 2026-01-17：恢复 MSHR missRspIn 的“valid 驱动”语义，允许在 ready 低时也能推进 subentry 弹出：
  - `ventus/src/L1Cache/DCache/MemRspPipe.scala`：`io.MSHRMissRsp.valid` / `io.tAAllocateWriteReq.valid` / `MemRsp_pipeReg_st0_st1.enq.valid` 恢复为基于 `io.memRsp.valid`（而不是 `io.memRsp.fire`）。
  - `ventus/src/L1Cache/DCache/MemRspPipe.scala`：`MemRsp_pipeReg_st0_st1` 恢复为 `pipe=true`，维持原设计“memRsp 被 backpressure 时可重复使用同一拍数据”的行为。

### 验证

- `./mill -i ventus[6.4.0].runMain top.GPGPU_gen`：通过。

## 2026-03-07 最小验证：把 write-miss `activeMask` 并入 `memReq_Q` 仍不能解除 `nn64K` 卡死

### 修改点

- `ventus/src/L1Cache/DCache/DCachev2.scala`：新增 `WshrMemReqV2`，把 write-miss 需要的 `activeMask` 与 `hasCoreRsp/coreRspInstrId` 一起放到 v2 专用 memReq bundle，避免触碰 `DCache.scala`。
- `ventus/src/L1Cache/DCache/CoreReqPipe.scala`：write miss 在生成 `missMemReq_st1` 时把 `Req.perLaneAddr.activeMask` 一起锁存到 `WshrMemReqV2.activeMask`；非 coreRsp 请求（flush/evict）统一写 0 mask。
- `ventus/src/L1Cache/DCache/DCachev2.scala`：删除独立的 `memReqCoreRspActiveMask_Q`，`memReq_coreRsp.activeMask` 改为直接读取 `memReq_Q.io.deq.bits.activeMask`。

### 验证命令

```bash
source /home/sunhn/ventus-env/env.sh
cd /home/sunhn/ventus-env/gpgpu
bash build-ventus.sh --build gvm

cd /home/sunhn/ventus-env/rodinia/opencl/nn/verify_min_fix_memreq_mask
VENTUS_BACKEND=gvm VENTUS_WAVEFORM=1 \
./nn.out /home/sunhn/ventus-env/rodinia/data/nn/list64k.txt \
  -r 20 -lat 30 -lng 90 -f /home/sunhn/ventus-env/rodinia/data/nn \
  -t -p 0 -d 0 \
  --ref /home/sunhn/ventus-env/rodinia/opencl/nn/nvidia-result-64k-lat30-lng90 \
  2>&1 | tee run_console.log
python3 /home/sunhn/ventus-env/gpgpu/sim-verilator/gvm-log-script.py \
  --input run_console.log \
  --output gvm.log
```

### 结果

- 新日志目录：`/home/sunhn/ventus-env/rodinia/opencl/nn/verify_min_fix_memreq_mask`
- 关键现象与当前坏版本一致：
  - 仍在 `@975` 出现首个 `x29` mismatch（老问题未变）。
  - `sm0 warp7` 仍在 `0x800000d0` 发出 `lsu.w ... @ 90221180`。
  - 紧接着仍在 `@12900` 报 `PMEM page at 0x90221180 not allocated, read as all zero`。
  - 之后只剩 heartbeat：`@20000/@30000/@40000/@50000/@60000/...`，没有再看到新的 retire 前进。
- 对比 `05503f8`：
  - 旧版同样命中 `0x90221180`，但不会在这里卡死，而是继续执行到 `@84775`，最终死于 `UNDEFINED INSTRUCTION @ PC 0x00010000`。

### 结论

- 仅把 write-miss `activeMask` 从旁路 sideband 改为随 `memReq_Q` 携带，不足以修复当前 `nn64K` 的卡死。
- 这说明先前“activeMask 脱节导致 store-miss completion 丢失”最多只是部分现象，真正让当前版本停在 `0x90221180` 的原因仍在别处。
- 下一步应优先继续核对 write-miss completion 的其它状态是否也在重构中脱节，尤其是：
  - `hasCoreRsp/coreRspInstrId` 与实际入队 request 的时序一致性；
  - `WshrAccess.io.pushReq.valid`、`coreRsp_st2_valid_from_memReq`、`coreReqPipe.io.memReq_coreRsp.fire` 在 `0x90221180` 附近是否真正发生；
  - `st1_ready` / `memReq_Q.io.deq.ready` / `cRspBlockedOrWshrFull` 是否把 write miss completion 永久压住。

## 新问题：st1 stall 结束后 probeStatus 仍 SecondaryAvail（st1 保存的 entryMatchProbe 陈旧）

### 现象（示例时序）

- `c0`：`req0` 在 st0 probe 命中已有 entry（`entryMatchProbe_st0` one-hot=E），但 st1 因 releasing stall 未握手。
- `c1`：同一 entry 的 `missRspIn.fire` 清除最后一个 subentry，`entry_valid(E)` 下一拍变 0。
- `c2`：releasing stall 解除，st1 继续握手；但 `MSHR_st1.deq.bits.entryMatchProbe` 仍为旧 one-hot=E，导致 `secondaryMiss=1`、`probeStatus=SecondaryAvail`，coreReq 继续按 secondary miss 合并（不发起新的 missReq），可能把 subentry 写入一个已释放/无效的 entry，最终卡死。

### 根因

- `entryMatchProbe_st1` 取自 st0 probe 的 pipeReg（Queue），在 st1 stall 跨过 `missRspIn` 释放窗口时会变成陈旧信息；
- `secondaryMiss/subentrySelectedForReq` 未结合当前 `entry_valid` 重新过滤，导致 missRsp 清空 entry 后下一拍仍被当作 secondary miss。

### 修复

- 2026-01-21：
  - `ventus/src/L1Cache/L1MSHR.scala`：将 `entryMatchProbe_st1` 用 `& entry_valid` 过滤，并用过滤后的 `secondaryMiss` 参与 `mshrStatus_st1_w`/`real_SRAMAddrUp`/subentry 选择计算，使 missRsp 清空 entry 后下一拍自动回到 `PrimaryAvail`。

### 验证

- `./mill -i ventus[6.4.0].runMain top.GPGPU_gen`：通过。

### 残留风险

- 若未来允许在 st1 stall 期间有其他来源重新分配同一个 entry index（`entry_valid` 回 1 但 blockAddr 已变），仅用 `entry_valid` mask 仍可能误判；当前 DCache missReq 仅在 st1 `deq.fire` 时发起，stall 期间不应出现该重分配。

## 新问题：MSHR missRspIn“原子态”同拍 st1 secondary merge，导致 late-merge 卡死

### 现象（示例时序）

- `c0`：`req0` 在 `coreReqPipe` st0 握手进入流水。
- `c1`：`MSHR` 正在处理同一 block 的 `missRspIn`（同拍 “释放/返回”），但由于内部 valid/状态在该拍尚未更新，`coreReqPipe` st1 看到的 `MSHR_ProbeStatus.probeStatus` 仍可能为 `SecondaryAvail(010)`；若 `req0` 在该拍继续按 secondary miss 合并（不发起新 memReq），会出现 “late secondary” 错过本拍正在处理的 missRsp，最终 subentry 卡在 MSHR 内不再前进。

### 根因

- `missRspIn` 发生的这个周期属于 MSHR 的“原子态窗口”：subentry_valid/entry_valid 的更新在时钟沿生效，组合态下 probeStatus 仍反映旧状态；
- st1 若在该窗口对同一 block 做 secondary merge，会把请求并入一个“正在被释放/正在返回”的 entry，形成 late-merge。

### 修复

- 2026-01-21：由 MSHR 直接输出 `releasing_valid/blockAddr(/asid)`（仅依赖 `missRspIn.valid`，不依赖 ready/fire），coreReqPipe 在 st1 检测到“同块 releasing”时强制拉低 `st1_ready`，等待原子态结束后再继续。
  - `ventus/src/L1Cache/L1MSHR.scala`：新增 `releasing_valid/releasing_blockAddr(/releasing_asid)` 输出。
  - `ventus/src/L1Cache/DCache/DCachev2.scala`：连线 `MshrAccess -> coreReqPipe` 的 releasing 信号。
  - `ventus/src/L1Cache/DCache/CoreReqPipe.scala`：新增 `mshrReleasing_*` 输入；同块 releasing 时拉低 `st1_ready`；同时将 `MissReq_MSHR.valid` 改为基于 `deq.fire`（避免 st1 停住但 MSHR 侧已握手导致状态不一致）。

### 验证

- `./mill -i ventus[6.4.0].runMain top.GPGPU_gen`：通过。

## 新问题：missRspIn 与 missReq 同拍时 missRspIn 被阻塞

### 现象

- `missRspIn.valid=1` 的周期出现 `missReq.valid=1`，导致 `missRspIn.ready` 被压低，missRspIn 无法握手；优先级与“missRspIn 优先释放 MSHR”的预期相反。

### 根因

- `L1MSHR.scala` 中 `io.missRspIn.ready` 直接依赖 `io.missReq.valid`，使 missReq.valid 夺走了 missRspIn 的 ready。

### 修复

- 2026-01-18：`ventus/src/L1Cache/L1MSHR.scala` 为 missRspIn 增加优先级：
  - `missReq.ready` 在 `missRspIn.valid` 时拉低，避免同拍 `missReq.fire`。
  - `missRspIn.ready` 去除 `io.missReq.valid` 的阻塞条件。
  - 内部使用 `missReqValid = io.missReq.valid && !missRspIn.valid` 作为 missReq 参与判断的有效条件（如 probeMatchMissReq / probeOut_st1.a_source）。

### 验证

- 待补：检查同拍 `missRspIn.valid` 时 `missReq.ready=0`、`missRspIn.ready=1`，且无 `missReq.fire`。

## 新问题：replace 阶段 tag 写入 bits/valid 不对齐（allocateWrite 被后续 memRsp 覆盖）

### 现象

- `tagBodyAccess.io.w.req.valid` 拉高时，`setIdx/wayMask` 与预期 memRsp 不匹配，波形中出现 `addr0` 还在 replace，但 `allocateWrite` 已被 `addr1` 覆盖。

### 根因
 
- `MemRspPipe` 的 `tAAllocateWriteReq.valid` 基于 `memRsp.valid`，当 `memRsp.ready=0`（backpressure）且新的 memRsp 地址出现时，`ValidIO` 仍会推动 `allocateWrite_st1` 更新，导致 tag 写入 bits 与当前 replace 事务错位。

## 新问题：RTAB 的 `mshrFull` 不等价于 “MSHR PrimaryFull”（EntryFull replay 可能提前触发）

### 现象（你的怀疑）

- RTAB entry `Replay_type=EntryFull` 时，`mshrFull` 很少拉高；即使 MSHR primary entry 已满也可能触发 replay。

### 代码定位（最小链路）

- `ventus/src/L1Cache/DCache/DCachev2.scala:168`：`ReplayTable.io.mshrFull := MshrAccess.io.full`
- `ventus/src/L1Cache/DCache/L1RTAB.scala:214`：`EntryFull` 的 replay 仅依赖 `!io.mshrFull`
- `ventus/src/L1Cache/DCache/CoreReqPipe.scala:289`：`ReadMiss && MshrStatus===PrimaryFull` 时置 `ReplayType := EntryFull`
- `ventus/src/L1Cache/L1MSHR.scala:329`：`io.full := MSHR_st1.io.deq.valid && MSHR_st1.io.deq.bits.full`
- `ventus/src/L1Cache/L1MSHR.scala:245`：`MSHR_st1.io.enq.bits.full := mainEntryFull && subEntryFull`
- `ventus/src/L1Cache/L1MSHR.scala:167`：`subEntryFull` 来自 `subentryStatus.io.full`，其选择基于 `entryMatchProbe_st1`（secondary miss 才可能非 0）

### 根因（确认存在语义不匹配）

- `EntryFull` 的语义来自 `PrimaryFull`（即 **mainEntryFull**），但 `MshrAccess.io.full` 需要 `mainEntryFull && subEntryFull`，且额外被 `MSHR_st1.io.deq.valid` gate。
- 当本次是 **primary miss** 时 `entryMatchProbe_st1===0`，`subentrySelectedForReq` 会被强制全 0，从而 `subEntryFull=0`，导致即使 `mainEntryFull=1`，`MSHR_st1.io.enq.bits.full` / `io.full` 也不会因为 primary full 而拉高。

### 建议修复方向（未改动代码）

- 方案 A：将 `ventus/src/L1Cache/L1MSHR.scala` 的 `io.full` 语义改为 “primary entry full”（例如直接输出 `mainEntryFull` / `entryStatus.io.full`），让 `ReplayTable.io.mshrFull` 能正确反映 `PrimaryFull`。
- 方案 B：保持 `L1MSHR.io.full` 不动，在 `ventus/src/L1Cache/DCache/DCachev2.scala` 将 `ReplayTable.io.mshrFull` 改为基于 `MshrAccess.io.mshrStatus_st0===PrimaryFull`（或新增独立 `primaryFull` 输出），避免复用当前 `io.full`。

### 修复

- 2026-01-20：采用方案 A：
  - `ventus/src/L1Cache/L1MSHR.scala`：`io.full` 改为 `entryStatus.io.full`（primary entry full），去掉原来 `MSHR_st1.io.deq.valid && mainEntryFull && subEntryFull` 的 gate。

### 验证（待补）

- 波形：对齐 `MshrAccess.io.probeOut_st1.probeStatus==PrimaryFull(1)` 时的 `MshrAccess.io.full / ReplayTable.io.mshrFull`，确认其是否仍为 0，以及 RTAB 是否因此提前 replay。

### 修复

- 2026-01-18：`ventus/src/L1Cache/DCache/MemRspPipe.scala` 将 `tAAllocateWriteReq.valid` 改为基于 `MemRsp_pipeReg_st0_st1.enq.fire`（`st0_valid && enq.ready`），只在 memRsp 真正“入队/锁存到 MemRsp_pipeReg”时推进 `allocateWrite`，避免 replace/反压窗口被后续 memRsp 覆盖（`MSHRMissRsp.valid` 仍保持 `memRsp.valid` 以维持 MSHR 的 subentry 语义）。
- 2026-01-18：进一步解耦 coreRsp 与 refill 两条路径：
  - `MemRsp_pipeReg_st0_st1.enq.valid := memRsp.fire && memRspisRead`，避免 `io.memRsp.ready=0` 时重复采样导致 pipeReg “握手多次”。
  - `io.memRsp_coreRsp` 的选择不再依赖 `MemRsp_pipeReg_st0_st1.deq.bits.isSpecial`，改为基于 `io.SMSHRMissRspOut.valid` / `io.MSHRMissRspOut.valid`；同时 `MSHRData_pipeReg` 覆盖 special read（LR/SC/AMO）以提供稳定 `d_data`。

### 验证

- 待补：对齐观测 `MemRsp_pipeReg_st0_st1.enq.fire`/`tAAllocateWriteReq.valid`/`TagAccess.allocateWrite.fire` 与 `tagBodyAccess.io.w.req.(valid,bits)`。

## 新问题：st1 backpressure 时 TagAccess miss 结果被覆盖，coreRsp 失配

### 现象

- 一次指令跨多个 cacheline：`addr0` 在 `st1` 判定 miss 时 `st1_ready` 被拉低，随后 `tA_Hit_st1` 在 `st1` 仍阻塞时翻转为 hit（实际上对应其他读请求），导致 `CoreRsp_pipeReg_st1_st2.enq` 以 hit 路径入队，但 `CoreReq_pipeReg_st0_st1.deq` 仍是 `addr0`，出现 miss/hit 与地址不对齐。

### 最小信号

- `coreReqPipe.io.st1_ready`
- `CoreReq_pipeReg_st0_st1.deq.bits.Req.(tag,setIdx,perLaneAddr)`
- `TagAccess.io.hitStatus_st1.(hit,waymask)`
- `TagAccess.io.probeRead.(valid,ready)`

### 根因

- `L1TagAccess` 的 `cachehit_hold` 只在 **hit** 时保持命中结果；当 miss 且 `st1_ready=0` 时，`hitStatus_st1` 仍直接取 `iTagChecker` 的当前输出，若期间 `tagBodyAccess` 被其他读（allocate/hasDirty）覆盖，miss 会被“翻转”为 hit，导致 coreRsp 与 deq 地址失配。

### 修复

- `ventus/src/L1Cache/L1TagAccess.scala`：在 `cachehit_hold.deq.valid` 时 **无条件** 使用 hold 的 `hit/waymask`，并基于 hold 的 `waymask` 计算 `isDirty`/`waymaskHit_st1`，确保 miss 也能在 backpressure 期间稳定。

### 验证

- 未跑仿真/回归；建议复现波形：`st1_ready=0` 期间 `tA_Hit_st1.hit` 不再翻转，且 `CoreRsp_pipeReg_st1_st2.enq` 与 `CoreReq_pipeReg_st0_st1.deq` 对齐。

### 残留风险

- `hitStatus_st1.tag` 仍直接取 `tagBodyAccess` 的当前输出（未锁存）；目前未被核心逻辑使用，但调试时需注意可能不对应 hold 的地址。

## 新问题：memRsp 反压下 MSHRData_pipeReg 虚入队占坑

### 现象（示例时序）

- `c0`：`io.memRsp.valid=1`、`io.memRsp.ready=0`（`MemRsp_pipeReg_st0_st1.enq.ready=0`），`io.MSHRMissRsp.fire=1`，`MSHRData_pipeReg.enq.fire=1`。
- `c1`：`io.memRsp.fire=1` 且 `MemRsp_pipeReg_st0_st1.enq.fire=1`，`io.MSHRMissRsp.fire=1` 再次触发，`MSHRData_pipeReg.enq.fire=1` 再入队。
- 后续若 `coreRspFrom(M/S)MSHR` 不再有效，`MSHRData_pipeReg` 中残留“虚数据”占住下一次入口。

### 最小信号

- `io.memRsp.(valid,ready,fire)`
- `io.MSHRMissRsp.(valid,ready,fire)` / `io.SMSHRMissRsp.(valid,ready,fire)`
- `MemRsp_pipeReg_st0_st1.enq.(ready,fire)`
- `MSHRData_pipeReg.enq.(ready,fire)` / `MSHRData_pipeReg.deq.(valid,ready)`
- `io.memRsp_coreRsp.(valid,ready)` / `coreRspFromMshr` / `coreRspFromSms`

### 根因

- `MSHRData_pipeReg.enq.valid` 直接用 `io.memRsp.valid`，当 `io.memRsp.ready=0` 造成 memRsp 被反压时，同一条 memRsp 在多个周期被“重复入队”；如果随后没有对应 `coreRspFrom(M/S)MSHR`，队列里的虚数据无法被消费。

### 修复

- 2026-01-18：`ventus/src/L1Cache/DCache/MemRspPipe.scala` 在没有 `coreRspFrom(M/S)MSHR` 时强制 `MSHRData_pipeReg.deq.ready=1`，主动 drain 掉无对应 coreRsp 的残留数据；有 coreRsp 时仍仅在 `memRsp_coreRsp.ready` 下出队，保证正常 backpressure 语义。

### 验证

- 待补：复现上述 `c0/c1`，确认 `coreRspFrom(M/S)MSHR=0` 时 `MSHRData_pipeReg` 会被清空，不再占坑。

## 新问题：RTAB full 时外部未握手，但 CoreReqArb 内部“握手”导致重复注入

### 现象

- `ReplayTable.io.RTAB_full=1` 时，顶层 `io.coreReq.ready` 被拉低，外部 `io.coreReq.fire=0`（外部保持同一条请求不变）。
- 但同周期观察到 `CoreReqArb.io.in(1).fire=1` / `CoreReqArb.io.out.fire=1`，导致 `CoreReq_pipeReg_st0_st1.enq/deq` 等内部流水持续活动，表现为“同一条 coreReq 被重复注入”。

### 最小信号

- `ReplayTable.io.RTAB_full`
- `io.coreReq.(valid,ready,fire)`（顶层）
- `CoreReqArb.io.in(1).(valid,ready,fire)`、`CoreReqArb.io.out.fire`（仲裁是否在消费 in(1)）
- `coreReqPipe.CoreReq_pipeReg_st0_st1.enq.fire`（是否重复入队）

### 根因

- `ventus/src/L1Cache/DCache/DCachev2.scala` 中 `io.coreReq.ready := CoreReqArb.io.in(1).ready && !ReplayTable.io.RTAB_full` 对外部 `ready` 加了更严格条件；
- 但 `CoreReqArb.io.in(1)` 仍直接接收 `io.coreReq.valid`，导致 `RTAB_full=1` 时外部未握手、内部却可能被 arbiter “消费”，破坏 Decoupled 语义（同一拍/同一请求被重复当作 fire）。

### 修复

- 2026-01-20：`ventus/src/L1Cache/DCache/DCachev2.scala` 改为显式连接并 gate：
  - `CoreReqArb.io.in(1).valid := io.coreReq.valid && !ReplayTable.io.RTAB_full`
  - `io.coreReq.ready := CoreReqArb.io.in(1).ready && !ReplayTable.io.RTAB_full`
  - 保证 `io.coreReq.fire` 与 `CoreReqArb.io.in(1).fire` 一致，避免重复注入。
- 2026-01-20：补充 almost-full 保护（避免“本拍 st1 入 RTAB 占掉最后 1 个空位 + 同拍又接收新 coreReq，下一拍该 coreReq 也要入 RTAB -> 溢出”）：
  - `allowIn1 := !RTAB_full && !(RTAB_almost_full && coreReqPipe.io.Req_st1_RTAB.valid)`
  - `io.coreReq.ready` 与 `CoreReqArb.io.in(1).valid` 同时使用 `allowIn1` gate。

### 验证

- `./mill -i ventus[6.4.0].compile`：通过。

## 新问题：dirty replace 期间 victim line 仍可 write-hit

### 现象（示例时序）

- `c0`：cached read miss 的 memRsp 在 `MemRspPipe` 进入 st1（`st1_valid=1`），`TagAccess.io.needReplace=1`（即 `needReplace_pulse`）。`memRspPipe.io.dAReplace_rReq_valid=1` 发起读 victim data；同时 `dAmemRsp_wReq_valid=0`（allocate 写回被延后）。
- `c1`：victim data 被采样（`replaceDataReg` 置 valid），`memRspPipe.io.memReq_valid=1` 等待写回请求进入 `MemReqArb`。
- `c1~cN`：若 core 这期间对 victim line 发 store，仍可能出现 `coreReqPipe.io.WriteHit_st1=1`，DataAccess 写口更新 victim line。
- `cK`：dirty replace 的 `PutFullData` 使用早先采样的 victim data 发出，未包含 `c1~cN` 的 write-hit 更新 -> 写回丢数据/不一致。

### 最小信号

- `TagAccess.io.needReplace`
- `memRspPipe.io.(dAReplace_rReq_valid, memReq_valid, dAmemRsp_wReq_valid, blockCoreReq)`
- `MemReqArb.io.in(0).(valid,ready,fire)` / `replaceMemReqFire`
- `coreReqPipe.io.(st1_valid,st1_ready,WriteHit_st1)` 与 `DataAccesses(i).io.w.req.valid`

### 根因

- dirty replace 流程为保证 PutFullData 先被接受，allocate 的 tag/data 写入被延后到 `dAmemRsp_wReq_valid` 周期；在这段窗口 victim line 的 tag 仍有效，导致 core 仍可 hit 并修改 victim line；而写回数据来自更早周期采样的 victim data，产生“写回丢更新”。

### 修复

- 2026-01-21：
  - `ventus/src/L1Cache/DCache/MemRspPipe.scala`：新增 `blockCoreReq`，在 `needReplace_pulse` 拉高后保持到本次 allocate 写回（`dAmemRsp_wReq_valid`）完成。
  - `ventus/src/L1Cache/DCache/CoreReqPipe.scala`：新增 `blockCoreReq` 输入；拉高时强制 `st0_ready/st0_valid/st1_ready=0` 冻结 coreReqPipe，避免任何 hit/miss 推进。
  - `ventus/src/L1Cache/DCache/DCachev2.scala`：用 `blockCoreReq` gate `CoreReqArb` 两路输入（replay + 外部）以及 `allowIn1`；同时将 write-hit 对 DataAccess 的写使能改为 `WriteHit && st1_valid && st1_ready`（只在 st1 真正 fire 时写），防止 backpressure 下的多周期重复写/与 replace 并发写。

### 验证

- `./mill -i ventus[6.4.0].compile`：通过。

### 残留风险

- `blockCoreReq` 是全局 stall，dirty replace 期间会暂停所有 core 请求（含 replay），影响性能但更安全；若未来想更细粒度（仅锁 set/way），需引入 line lock/way lock。
- 依赖前提：`TagAccess.io.needReplace` 为单周期脉冲且同一条 memRsp 的 replace 流程串行（当前 `L1TagAccess` 注释已说明 needReplace 默认只拉高一拍）。

## 新问题：refill 同拍 coreReq 误判 miss，导致 MSHR 卡死

### 现象（示例时序）

- `c0`：`coreReqPipe` 在 st0 与 `req0` 发生握手；同拍 `memRspPipe.io.dAmemRsp_wReq_valid=1`，且其写回的 cacheline blockAddr 与 `req0` 访问的 blockAddr 相同。
- `c1`：`req0` 在 st1 走 miss 路径，同时 `MSHR` probeStatus 返回 `SecondaryAvail`（表现为“可用 subentry”，因此不会发起新的 memReq）。由于该 block 的 memRsp 已在 `c0` 被消费/写回，`req0` 作为“late secondary”错过返回，最终卡在 MSHR 内不再前进。

### 根因

- refill/allocate 写回(tag/data)与新的 coreReq probe 同拍发生时，coreReq st0 看到的是更新前的 tag（miss）；而 MSHR 又允许 `SecondaryAvail` 合并，导致“在 memRsp 已被消费后才插入 subentry”的 late-merge，最终 subentry 永远等不到返回。

### 修复

- 2026-01-21：当 `memRspPipe` 正在对某个 block 执行 allocate 写回（`dAmemRsp_wReq_valid`）时，若 st0 的 coreReq 访问同一 blockAddr，则禁止 st0 握手进入流水（拉低 `st0_ready`，并同步 gate `st0_valid` 保证 Decoupled 语义一致）。
  - `ventus/src/L1Cache/DCache/MemRspPipe.scala`：新增 `dAmemRsp_wReq_blockAddr`（以及 MMU 下的 `dAmemRsp_wReq_asid`）输出。
  - `ventus/src/L1Cache/DCache/CoreReqPipe.scala`：新增 `refillWrite_*` 输入，并在 st0 对 read/write/AMO/LR/SC 路径 gate `st0_valid/st0_ready`。
  - `ventus/src/L1Cache/DCache/DCachev2.scala`：连线 `memRspPipe -> coreReqPipe` 的 refill 写回信息。

### 验证

- `./mill -i ventus[6.4.0].runMain top.GPGPU_gen`：通过。

## 2026-03-08 修复：split read miss 的 `a_source` 在 `DCachev2` 输出级串线，表现为 activeMask 相关卡死

### 现象

- 在已修复 `CoreReqPipe` st3 `activeMask` mux 之后，`nn64K` 仍会在 `@45055` 左右停住，只剩 heartbeat。
- 停机前关键访问是 `sm1 warp7` 的跨两个 cache line 的 vector load：
  - `0x90000f00`
  - `0x90000f80`
- 旧坏波形（`/tmp/verify_fix_st3_activeMask_mux.vcd`）显示这两个 split miss 离开 `sm1.dcache.io_memReq` 时 source 已被串掉：
  - `#42205 addr=0x90000f00 src=191`
  - `#42215 addr=0x90000f80 src=191`
- 这会让两条 miss 在下游共享同一个返回身份，LSU 侧看起来就像“只回来半边 completion / activeMask 清到一半后卡住”。

### 根因

- `ventus/src/L1Cache/DCache/DCachev2.scala` 的 memReq 输出级把 `a_addr` 与 `a_source` 分开 staging：
  - `a_addr` 走 `memReq_st3_addr`
  - `a_source` 原先仍复用 `memReq_st3.a_source`
- 在背靠背 split read miss 场景下，`memReq_st3.a_source` 会被后一条请求覆盖，而 `a_addr` 仍对应前一条请求，导致顶层 `io.memReq` 发出“地址/来源不匹配”的 request。
- 结果是某个 half-line completion 回不到正确的 MSHR/LSU entry，最终表现为 activeMask 相关死锁。

### 修改点

- `ventus/src/L1Cache/DCache/DCachev2.scala`
  - 新增独立的 `memReq_st3_source` 寄存器，和 `memReq_st3_addr` 一样按实际 `memReq_Q.io.deq.fire` 的请求锁存。
  - 顶层输出改为 `io.memReq.get.bits.a_source := memReq_st3_source`，不再依赖 `memReq_st3.a_source` 的隐式复用。
  - write miss 仍在真正 `deq.fire` 时用 `Cat("d0".U, WshrAccess.io.pushedIdx, memReqSetIdx_st2)` 生成 source；read miss 则锁存 dequeued request 自身的 source。

### 验证

- 编译安装：
  - 默认 `bash build-ventus.sh --build gvm` 这轮遇到 Verilator internal fault；
  - 改用低并行度重试并成功安装：

```bash
source /home/sunhn/ventus-env/env.sh
cd /home/sunhn/ventus-env/gpgpu/sim-verilator
make -f gvm.mk -j16 RELEASE=1 GVM_TRACE=1 VLIB_NPROC_CPU=16 VLIB_NPROC_DUT=1
make -f gvm.mk install RELEASE=1 PREFIX=/home/sunhn/ventus-env/install VLIB_NPROC_CPU=16 VLIB_NPROC_DUT=1
```

- 定向 case：
  - 目录：`/home/sunhn/ventus-env/rodinia/opencl/nn/verify_fix_memreq_source_align`
  - 日志：`gvm.log`
  - 波形：`waveform.fst`
- 新波形中，同一组 split miss 已带不同 source 发出：
  - `#42005 addr=0x90000f00 src=158`
  - `#42015 addr=0x90000f80 src=191`
- 新日志中程序已明显越过旧停点：
  - 仍会在 `@19340` 看到 `PMEM page at 0x90221180 not allocated`，但不再死锁；
  - 继续前进到 `@248015`、`@380000`，并在手动停止前跑到 `@413385` 仍有 retire。

### 结论

- 这次 `activeMask` 相关卡死的真正根因不是 lane mask 本身再次丢失，而是 split miss 的 `a_source` 被串线，导致 half-completion 回错身份。
- 修复 `DCachev2` 输出级 `a_source` 锁存后，`nn64K` 已不再复现原先 `@45055` 的 activeMask 卡死。

### 残留问题

- 当前 `nn64K` 仍有与此问题独立的执行错误（例如大量 `x2/x8` mismatch），但它们不再表现为本次 activeMask/half-completion 死锁。
