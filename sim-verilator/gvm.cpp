// GVM 核心类的实现

#include <cstdint>
#include <map>
#include <vector>
#include <memory>
#include <spdlog/logger.h>

#include "gvmref_interface.h"
#include "gvm_global_var.hpp"
#include "gvm.hpp"
#include "gvm_structs.hpp"
#include <bitset>

//
// ------------------------- insn related ----------------------------------------
//

bool gvm_t::isInsnCare(uint32_t insn, const std::vector<care_insn_t> care_insns) {
  // 判断指令是否 care
  for (const auto& pattern : care_insns) {
    if ((insn & pattern.mask) == pattern.value) {
      return true;
    }
  }
  return false;
}

void gvm_t::disasm(uint32_t insn, char* insn_name) {
  for (const auto& pattern : disasm_table) {
    if ((insn & pattern.mask) == pattern.value) {
      strcpy(insn_name, pattern.name);
      return;
    }
  }
  strcpy(insn_name, " ");
  return;
}

//
// ------------------------- gvm_t::getDut() ----------------------------------------------
//

static uint32_t pack_with_bitset(const std::array<bool,32> &a) {
  std::bitset<32> bs;
  for (size_t i = 0; i < 32; ++i) bs[i] = a[i]; // bs[0] 为最低位
  return static_cast<uint32_t>(bs.to_ulong());
}

void gvm_t::getDut() {
  getDutWarpNew(); // 添加新 warp 条目
  getDutWarpFinish(); // 删除已完成 warp 条目
  getDutInsnDispatch(); // 添加新指令条目，其中不关心的指令直接置为 single_insn_cmp.cmp_pass = 1
  getDutInsnFinish(); // 标记指令条目为已完成，维护 dut_done 与 dut_result
  getDutXReg(); // 根据 warp 条目更新 XReg 条目
  getDutWarpNewSetRefXReg();
  clearGlobal(); // 清空全局变量
}

void gvm_t::getDutWarpNew() {
  for (const auto& item : g_cta2warp_data) {
    dut_active_warp_t d;
    d.sm_id = item.sm_id;
    d.hardware_warp_id = item.hardware_warp_id;
    d.software_wg_id = item.software_wg_id;
    d.software_warp_id = item.software_warp_id;
    d.xreg_base = item.sgpr_base;
    d.xreg_usage = g_sgprUsage; // 临时特殊处理
    // d.vreg_base = item.vgpr_base;
    d.base_dispatch_id_set = 0;
    d.wg_slot_id_in_warp_sche = item.wg_slot_id_in_warp_sche;

    // check if the warp is already in the list
    bool found_sw = false;
    bool found_hw = false;
    for (const auto& warp : dut_active_warps) {
      if (warp.second.software_wg_id == d.software_wg_id && warp.second.software_warp_id == d.software_warp_id) {
        found_sw = true;
      }
      if (warp.second.sm_id == d.sm_id && warp.second.hardware_warp_id == d.hardware_warp_id) {
        found_hw = true;
      }
    }
    if (found_sw) {
      printf("GVM error: repeated cta2warp dispatch with same software_wg_id & software_warp_id.\n");
      assert(0);
    }
    if (found_hw) {
      printf("GVM error: repeated cta2warp dispatch. sm_id & hardware_warp_id already occupied.\n");
      assert(0);
    }
    dut_active_warps[{d.software_wg_id, d.software_warp_id}] = d;
  }
}

void gvm_t::getDutWarpFinish() {
  for (const auto& item : g_insn_dispatch_data) {
    if (item.insn == 0x0000400B) {
      // 0x0000400B 是 endprg 指令
      // delete dut_active_warp
      bool found_dut_active_warp = false;
      for (auto warp_it = dut_active_warps.begin(); warp_it != dut_active_warps.end();) {
        if (warp_it->second.sm_id == item.sm_id && warp_it->second.hardware_warp_id == item.hardware_warp_id) {
          if (found_dut_active_warp) {
            printf("GVM error in `gvm_t::getDutWarpFinish`: "
              "multiple items in `dut_active_warps` with same sm_id and hardware_warp_id\n");
            assert(0);
          }
          logger->debug(fmt::format("GVM info: endprg dispatched, deleting warp with sm_id: {}, hardware_warp_id: {}\n",
            warp_it->second.sm_id, warp_it->second.hardware_warp_id));
          warp_it = dut_active_warps.erase(warp_it);
          found_dut_active_warp = true;
        } else {
          ++warp_it;
        }
      }
      if (!found_dut_active_warp) {
        printf("GVM error in `gvm_t::getDutWarpFinish`: "
          "no item in `dut_active_warps` with required sm_id and hardware_warp_id\n");
        assert(0);
      }
      // 这里最好把对应的 ref warp 跑到头
    }
  }
}

