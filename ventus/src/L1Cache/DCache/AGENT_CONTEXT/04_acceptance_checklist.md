# DCachev2 修改验收清单

## 1. 编译级

- `./mill -i ventus[6.4.0].runMain top.GPGPU_gen` 通过
- 如涉及 GVM 后端，`bash build-ventus.sh --build gvm` 通过
- 若开启/关闭 tracing，确认 `libVentusGVM.so` 不再出现缺失符号
- 本轮结论对应的工件已明确标注执行后端，且确认运行前已经 `source /home/sunhn/ventus-env/env.sh`

## 2. 定向 case 级

- 目标 case 能稳定复现旧问题
- 修改后先确认已经越过旧故障点
- 若旧故障点消失但出现新首个错误点，必须记录“新首错是什么”

## 3. 波形级

- 对本次根因对应的最小信号做过一次真实核对
- 已确认修改后不是“靠运气错开 1 拍”
- 已确认 metadata/data 或 line/offset 的绑定关系被真正修正

## 4. 功能级

- 目标 case 在 `gvm` 下最终 PASS
- `gvm` 通过后，目标 case 在 `rtl` 下也最终 PASS
- 若是 `gaussian` 这类结果比对 case，必须看到最终结果匹配，不只看中途不报错
- 若日志仍有已知 benign mismatch，需在记录里明确说明其非阻塞性

## 5. 回归级

- 至少跑过与本次根因同类的一组相邻 case 或小回归
- 在全量回归前，先确认没有引入明显新的 stall/deadlock

## 6. 文档级

- `07_success_casebook.md` 已追加本次成功案例
- `08_hardware_change_log.md` 已登记本次 RTL 改动
- 本次新增 RTL 修改若包含非直观逻辑，代码旁已补充中文注释
- 若产生新规则，`06_self_iteration_rules.md` 已更新
- 若未产生新规则，本次成功案例中已明确写明“无新增可推广规则”
- 若本轮曾怀疑或接触 `gvm.mk`，记录中已明确说明是否修改了它；除非脚本本身存在明确错误，否则不应把修改 `gvm.mk` 作为常规修复的一部分

## 7. 这次 `gaussian` 对应的最小验收标准

- `gaussian` 旧错误点不再出现
- `gaussian` 最终打印结果匹配
- 至少一次波形核对确认：
  - `st1` read-hit snapshot 生效
  - `memRsp_coreRsp` 使用的数据与当前 `instrId` 对齐
