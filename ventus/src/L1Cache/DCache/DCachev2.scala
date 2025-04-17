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
package L1Cache.DCache

import L1Cache.DCache.DCacheParameters._
import L1Cache._
import SRAMTemplate._
import chisel3._
import chisel3.util._
import config.config.Parameters
import firrtl.Utils._
import top.parameters.{MMU_ENABLED, NUMBER_CU, dcache_BlockOffsetBits, dcache_BlockWords, dcache_MshrEntry, dcache_NSets, dcache_WordOffsetBits, num_block, num_thread}
import mmu.SV32.{asidLen, paLen, vaLen}

class DataCachev2(SV: Option[mmu.SVParam] = None)(implicit p: Parameters) extends DCacheModule{
  val io = IO(new Bundle{
    val coreReq = Flipped(DecoupledIO(new DCacheCoreReq(SV)))
    val coreRsp = DecoupledIO(new DCacheCoreRsp)
    val memRsp = Flipped(DecoupledIO(new DCacheMemRsp))
    val memReq = if(MMU_ENABLED) Some(DecoupledIO(new DCacheMemReq_p)) else Some(DecoupledIO(new DCacheMemReq))
    val TLBRsp = if(MMU_ENABLED) Some(Flipped(DecoupledIO(new mmu.L1TlbRsp(SV.getOrElse(mmu.SV32))))) else None
    val TLBReq = if(MMU_ENABLED) Some(DecoupledIO(new mmu.L1TlbReq(SV.getOrElse(mmu.SV32)))) else None
  })
  // submodules
  val TagAccess = Module(new L1TagAccess(set=NSets, way=NWays, tagBits=TagBits,AsidBits = asidLen,readOnly=false))
  val WshrAccess = Module(new DCacheWSHR(Depth = NWshrEntry))
  val ReplayTable = Module(new L1RTAB())
  val MshrAccess = Module(new MSHR(bABits = bABits, tIWidth = tIBits, WIdBits = WIdBits, NMshrEntry, NMshrSubEntry, asidLen))
  val SMshrAccess = Module(new SpecialMSHR(bABits = bABits, tIWidth = tIBits, WIdBits = WIdBits, NMshrEntry, asidLen))
  val DataAccesses = Seq.tabulate(BlockWords) { i =>
    Module(new SRAMTemplate(
      gen=UInt(8.W),
      set=NSets*NWays,
      way=BytesOfWord,
      shouldReset = false,
      holdRead = false,
      singlePort = false
    ))
  }
  val DataAccessRRsp = DataAccesses.map(d => d.io.r.resp.data)
  val DataAccessReadSRAMRRsp = VecInit[Vec[UInt]](DataAccessRRsp)
  // pipelines
  val coreReqPipe = Module(new CoreReqPipe)
  val memRspPipe = Module(new MemRspPipe)
  val memRsp_Q = Module(new Queue(new DCacheMemRsp,entries = 2,flow=false,pipe=false))
  val memReq_Q = Module(new Queue(new WshrMemReq,entries = 8,flow=false,pipe=false))
  val MemReqArb = Module(new Arbiter(new WshrMemReq, 3))
  val CoreReqArb = Module(new Arbiter(new DCacheCoreReq, 2))
  io.memRsp <> memRsp_Q.io.enq
  CoreReqArb.io.in(0) <> ReplayTable.io.coreReq_replay
  CoreReqArb.io.in(1) <> io.coreReq
  io.coreReq.ready := CoreReqArb.io.in(0).ready && !ReplayTable.io.RTAB_full
  // coreReqPipe connection
  coreReqPipe.io.CoreReq       <> CoreReqArb.io.out   
  coreReqPipe.io.RTABHit       := ReplayTable.io.checkRTABhit
  coreReqPipe.io.hasDirty      := TagAccess.io.hasDirty_st0.get
  coreReqPipe.io.MSHREmpty     := MshrAccess.io.empty
  coreReqPipe.io.SMSHREmpty    := SMshrAccess.io.empty

  MshrAccess.io.probe          := coreReqPipe.io.Probe_MSHR
  if(MMU_ENABLED){
    MshrAccess.io.probeAsid.get      := coreReqPipe.io.probeAsid.get
  }
  TagAccess.io.probeRead       := coreReqPipe.io.Probe_tA   
  coreReqPipe.io.Req_st0_RTAB  <> ReplayTable.io.RTABReq_st0

  coreReqPipe.io.tA_Hit_st1       := TagAccess.io.hitStatus_st1
  coreReqPipe.io.MSHR_ProbeStatus := MshrAccess.io.probeOut_st1
  coreReqPipe.io.SMSHR_ProbeStatus := SMshrAccess.io.probeOut_st1
  coreReqPipe.io.WSHR_CheckResult := WshrAccess.io.checkresult
  coreReqPipe.io.Mshr_st1_ready   := MshrAccess.io.missReq.ready

  TagAccess.io.tagFromCore_st1    := coreReqPipe.io.tagFromCore_tA_st1
  if(MMU_ENABLED){
    TagAccess.io.asidFromCore_st1.get := coreReqPipe.io.asidFromCore_tA_st1.get
  }
  ReplayTable.io.RTABReq_st1      <> coreReqPipe.io.Req_st1_RTAB
  WshrAccess.io.checkReq := coreReqPipe.io.CheckReq_WSHR
  SMshrAccess.io.missReq          <> coreReqPipe.io.Probe_SMSHR
  MshrAccess.io.missReq           <> coreReqPipe.io.MissReq_MSHR
  coreReqPipe.io.memRsp_coreRsp   <> memRspPipe.io.memRsp_coreRsp

  coreReqPipe.io.dA_data          <> DataAccessReadSRAMRRsp

  io.coreRsp <> coreReqPipe.io.CoreRsp

  // memRspPipe connection
  memRsp_Q.io.enq <> io.memRsp
  memRsp_Q.io.deq <> memRspPipe.io.memRsp
  TagAccess.io.allocateWrite      <> memRspPipe.io.tAAllocateWriteReq
  ReplayTable.io.RTABUpdate       <> memRspPipe.io.RTABUpdateReq
  MshrAccess.io.missRspIn         <> memRspPipe.io.MSHRMissRsp
  SMshrAccess.io.missRspIn        <> memRspPipe.io.SMSHRMissRsp
  WshrAccess.io.popReq            <> memRspPipe.io.WSHRPopReq

  memRspPipe.io.MSHRMissRspOut    <> MshrAccess.io.missRspOut
  memRspPipe.io.SMSHRMissRspOut   <> SMshrAccess.io.missRspOut
  if(MMU_ENABLED){
    memRspPipe.io.MSHRMissRspOutAsid.get := MshrAccess.io.missRspOutAsid.get
    memRspPipe.io.SMSHRMissRspOutAsid.get := SMshrAccess.io.missRspOutAsid.get
  }
  memRspPipe.io.tAWayMask   := TagAccess.io.waymaskReplacement_st1
  memRspPipe.io.needReplace := TagAccess.io.needReplace.get


  

  MemReqArb.io.in(1) <> coreReqPipe.io.MissReq_Mem

}