void gvm_t::getDutInsnDispatch() {
  for (const auto& item : g_insn_dispatch_data) {
    insn_t d;
    d.pc = item.pc;
    d.insn = item.insn;
    d.extended = item.is_extended;
    d.care = isInsnCare(item.insn, retire_care_insns);
    d.done = 0;
    d.retired = 0;
    d.single_insn_cmp.care = isInsnCare(item.insn, single_insn_cmp_care_insns);
    d.single_insn_cmp.dut_done = 0;
    d.single_insn_cmp.ref_done = 0;
    d.single_insn_cmp.cmp_pass = 0;
    if (!d.single_insn_cmp.care) {
      d.single_insn_cmp.cmp_pass = 1; // 不关心的指令直接置为比对通过
    }
    d.dispatch_id = item.dispatch_id;

    bool found = false;
    for (auto& warp : dut_active_warps) {
      if (warp.second.sm_id == item.sm_id && warp.second.hardware_warp_id == item.hardware_warp_id) {
        if (found) {
          printf("GVM error in `gvm_t::getDutInsnDispatch`: "
            "multiple item in `dut_active_warps` with same sm_id and hardware_warp_id\n");
          assert(0);
        }
        assert(warp.second.insns.find(d.dispatch_id) == warp.second.insns.end()); // 错误：指令已被 dispatch 过一次
        warp.second.insns[d.dispatch_id] = d;
        found = true;
        if (!warp.second.base_dispatch_id_set) {
          // 设置本 warp 的首条指令的 dispatch_id
          warp.second.base_dispatch_id = d.dispatch_id;
          warp.second.base_dispatch_id_set = 1;
          warp.second.next_retire_dispatch_id = d.dispatch_id;
        }
      }
    }
    // 目前获取 warp 结束的标志是 endprg dispatch，
    // 但这距离 endprg 真正执行完成还有一段时间，
    // 在此期间仍有其他指令会 dispatch，但 dut_active_warps 中的 warp 已经被删除了
  }
}

void gvm_t::getDutInsnFinish() {
  // 将 insns 中 care 的指令标记为已完成
  // 并记录 insn_t.single_insn_cmp 中 care 的指令的完成信息
  getDutXRegWbFinish();
  getDutVRegWbFinish();
  getDutBarDone();
}
void gvm_t::getDutXRegWbFinish() {
  for (const auto& item : g_xreg_wb_data) {
    if (isInsnCare(item.insn, retire_care_insns) || isInsnCare(item.insn, single_insn_cmp_care_insns)) {
      bool found_warp = false;
      for (auto& warp : dut_active_warps) {
        if (warp.second.sm_id == item.sm_id && warp.second.hardware_warp_id == item.hardware_warp_id) {
          found_warp = true;
          auto insn_it = warp.second.insns.find(item.dispatch_id);
          if ((insn_it != warp.second.insns.end()) && (insn_it->second.done != 1)) {
            // 如果找到了该条指令，并且该指令尚未被标记为已完成
            assert(insn_it->second.pc == item.pc);
            assert(insn_it->second.insn == item.insn);
            assert(insn_it->second.care == true); // 标量寄存器写回指令需指导 retire
            // 维护 retire 相关变量
            insn_it->second.done = true;
            // 维护 single insn cmp 相关变量
            if (insn_it->second.single_insn_cmp.care == true) {
              insn_it->second.single_insn_cmp.dut_done = 1;
              insn_it->second.single_insn_cmp.dut_result.insn_type = InsnType::XREG;
              insn_it->second.single_insn_cmp.dut_result.xreg_result.rd = item.rd;
              insn_it->second.single_insn_cmp.dut_result.xreg_result.reg_idx = item.reg_idx;
            }
          } else {
            logger->error(
                "GVM error in `gvm_t::getDutInsnFinish`: "
                "sm_id & hardware_warp_id match successful, but no item in this warp's unfinished "
                "dispatched insns with required dispatch_id"
            );
            logger->error(
                "getDutInsnFinish Error: sm_id: {}, hardware_warp_id: {}, dispatch_id: {}, pc: 0x{:08x}, insn: "
                "0x{:08x}",
                item.sm_id, item.hardware_warp_id, item.dispatch_id, item.pc, item.insn
            );
            // assert(0);
          }
        }
      }
      if (!found_warp) {
        // logger->error("GVM error in `gvm_t::getDutXRegWbFinish`: "
        //   "no warp in `dut_active_warps` with required sm_id and hardware_warp_id\n"
        //   "getDutXRegWbFinish Error: sm_id: {}, hardware_warp_id: {}, dispatch_id: {}, pc: 0x{:08x}, insn: 0x{:08x}",
        //   item.sm_id, item.hardware_warp_id, item.dispatch_id, item.pc, item.insn);
        // assert(0);
      }
    }
  }
}

