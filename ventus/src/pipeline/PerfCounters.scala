/*
 * Copyright (c) 2021-2022 International Innovation Center of Tsinghua University, Shanghai
 * Ventus is licensed under Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan PSL v2.
 * You may obtain a copy of Mulan PSL v2 at:
 *          http://license.coscl.org.cn/MulanPSL2
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
 * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
 * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 * See the Mulan PSL v2 for more details. */
package pipeline

import chisel3._

class PipelinePerfCounters extends Bundle {
  val activeCycles = UInt(64.W)
  val totalScalarIssued = UInt(64.W)
  val totalVectorIssued = UInt(64.W)

  val execStructuralHazardCyclesX = UInt(64.W)
  val execStructuralHazardCyclesV = UInt(64.W)
  val dataDepStallCycles = UInt(64.W)
  val barrierStallCycles = UInt(64.W)
  val controlHazardFlushCount = UInt(64.W)
  val frontendStallCycles = UInt(64.W)
  val lsuBackpressureCycles = UInt(64.W)
  val ibufferFullCycles = UInt(64.W)
}

class InstClassPerfCounters extends Bundle {
  val computeIssued = UInt(64.W)
  val memIssued = UInt(64.W)
  val ctrlIssued = UInt(64.W)

  val ctrlBranchIssued = UInt(64.W)
  val ctrlBarrierIssued = UInt(64.W)
  val ctrlCsrIssued = UInt(64.W)
  val ctrlSimtStackIssued = UInt(64.W)
  val ctrlFenceIssued = UInt(64.W)

  val memLoadIssued = UInt(64.W)
  val memStoreIssued = UInt(64.W)
  val memAtomicIssued = UInt(64.W)

  val computeSaluIssued = UInt(64.W)
  val computeValuIssued = UInt(64.W)
  val computeFpuIssued = UInt(64.W)
  val computeMulIssued = UInt(64.W)
  val computeSfuIssued = UInt(64.W)
  val computeTensorCoreIssued = UInt(64.W)
}
