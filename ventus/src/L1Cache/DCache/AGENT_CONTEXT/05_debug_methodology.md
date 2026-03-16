# DCachev2 Debug 方法论

## 1. 先判断“是不是同一笔事务”

这次问题之所以绕了几轮，本质上是太早进入“哪个模块有 bug”的讨论，而没有先把事务身份绑定说清楚。

以后优先按下面顺序判断：

1. 当前返回的数据属于哪条 cacheline
2. 当前 completion 属于哪条 miss / 哪条 core 指令
3. 当前 activeMask 属于哪一组 lane 子集合
4. 上面三者是否还是同一笔事务

只要这里有一个不一致，就先修身份绑定，不要先修功能策略。

## 2. 对 DCache，日志只能给方向，波形必须给定案

这次我能靠日志把范围收敛到 `CoreReqPipe/MemRspPipe`，但没有第一时间直接去检索 `waveform.gvm.fst`，导致确认链条不够短。

以后流程应改成：

- 日志负责找到首个真实异常点
- 波形负责确认“错的是数量、顺序，还是身份绑定”
- RTL 修改只建立在明确的时序证据上

## 3. 优先抓“最小守恒关系”

调 DCache 最稳的办法，不是先看复杂内部状态，而是先看几段数量是否守恒：

- `memRsp.fire`
- `MSHRMissRspOut.fire`
- `coreRsp.fire`

如果数量先不守恒，问题多半在握手或队列；
如果数量守恒但内容错，多半在身份绑定或 data 选择。

## 4. 优先怀疑“live data”而不是“寄存器值本身”

这次两个问题都和 live path 有关：

- `st2` 直接消费 live `io.dA_data`
- `memRsp_coreRsp` 在首拍直接依赖 live memRsp data

所以调 DCache 时，看到“值像是对的，只是 line 不对”时，应优先问：

- 这个值是不是来自一个没有和事务一起推进的 live 数据口

## 5. 不要用“延 1 拍”掩盖协议问题

像 `RegNext(!coreRspPipeEnqFromCoreReq)` 这种修法，只适合临时验证“是否存在一拍 skew”，不适合做最终修复。

最终修复必须满足两点之一：

- 把 data 跟事务一起带下去
- 或者在消费时证明 live data 与该事务仍是同一笔

## 6. 修改后先验证“越过旧点”，再追新首错

这次一个重要经验是：

- 旧错误点消失，不等于程序已经正确
- 但“新首错后移”是非常重要的正向证据

所以每次修完都要明确写下：

- 旧首错在哪里
- 现在是否越过了
- 新首错在哪里

## 7. 让新 Agent 更快上手的最小流程

新 Agent 接手时，必须按下面流程：

1. 读 `DCachev2_AGENT_ENTRY.md`
2. 读 `01_code_map.md`
3. 读 `02_debug_playbook.md`
4. 先抓一轮最小波形
5. 用模板记录事实，再提出根因假设
6. 修改后按 `04_acceptance_checklist.md` 验收
7. 成功后同步更新 `07_success_casebook.md`、`08_hardware_change_log.md` 和 `06_self_iteration_rules.md`

这样做的目标不是增加流程，而是避免再次靠零散上下文和口头历史定位问题。

## 8. 先确认“你看到的到底是不是 Ventus 后端结果”

分析任何 `run_console.log` 之前，先确认下面两件事：

1. 运行前是否已经 `source /home/sunhn/ventus-env/env.sh`
2. 当前后端到底是 `Spike`、`gvm`、`rtl`，还是宿主机 OpenCL/NVIDIA

这里最容易出错的是：

- 没有 `source env.sh`
  - 程序会落到宿主机 OpenCL/NVIDIA GPU 路径
  - 这类日志不能用来证明 Ventus 已经通过
- 已经 `source env.sh` 但没指定 `VENTUS_BACKEND`
  - 默认是 `Spike`
  - 不能拿 `Spike` 的结论替代 `gvm/rtl`

标准闭环应该是：

1. `gvm` 先跑，生成 `log + wave`
2. 根据 `gvm` 定位和修改
3. `gvm` 先通过
4. `rtl` 再通过

## 9. 不要把修改 `gvm.mk` 当成常规 debug 手段

`gvm.mk` 的作用是构建后端，不是承载功能修复。

因此默认规则是：

- 优先修 RTL 或运行命令
- 优先通过 `source env.sh` 和 `VENTUS_BACKEND` 把执行路径跑对
- 优先用标准构建命令重编 `gvm`

只有当 `gvm.mk` 本身存在非常明确的脚本错误时，才允许修改它；否则很容易把“后端跑错了”误判成“RTL 已经修好了”。