void gvm_t::getDutVRegWbFinish() {
  for (const auto& item : g_vreg_wb_data) {
    if (isInsnCare(item.second.insn, retire_care_insns) || isInsnCare(item.second.insn, single_insn_cmp_care_insns)) {
      bool found_warp = false;
      for (auto& warp : dut_active_warps) {
        if ((warp.second.sm_id == item.second.sm_id) && (warp.second.hardware_warp_id == item.second.hardware_warp_id)) {
          found_warp = true;
          auto insn_it = warp.second.insns.find(item.second.dispatch_id);
          if (insn_it != warp.second.insns.end() && (insn_it->second.single_insn_cmp.dut_done != 1)) {
            // 如果找到了该条指令，并且该指令尚未被标记为已完成
            assert(insn_it->second.pc == item.second.pc);
            assert(insn_it->second.insn == item.second.insn);
            assert(insn_it->second.care == false); // 向量寄存器写回指令不参与 retire
            // 维护 single insn cmp 相关变量
            if (insn_it->second.single_insn_cmp.care == true) {
              insn_it->second.single_insn_cmp.dut_done = 1;
              insn_it->second.single_insn_cmp.dut_result.insn_type = InsnType::VREG;
              insn_it->second.single_insn_cmp.dut_result.vreg_result.rd = item.second.rd_data;
              insn_it->second.single_insn_cmp.dut_result.vreg_result.reg_idx = item.second.reg_idx;
              insn_it->second.single_insn_cmp.dut_result.vreg_result.mask = pack_with_bitset(item.second.wvd_mask);
            }
          } else {
            logger->error(
                "GVM error in `gvm_t::getDutVRegWbFinish`: "
                "sm_id & hardware_warp_id match successful, but no item in this warp's unfinished "
                "dispatched insns with required dispatch_id"
            );
            logger->error(
                "getDutVRegWbFinish Error: sm_id: {}, hardware_warp_id: {}, dispatch_id: {}, pc: 0x{:08x}, insn: 0x{:08x}",
                item.second.sm_id, item.second.hardware_warp_id, item.second.dispatch_id, item.second.pc, item.second.insn
            );
            // assert(0);
          }
        }
      }
      if (!found_warp) {
        // logger->error("GVM error in `gvm_t::getDutVRegWbFinish`: "
        //   "no warp in `dut_active_warps` with required sm_id and hardware_warp_id\n"
        //   "getDutVRegWbFinish Error: sm_id: {}, hardware_warp_id: {}, dispatch_id: {}, pc: 0x{:08x}, insn: 0x{:08x}",
        //   item.second.sm_id, item.second.hardware_warp_id, item.second.dispatch_id, item.second.pc, item.second.insn);
        // assert(0);
      }
    }
  }
}

void gvm_t::getDutBarDone() {
  for (const auto& item : g_bar_done_data) {
    if (isInsnCare(item.insn, retire_care_insns) || isInsnCare(item.insn, single_insn_cmp_care_insns)) {
      bool found = false;
      for (auto& warp: dut_active_warps) {
        if (warp.second.sm_id == item.sm_id && warp.second.wg_slot_id_in_warp_sche == item.wg_slot_id) {
          for (auto& insn: warp.second.insns) {
            if (insn.second.pc == item.pc) {
              if(insn.second.done == false) {
                found = true;
              }
              // 为什么这里不用 dispatch_id 来识别指令？因为各个 warp 的分支行为可能不同
              // 进而导致对同一条 barrier 指令的 dispatch_id 不同
              // 而这里拿到的 dispatch_id 只是最后一个完成的 warp 的 dispatch_id
              // 因此使用 pc 识别 barrier 指令
              // 激进地假设不会同时存在两个相同 pc 的未 retire 的 barrier 指令
              assert(insn.second.care == true); // barrier 指令需指导 retire
              insn.second.done = true; // 标记该指令已完成
            }
          }
        }
      }
      if(found == false) {
        // logger->error("GVM error in `gvm_t::getDutBarDone`: "
        //   "no insn in `dut_active_warps` with required unfinished barrier insn\n"
        //   "getDutBarDone Error: sm_id: {}, wg_slot_id: {}, pc: 0x{:08x}, insn: 0x{:08x}",
        //   item.sm_id, item.wg_slot_id, item.pc, item.insn);
        // assert(0);
      }
    }
  }
}

void gvm_t::getDutXReg() {
  // 从交织的寄存器板块中，提取每个 warp 各自的寄存器
  std::map<uint32_t, std::map<uint32_t, XRegData>> g_xreg_data_mapped;
  assert(!g_xreg_data.empty());
  for (const auto& item : g_xreg_data) {
    g_xreg_data_mapped[item.sm_id][item.bank_id] = item;
  }
  for (auto& warp : dut_active_warps) {
    warp.second.curr_xreg.resize(warp.second.xreg_usage);
    // 将这个 warp 的寄存器从交织的板块中提取出来
    uint32_t num_bank = g_xreg_data_mapped[warp.second.sm_id].begin()->second.num_bank;
    assert((num_bank & (num_bank - 1)) == 0); // 断言 num_bank 是 2 的幂
    // 断言 warp 的寄存器是对齐到板块个数的
    assert(warp.second.xreg_base % num_bank == 0);
    assert(warp.second.xreg_usage % num_bank == 0);
    for (int i = 0; i < warp.second.xreg_usage; ++i) {
      warp.second.curr_xreg[i] = g_xreg_data_mapped[warp.second.sm_id]
        [(i + warp.second.hardware_warp_id) % num_bank].bank_data[(warp.second.xreg_base + i) >> __builtin_ctz(num_bank)];
    }
  }
}

void gvm_t::getDutWarpNewSetRefXReg() {
  // 这个函数是为了解决以下问题：
  // REF 对每一个 warp 的标量寄存器都是零初始化的；
  // 但 RTL DUT 的 CTA 调度器在向 SM 分派新 warp 时，只会在寄存器堆中分配一块空间，但不会零初始化；
  // 导致大量寄存器不匹配。
  // 因此这里在 DUT 的 CTA 调度器向 SM 分派新 warp 时，将 DUT 的该 warp 的寄存器数据同步到 REF 的对应 warp。
  for (const auto& item : g_cta2warp_data) {
    auto warp_it = dut_active_warps.find({ item.software_wg_id, item.software_warp_id });
    if (warp_it == dut_active_warps.end()) {
      logger->error("GVM error in `gvm_t::getDutWarpNewSetRefXReg`: "
        "no warp in `dut_active_warps` with required software_wg_id and software_warp_id\n"
        "getDutWarpNewSetRefXReg Error: software_wg_id: {}, software_warp_id: {}",
        item.software_wg_id, item.software_warp_id);
      assert(0);
    }
    auto &warp = warp_it->second;

    gvmref_warp_xreg_t xreg_data;
    xreg_data.xreg.resize(warp.xreg_usage);
    for (uint32_t i = 0; i < warp.xreg_usage; ++i) {
      xreg_data.xreg[i] = warp.curr_xreg[i];
    }
    gvmref_set_warp_xreg(item.software_wg_id, item.software_warp_id, warp.xreg_usage, xreg_data);
  }
}

void gvm_t::clearGlobal() {
  // 清空全局变量
  g_cta2warp_data.clear();
  g_insn_dispatch_data.clear();
  // g_sgprUsage.clear();
  g_xreg_wb_data.clear();
  g_xreg_data.clear();
  g_vreg_wb_data.clear();
  g_bar_done_data.clear();
}

//
// ------------------------- gvm_t::gvmStep() ----------------------------------------------
//

int gvm_t::gvmStep() {
  checkRetire(); // 判断是否有 retire 指令，并将相关信息写入 retire_info
  stepRef(); // 根据 retire_info 步进 REF，每次步进前确认 PC，维护 ref_done 与 ref_result
  doSingleInsnCmp();
  doRetireCmp(); // 根据 retire_info 比对 DUT 和 REF 的特定 warp 的寄存器堆
  clearInsnItem(); // 删除已 retire 且已 single_insn_cmp 通过的指令条目
  resetRetireInfo(); // 重置 retire_info
  return 0;
}


void gvm_t::checkRetire() {
  for (auto& warp: dut_active_warps) {
    uint32_t retire_cnt = 0;
    uint32_t extended_cnt = 0; // 统计最终 retire 范围内的 extended 指令数量

    auto temp_it = warp.second.insns.find(warp.second.next_retire_dispatch_id);
    assert(temp_it == warp.second.insns.end() || temp_it->second.retired == false);

    if (temp_it == warp.second.insns.end()) {
      continue;
    }

    // 1) 找到从 next_retire_dispatch_id 开始的前缀，满足 (done==1 || care==0)
    auto insn_it_begin = temp_it;
    auto it = insn_it_begin;
    for (; it != warp.second.insns.end(); ++it) {
      if (it->second.done == 1 || it->second.care == 0) {
        retire_cnt++;
      } else {
        break;
      }
    }
    if (retire_cnt == 0) {
      continue; // 没有可候选的前缀
    }

    // it 指向前缀后的第一个元素（或 end()）
    auto prefix_end = it; // 不包含

    // 2) 在前缀内从尾部向前剔除末尾连续的 care==0 元素，
    //    保证最终被 retire 的最后一个元素是 (care==1 && done==1)
    uint32_t final_cnt = retire_cnt;
    // last 指向前缀最后一个元素
    auto last = prefix_end;
    --last; // safe because retire_cnt > 0
    bool found_last_good = false;
    while (true) {
      if (last->second.care == 1 && last->second.done == 1) {
        found_last_good = true;
        break;
      }
      // 否则该元素是 care==0（因为在前缀内 must satisfy (done==1 || care==0)）
      if (last == insn_it_begin) {
        // 已到达前缀最前面，没有找到符合 (care==1 && done==1)
        final_cnt = 0;
        break;
      } else {
        // 剔除最后一个元素，向前移动
        --final_cnt;
        --last;
      }
    }

    if (final_cnt == 0 || !found_last_good) {
      continue; // 保守策略：前缀内没有以 care==1&&done==1 结尾的元素 => 不 retire
    }

    // 3) 统计最终 retire 区间内的 extended_cnt
    extended_cnt = 0;
    auto scan = insn_it_begin;
    for (uint32_t i = 0; i < final_cnt && scan != warp.second.insns.end(); ++i, ++scan) {
      if (scan->second.extended) extended_cnt++;
    }

    // 4) 确认后缀（从最终 retire 之后开始）没有已完成且被关心的指令
    auto suffix_it = insn_it_begin;
    for (uint32_t i = 0; i < final_cnt; ++i) {
      ++suffix_it;
    }
    bool ok = true;
    for (auto kt = suffix_it; kt != warp.second.insns.end(); ++kt) {
      if (kt->second.care == 1 && kt->second.done == 1) {
        ok = false;
        break;
      }
    }
    if (!ok) continue;

    // 5) 到这里可以 retire final_cnt 条指令（保守策略）
    retireInfo_t::retire_cnt_item_t r;
    r.sm_id = warp.second.sm_id;
    r.hardware_warp_id = warp.second.hardware_warp_id;
    r.software_wg_id = warp.second.software_wg_id;
    r.software_warp_id = warp.second.software_warp_id;
    r.retire_cnt = final_cnt;
    r.extended_cnt = extended_cnt;
    retire_info.warp_retire_cnt.push_back(r);

    logger->debug(fmt::format("GVM retire message from gvm_t::checkRetire()"));

    // 打印 retire log（遍历最终 retire 的那一段）
    auto print_it = insn_it_begin;
    for (uint32_t i = 0; i < final_cnt && print_it != warp.second.insns.end(); ++i, ++print_it) {
      char insn_name[64];
      disasm(print_it->second.insn, insn_name);
      logger->debug(fmt::format(
        "GVM retire: sm_id: {}, hardware_warp_id: {}, software_wg_id: {}, software_warp_id: {}, pc: 0x{:08x}, insn: 0x{:08x} {}",
        warp.second.sm_id, warp.second.hardware_warp_id, warp.second.software_wg_id,
        warp.second.software_warp_id, print_it->second.pc, print_it->second.insn, insn_name
      ));
    }
  }
}


void gvm_t::stepRef() {
  for (const auto& item : retire_info.warp_retire_cnt) {
    // 分别步进 REF 的每个 warp
    auto warp_it = dut_active_warps.find({ item.software_wg_id, item.software_warp_id });
    assert(warp_it != dut_active_warps.end());

    for (int i=0; i<item.retire_cnt; i++) {
      gvmref_step_return_info_t gvmref_step_return_info;

      auto& cur_insn = warp_it->second.insns[warp_it->second.next_retire_dispatch_id];

      if (cur_insn.extended) {
        gvmref_step(item.software_wg_id, item.software_warp_id, &gvmref_step_return_info); // 跳过 regext
      }

      // 确认 DUT 与 REF 的 PC 一致
      uint32_t next_gvmref_pc;
      next_gvmref_pc = gvmref_get_next_pc(item.software_wg_id, item.software_warp_id);
      uint32_t next_dut_pc;
      next_dut_pc = cur_insn.pc;
      assert(next_dut_pc == next_gvmref_pc);

      // 步进 REF 并维护 insn_t.single_insn_cmp
      gvmref_step(item.software_wg_id, item.software_warp_id, &gvmref_step_return_info);
      if (cur_insn.single_insn_cmp.care) {
        // 若为 single_insn_cmp.care 的指令，则从 spike 返回值中提取指令执行结果
        switch (gvmref_step_return_info.insn_result.insn_type) {
          // 对于相同的指令类型，gvmref 认为的指令集合可为 RTL 认为的指令集合的子集
          case XREG:
            assert(cur_insn.single_insn_cmp.cmp_pass == 0);
            cur_insn.single_insn_cmp.ref_done = 1;
            cur_insn.single_insn_cmp.ref_result.insn_type = InsnType::XREG;
            cur_insn.single_insn_cmp.ref_result.xreg_result.rd =
              gvmref_step_return_info.insn_result.xreg_result.rd;
            cur_insn.single_insn_cmp.ref_result.xreg_result.reg_idx =
              gvmref_step_return_info.insn_result.xreg_result.reg_idx;
            break;
          case VREG:
            assert(cur_insn.single_insn_cmp.cmp_pass == 0);
            cur_insn.single_insn_cmp.ref_done = 1;
            cur_insn.single_insn_cmp.ref_result.insn_type = InsnType::VREG;
            cur_insn.single_insn_cmp.ref_result.vreg_result.rd =
              gvmref_step_return_info.insn_result.vreg_result.rd;
            cur_insn.single_insn_cmp.ref_result.vreg_result.reg_idx =
              gvmref_step_return_info.insn_result.vreg_result.reg_idx;
            cur_insn.single_insn_cmp.ref_result.vreg_result.mask =
              gvmref_step_return_info.insn_result.vreg_result.mask;
            break;
        }
        if (cur_insn.single_insn_cmp.ref_done == 0) {
          logger->error(fmt::format("GVM error: suspected dut and ref insn-type mismatch on insn: 0x{:08x}."
            ,cur_insn.insn)); // cmp care 但 ref 返回的 insn type 非 care
        }
        cur_insn.single_insn_cmp.ref_done = 1;
        // 即便 gvmref_step_return_info.insn_result.insn_type 不在上面的 switch-case 中，也置为 ref_done = 1
        // 避免因 care == 1 但 ref_done 恒为 0 导致该条目永远无法删除
        // 在下面的 doSingleInsnCmp() 中会将其忽略而不进行比较（insn type switch-case 的 default）
      }
      cur_insn.retired = true;
      warp_it->second.next_retire_dispatch_id++;
    }
  }
}

int gvm_t::doSingleInsnCmp() {
  volatile int dut_active_warps_size = dut_active_warps.size();
  volatile auto dut_active_warps_begin = dut_active_warps.begin();
  volatile auto dut_active_warps_end = dut_active_warps.end();
  // assert(0);
  for (auto warpIt = dut_active_warps.begin(); warpIt != dut_active_warps.end(); ++warpIt) {
    for (auto insnIt = warpIt->second.insns.begin(); insnIt != warpIt->second.insns.end(); ++insnIt) {
      if (insnIt->second.single_insn_cmp.care) {
        if (insnIt->second.single_insn_cmp.dut_done && insnIt->second.single_insn_cmp.ref_done) {
          switch (insnIt->second.single_insn_cmp.dut_result.insn_type) {
            case InsnType::XREG:
              if ((insnIt->second.single_insn_cmp.dut_result.xreg_result.rd
                != insnIt->second.single_insn_cmp.ref_result.xreg_result.rd)
                || (insnIt->second.single_insn_cmp.dut_result.xreg_result.reg_idx
                != insnIt->second.single_insn_cmp.ref_result.xreg_result.reg_idx)) {
                logger->error(fmt::format(
                  "GVM error: DUT and REF insn result mismatch at software_wg_id {}, software_warp_id {}, pc 0x{:08x}, insn 0x{:08x}"
                  "insn_type XREG, DUT reg_idx: {}, REF reg_idx: {}, DUT rd: 0x{:08x}, REF rd: 0x{:08x}",
                  warpIt->second.software_wg_id, warpIt->second.software_warp_id, insnIt->second.pc, insnIt->second.insn,
                  insnIt->second.single_insn_cmp.dut_result.xreg_result.reg_idx,
                  insnIt->second.single_insn_cmp.ref_result.xreg_result.reg_idx,
                  insnIt->second.single_insn_cmp.dut_result.xreg_result.rd,
                  insnIt->second.single_insn_cmp.ref_result.xreg_result.rd
                ));
                insnIt->second.single_insn_cmp.cmp_pass = -1;
              }
              else {
                insnIt->second.single_insn_cmp.cmp_pass = 1;
              }
              break;
            case InsnType::VREG:
              if ((insnIt->second.single_insn_cmp.dut_result.vreg_result.mask
                != insnIt->second.single_insn_cmp.ref_result.vreg_result.mask)
                || (insnIt->second.single_insn_cmp.dut_result.vreg_result.reg_idx
                != insnIt->second.single_insn_cmp.ref_result.vreg_result.reg_idx))
              {
                logger->error(fmt::format(
                  "GVM error: DUT and REF vreg insn result writeback mask mismatch at software_wg_id {}, software_warp_id {}, pc 0x{:08x}, insn 0x{:08x}"
                  "insn_type VREG, DUT reg_idx: {}, REF reg_idx: {}, DUT mask: 0x{:08x}, REF mask: 0x{:08x}",
                  warpIt->second.software_wg_id, warpIt->second.software_warp_id, insnIt->second.pc, insnIt->second.insn,
                  insnIt->second.single_insn_cmp.dut_result.vreg_result.reg_idx,
                  insnIt->second.single_insn_cmp.ref_result.vreg_result.reg_idx,
                  insnIt->second.single_insn_cmp.dut_result.vreg_result.mask,
                  insnIt->second.single_insn_cmp.ref_result.vreg_result.mask
                ));
                insnIt->second.single_insn_cmp.cmp_pass = -1;
              }
              else {
                bool is_fp32 = isInsnCare(insnIt->second.insn, fp32_vreg_insns);
                for (int i = 0; i < 32; i++) {
                  if ((insnIt->second.single_insn_cmp.dut_result.vreg_result.mask & (1 << i)) != 0) {
                    if (is_fp32) {
                      // 比较浮点数
                      float dut_value = *reinterpret_cast<float*>(&insnIt->second.single_insn_cmp.dut_result.vreg_result.rd[i]);
                      float ref_value = *reinterpret_cast<float*>(&insnIt->second.single_insn_cmp.ref_result.vreg_result.rd[i]);
                      if (std::abs(dut_value - ref_value) > fp32_atol + fp32_rtol * std::abs(ref_value)) {
                        logger->error(fmt::format(
                          "GVM error: DUT and REF vreg mismatch at software_wg_id {}, software_warp_id {}, pc 0x{:08x}, insn 0x{:08x}, "
                          "vreg_idx {}, vec_element_idx {}, DUT value: {}, REF value: {}",
                          warpIt->second.software_wg_id, warpIt->second.software_warp_id, insnIt->second.pc, insnIt->second.insn,
                          insnIt->second.single_insn_cmp.dut_result.vreg_result.reg_idx,
                          i,
                          dut_value,
                          ref_value
                        ));
                        insnIt->second.single_insn_cmp.cmp_pass = -1;
                      }
                    }
                    else {
                      // 比较非浮点数
                      if (insnIt->second.single_insn_cmp.dut_result.vreg_result.rd[i]
                        != insnIt->second.single_insn_cmp.ref_result.vreg_result.rd[i]) {
                        logger->error(fmt::format(
                          "GVM error: DUT and REF vreg mismatch at software_wg_id {}, software_warp_id {}, pc 0x{:08x}, insn 0x{:08x}, "
                          "vreg_idx {}, vec_element_idx {}, DUT value: 0x{:08x}, REF value: 0x{:08x}",
                          warpIt->second.software_wg_id, warpIt->second.software_warp_id, insnIt->second.pc, insnIt->second.insn,
                          insnIt->second.single_insn_cmp.dut_result.vreg_result.reg_idx,
                          i,
                          insnIt->second.single_insn_cmp.dut_result.vreg_result.rd[i],
                          insnIt->second.single_insn_cmp.ref_result.vreg_result.rd[i]
                        ));
                        insnIt->second.single_insn_cmp.cmp_pass = -1;
                      }
                    }
                  }
                }
                if (insnIt->second.single_insn_cmp.cmp_pass == 0) {
                  insnIt->second.single_insn_cmp.cmp_pass = 1; // 比对通过
                }
              }
              break;
            default:
              insnIt->second.single_insn_cmp.cmp_pass = -2; // unknown insn type
              break;
          }
        }
      }
    }
  }
  return 0;
}

int gvm_t::doRetireCmp() {
  gvmref_xreg_t gvmref_xreg;
  gvmref_get_xreg(&gvmref_xreg);
  for (const auto& item : retire_info.warp_retire_cnt) {
    auto& warp = dut_active_warps[{item.software_wg_id, item.software_warp_id}];
    for (int i=0; i<warp.xreg_usage; i++) {
      if (static_cast<uint32_t>(gvmref_xreg.xpr[warp.software_wg_id][warp.software_warp_id][i]) != warp.curr_xreg[i]) {
        logger->error(fmt::format(
          "GVM error: DUT and REF xreg mismatch at software_wg_id {}, software_warp_id {}, reg x{}: DUT = 0x{:08x}, REF = 0x{:08x}",
          warp.software_wg_id, warp.software_warp_id, i,
          warp.curr_xreg[i],
          static_cast<uint32_t>(gvmref_xreg.xpr[warp.software_wg_id][warp.software_warp_id][i])
        ));
      }
    }
  }
  return 0;
}

void gvm_t::clearInsnItem() {
  for (auto& warp: dut_active_warps) {
    for (auto insn_it = warp.second.insns.begin(); insn_it != warp.second.insns.end(); ) {
      if ((insn_it->second.single_insn_cmp.cmp_pass != 0)
        && (insn_it->second.retired == true)) {
        insn_it = warp.second.insns.erase(insn_it);
      }
      else {
        break;
      }
    }
  }
}

void gvm_t::resetRetireInfo() {
  retire_info.warp_retire_cnt.clear();
}