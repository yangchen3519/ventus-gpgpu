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
import top.parameters.DCACHE_DEBUG
import scala.tools.nsc.interpreter.Repl

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
      singlePort = false,
      bypassWrite = true
    ))
  }
    // pipelines
  val coreReqPipe = Module(new CoreReqPipe)
  val memRspPipe = Module(new MemRspPipe)
  val memRsp_Q = Module(new Queue(new DCacheMemRsp,entries = 2,flow=false,pipe=false))
  val memReq_Q = Module(new Queue(new WshrMemReq,entries = 8,flow=false,pipe=false))
  val RTAB_pushedIdx_st2 = Module(new Queue(UInt(NRTABs.W),entries = 8,flow=false,pipe=false))
  val MemReqArb = Module(new Arbiter(new WshrMemReq, 2))
  val CoreReqArb = Module(new Arbiter(new DCacheCoreReq, 2))
  val dirtyReplaceMemReq = Wire(new WshrMemReq)
  for(i <- 0 until BlockWords){
    DataAccesses(i).io.r.req.valid := coreReqPipe.io.read_Req_dA.valid || memRspPipe.io.dAReplace_rReq_valid
    DataAccesses(i).io.r.req.bits := Mux(memRspPipe.io.dAReplace_rReq_valid,
      memRspPipe.io.dAReplace_rReq(i), coreReqPipe.io.read_Req_dA.bits(i))
    DataAccesses(i).io.w.req.valid := coreReqPipe.io.WriteHit_st1 && coreReqPipe.io.WriteReq_dA_valid(i) || memRspPipe.io.dAmemRsp_wReq_valid
    DataAccesses(i).io.w.req.bits := Mux(memRspPipe.io.dAmemRsp_wReq_valid,
      memRspPipe.io.dAmemRsp_wReq(i), coreReqPipe.io.WriteReq_dA(i))
  }
  val DataAccessRRsp = DataAccesses.map(d => d.io.r.resp.data)
  val DataAccessReadSRAMRRsp = DataAccessRRsp.map(d => Cat(d.reverse))

  io.memRsp <> memRsp_Q.io.enq
  // core request arbiter
  // source: RTAB top request / core request from io
  CoreReqArb.io.in(0) <> ReplayTable.io.coreReq_replay
  CoreReqArb.io.in(1) <> io.coreReq
  io.coreReq.ready := CoreReqArb.io.in(1).ready && !ReplayTable.io.RTAB_full
  //---------coreReqPipe input connection------------
  // st0
  coreReqPipe.io.CoreReq                <> CoreReqArb.io.out
  coreReqPipe.io.RTABHit                := ReplayTable.io.checkRTABhit
  coreReqPipe.io.hasDirty               := TagAccess.io.hasDirty_st0.get
  coreReqPipe.io.MSHREmpty              := MshrAccess.io.empty
  coreReqPipe.io.SMSHREmpty             := SMshrAccess.io.empty
  coreReqPipe.io.tA_dirtySetIdx_st0     := TagAccess.io.dirtySetIdx_st0.get
  coreReqPipe.io.tA_dirtyWayMask_st0    := TagAccess.io.dirtyWayMask_st0.get
  coreReqPipe.io.reqSource              := CoreReqArb.io.out.valid && ReplayTable.io.coreReq_replay.valid
  coreReqPipe.io.Probe_tA_ready         := TagAccess.io.probeRead.ready
  // st1
  coreReqPipe.io.tA_Hit_st1             := TagAccess.io.hitStatus_st1
  if(MMU_ENABLED){
    coreReqPipe.io.tA_dirtyAsid_st1.get := TagAccess.io.dirtyASID_st1.get
  }
  coreReqPipe.io.tA_dirtyTag_st1   := TagAccess.io.dirtyTag_st1.get
  coreReqPipe.io.MSHR_ProbeStatus  := MshrAccess.io.probeOut_st1
  coreReqPipe.io.SMSHR_ProbeStatus := SMshrAccess.io.probeOut_st1
  coreReqPipe.io.WSHR_CheckResult  := WshrAccess.io.checkresult
  coreReqPipe.io.Mshr_st1_ready    := MshrAccess.io.missReq.ready
  coreReqPipe.io.memRsp_coreRsp    <> memRspPipe.io.memRsp_coreRsp
 // st2
  coreReqPipe.io.dA_data          := DataAccessReadSRAMRRsp
  coreReqPipe.io.memRspIsFlu      := memRspPipe.io.memRspIsFlu
  //-----------core req pipe output connection------------
  // st0
  MshrAccess.io.probe.bits            := coreReqPipe.io.Probe_MSHR
  MshrAccess.io.probe.valid           := coreReqPipe.io.st0_valid
  if(MMU_ENABLED){
    MshrAccess.io.probeAsid.get  := coreReqPipe.io.probeAsid.get
  }
  TagAccess.io.probeRead.bits         := coreReqPipe.io.Probe_tA
  TagAccess.io.probeRead.valid        := coreReqPipe.io.st0_valid
  ReplayTable.io.RTABReq_st0     <> coreReqPipe.io.Req_st0_RTAB
  TagAccess.io.invalidateAll     := coreReqPipe.io.invalidate_tA
  TagAccess.io.flushChoosen.get  := coreReqPipe.io.flushDirty_tA
  // st1
  TagAccess.io.tagFromCore_st1        := coreReqPipe.io.tagFromCore_tA_st1
  TagAccess.io.probeIsWrite_st1.get       := coreReqPipe.io.coreReq_Control_st1.isWrite
  TagAccess.io.probeIsUncache_st1       := coreReqPipe.io.coreReq_Control_st1.isUncached
  TagAccess.io.tagready_st1    := coreReqPipe.io.st1_ready
  TagAccess.io.perLaneAddr_st1 := coreReqPipe.io.perLaneAddr_st1
  if(MMU_ENABLED){
    TagAccess.io.asidFromCore_st1.get := coreReqPipe.io.asidFromCore_tA_st1.get
  }
  ReplayTable.io.RTABReq_st1   <> coreReqPipe.io.Req_st1_RTAB
  WshrAccess.io.checkReq       := coreReqPipe.io.CheckReq_WSHR
  SMshrAccess.io.missReq       <> coreReqPipe.io.Probe_SMSHR
  MshrAccess.io.missReq        <> coreReqPipe.io.MissReq_MSHR
  MshrAccess.io.missCached_st1 := coreReqPipe.io.MissCached_MSHR
  MshrAccess.io.stage1_ready  := coreReqPipe.io.st1_ready
  SMshrAccess.io.stage1_ready := coreReqPipe.io.st1_ready

  io.coreRsp <> coreReqPipe.io.CoreRsp

  // ------memRspPipe input connection------
  memRsp_Q.io.enq <> io.memRsp
  memRspPipe.io.memRsp <> memRsp_Q.io.deq
  memRspPipe.io.MSHRMissRspOutUCached := MshrAccess.io.UncacheRsp
  memRspPipe.io.MSHRMissRspOut    <> MshrAccess.io.missRspOut
  memRspPipe.io.SMSHRMissRspOut   <> SMshrAccess.io.missRspOut
  if(MMU_ENABLED){
    memRspPipe.io.MSHRMissRspOutAsid.get := MshrAccess.io.missRspOutAsid.get
    memRspPipe.io.SMSHRMissRspOutAsid.get := SMshrAccess.io.missRspOutAsid.get
  }
  memRspPipe.io.tAWayMask   := TagAccess.io.waymaskReplacement_st1
  memRspPipe.io.needReplace := TagAccess.io.needReplace.get
  memRspPipe.io.memReq_ready := MemReqArb.io.in(0).ready

  //mem Rsp pipe output connection
  TagAccess.io.allocateWrite      <> memRspPipe.io.tAAllocateWriteReq
  ReplayTable.io.RTABUpdate       <> memRspPipe.io.RTABUpdateReq
  MshrAccess.io.missRspIn         <> memRspPipe.io.MSHRMissRsp
  SMshrAccess.io.missRspIn        <> memRspPipe.io.SMSHRMissRsp
  WshrAccess.io.popReq            <> memRspPipe.io.WSHRPopReq
  //rtab
  ReplayTable.io.mshrFull := MshrAccess.io.full
  ReplayTable.io.LRexist  := SMshrAccess.io.probeOut_st1.LRexist
  ReplayTable.io.pushedWSHRIdxUpdate.valid := WshrAccess.io.pushReq.valid
  ReplayTable.io.pushedWSHRIdxUpdate.bits.wshrIdx  := WshrAccess.io.pushedIdx
  ReplayTable.io.pushedWSHRIdxUpdate.bits.RTABIdx  := RTAB_pushedIdx_st2.io.deq.bits


  // dAmemRsp_wReq       
  // dAmemRsp_wReq_valid 
  // dAReplace_rReq      
  // dAReplace_rReq_valid
  // memReq_valid        
  // tAWayMask           
  // needReplace         
  // memReq_ready

  TagAccess.io.allocateWriteTagSRAMWValid_st1 := memRspPipe.io.dAmemRsp_wReq_valid
 TagAccess.io.allocateWriteData_st1 := get_tag(MshrAccess.io.missRspOut.bits.blockAddr)
  // tag access
  //mshr
  MshrAccess.io.stage2_ready  := MemReqArb.io.in(1).ready
  SMshrAccess.io.stage2_ready := MemReqArb.io.in(1).ready

  // memory request arbiter
  // source: dirty replace / core Req ( miss, uncached dirty writeback, flush and invalidate)
  MemReqArb.io.in(1) <> coreReqPipe.io.MissReq_Mem
  MemReqArb.io.in(0).valid := memRspPipe.io.memReq_valid
  MemReqArb.io.in(0).bits := dirtyReplaceMemReq
  memReq_Q.io.enq <> MemReqArb.io.out
  // todo NOT right!!
  RTAB_pushedIdx_st2.io.enq.valid := MemReqArb.io.out.valid
  RTAB_pushedIdx_st2.io.enq.bits  := ReplayTable.io.RTABpushedIdx
  RTAB_pushedIdx_st2.io.deq.ready := memReq_Q.io.deq.ready
  dirtyReplaceMemReq.a_opcode := 0.U//PutFullData
  dirtyReplaceMemReq.a_param := 0.U//regular write
  dirtyReplaceMemReq.a_source := DontCare//wait for WSHR
  dirtyReplaceMemReq.a_addr.get := RegNext(TagAccess.io.a_addrReplacement_st1.get)
  if(MMU_ENABLED){
    dirtyReplaceMemReq.Asid.get := RegNext(TagAccess.io.asidReplacement_st1.get)
  }
  dirtyReplaceMemReq.a_mask := VecInit(Seq.fill(BlockWords)(Fill(BytesOfWord,1.U)))
  dirtyReplaceMemReq.a_data := DataAccessReadSRAMRRsp//wait for data SRAM in next cycle
  dirtyReplaceMemReq.hasCoreRsp := false.B
  dirtyReplaceMemReq.coreRspInstrId := DontCare
  dirtyReplaceMemReq.spike_info.foreach{ _ := DontCare }

  // mem request
  val coreRsp_st2_valid_from_memReq = Wire(Bool())
  val waitTLB = if(MMU_ENABLED) Some(RegInit(0.U(2.W))) else None
  val waitTLBnext = if(MMU_ENABLED) Some(Wire(UInt(2.W))) else None
  val memReq_st3 = Reg(new DCacheMemReq)
  val memReq_st3_ready_tlb = if(MMU_ENABLED) Some(Wire(Bool())) else None
  val memReq_st3_valid_tlb = if(MMU_ENABLED) Some(Wire(Bool())) else None

  val memReq_st3_addr = if(MMU_ENABLED) Some(Reg(UInt(paLen.W))) else Some(Reg(UInt(vaLen.W)))
  val a_op_st3 = memReq_Q.io.deq.bits.a_opcode//memReq_Q.io.deq.bits.a_opcode
  val a_op_st3_isFlush = a_op_st3 === 5.U
  val memReqIsWrite_st3 = (a_op_st3 === TLAOp_PutFull) || ((a_op_st3 === TLAOp_PutPart) && memReq_Q.io.deq.bits.a_param === 0.U)
  val memReqIsRead_st3 = (a_op_st3 === TLAOp_Get) && memReq_Q.io.deq.bits.a_param === 0.U

  //memReq_Q.io.deq.bits.a_addr >> (WordLength - TagBits - SetIdxBits)
  val wshrProtect = WshrAccess.io.conflict && (memReqIsWrite_st3 || memReqIsRead_st3) && memReq_Q.io.deq.valid// && io.memReq.ready
  val cRspBlockedOrWshrFull = ((!coreReqPipe.io.st2_ready && memReq_Q.io.deq.bits.hasCoreRsp)
    || !WshrAccess.io.pushReq.ready) && memReqIsWrite_st3
  val wshrPass = !wshrProtect && !cRspBlockedOrWshrFull
  val PushWshrValid = wshrPass && memReq_Q.io.deq.fire && memReqIsWrite_st3
  // val WshrPushPopConflict = PushWshrValid && WshrAccess.io.popReq.valid
  // val wshrPushPopConflictReg = RegNext(WshrPushPopConflict)
    val pushReqbA = Wire(UInt((paLen - dcache_BlockOffsetBits - dcache_WordOffsetBits).W))//memReq_st3_addr.get >> (dcache_WordOffsetBits+dcache_BlockOffsetBits)
  // val pushReqbAReg = RegNext(pushReqbA)
  WshrAccess.io.pushReq.bits.blockAddr := pushReqbA//Mux(wshrPushPopConflictReg, pushReqbAReg, pushReqbA)
  if(MMU_ENABLED){
    pushReqbA := io.TLBRsp.get.bits.paddr  >> (dcache_WordOffsetBits+dcache_BlockOffsetBits)
  }
  else{
     pushReqbA := memReq_Q.io.deq.bits.a_addr.get  >> (dcache_WordOffsetBits+dcache_BlockOffsetBits)
  }
  WshrAccess.io.pushReq.valid := PushWshrValid//Mux(wshrPushPopConflictReg,true.B,Mux(WshrPushPopConflict,false.B,PushWshrValid))//wshrPass && memReq_Q.io.deq.fire() && memReqIsWrite_st3
  coreRsp_st2_valid_from_memReq := WshrAccess.io.pushReq.valid && memReq_Q.io.deq.bits.hasCoreRsp && !memRspPipe.io.memRsp_coreRsp.valid
  MMU_ENABLED match{
    case true =>{
      memReq_Q.io.deq.ready := Mux(a_op_st3_isFlush,io.memReq.get.ready && !memRspPipe.io.memRsp_coreRsp.valid,(waitTLB.get === 2.U) && (waitTLBnext.get === 0.U))
      waitTLBnext.get := waitTLB.get
      waitTLB.get := waitTLBnext.get
      when(waitTLB.get === 0.U){

        when(memReq_Q.io.deq.valid  && !a_op_st3_isFlush && io.TLBReq.get.ready){
          waitTLBnext.get := 1.U
        }.otherwise{
          waitTLBnext.get := waitTLB.get
        }
      }.elsewhen(waitTLB.get === 1.U){
        when(io.TLBRsp.get.valid){
          waitTLBnext.get := 2.U
        }.otherwise{
          waitTLBnext.get := waitTLB.get
        }
      }.elsewhen(waitTLB.get === 2.U){
        when(wshrPass && io.memReq.get.ready && !memRspPipe.io.memRsp_coreRsp.valid){
          waitTLBnext.get := 0.U
        }.otherwise{
          waitTLBnext.get := waitTLB.get
        }
      }.otherwise{
        waitTLBnext.get := 0.U
      }
      io.TLBRsp.get.ready := waitTLB.get === 1.U
      io.TLBReq.get.valid := memReq_Q.io.deq.valid && waitTLB.get === 0.U && !a_op_st3_isFlush
      io.TLBReq.get.bits.vaddr := memReq_Q.io.deq.bits.a_addr.get
      io.TLBReq.get.bits.asid := memReq_Q.io.deq.bits.Asid.get
      memReq_st3_ready_tlb.get := io.TLBReq.get.ready && waitTLB.get === 0.U
      memReq_st3_valid_tlb.get := io.TLBRsp.get.valid && waitTLB.get === 1.U

      when(memReq_Q.io.deq.valid && (memReq_st3_ready_tlb.get || a_op_st3_isFlush)) {
        memReq_st3.a_data := memReq_Q.io.deq.bits.a_data
        memReq_st3.a_param := memReq_Q.io.deq.bits.a_param
        memReq_st3.a_addr.get := memReq_Q.io.deq.bits.a_addr.get
        memReq_st3.a_mask := memReq_Q.io.deq.bits.a_mask
        memReq_st3.a_opcode := memReq_Q.io.deq.bits.a_opcode
        memReq_st3.spike_info.foreach{ _ := memReq_Q.io.deq.bits.spike_info.getOrElse(0.U) }
      }
      when(memReq_st3_valid_tlb.get){
        memReq_st3_addr.get := io.TLBRsp.get.bits.paddr
      }


    }
    case false => {
      memReq_Q.io.deq.ready := wshrPass && io.memReq.get.ready && !memRspPipe.io.memRsp_coreRsp.valid
      when(wshrPass && memReq_Q.io.deq.fire) {
        memReq_st3_addr.get := memReq_Q.io.deq.bits.a_addr.get
      }
    }
  }
  //memReq_Q.io.deq.ready := Mux(a_op_st3_isFlush,io.memReq.get.ready && !coreRsp_st2_valid_from_memRsp,(waitTLB === 2.U) && (waitTLBnext === 0.U))//wshrPass && io.memReq.ready && !coreRsp_st2_valid_from_memRsp

  // FSM for TLB handle and memreq transmit
  // 0-idle 1-wait TLB resp 2-issue memreq

  when(wshrPass && memReq_Q.io.deq.fire) {
    memReq_st3 := memReq_Q.io.deq.bits
  }

  val memReqSetIdx_st2 = memReq_Q.io.deq.bits.a_addr.get(WordLength - TagBits -1,WordLength - TagBits - SetIdxBits)
  when(memReqIsWrite_st3 && memReq_Q.io.deq.fire){
    memReq_st3.a_source := Cat("d0".U, WshrAccess.io.pushedIdx, memReqSetIdx_st2)
    //memReq_st3.a_source := Cat("d0".U, 0.U((log2Up(NMshrEntry)-log2Up(NWshrEntry)).W), WshrAccess.io.pushedIdx, coreReq_st1.setIdx)
  }.elsewhen(memReqIsRead_st3 && memReq_Q.io.deq.valid){
    memReq_st3.a_source := memReq_Q.io.deq.bits.a_source
  }

  val coreRspFromMemReq = Wire(new DCacheCoreRsp)
  coreReqPipe.io.memReq_coreRsp.bits := coreRspFromMemReq
  coreReqPipe.io.memReq_coreRsp.valid := coreRsp_st2_valid_from_memReq
  coreRspFromMemReq.data := DontCare
  coreRspFromMemReq.isWrite := true.B
  //st指令的regIdx对SM流水线提交级无意义，且memReq_Q没有传输该数据的通道
  coreRspFromMemReq.instrId := memReq_Q.io.deq.bits.coreRspInstrId
  coreRspFromMemReq.activeMask := DontCare//coreRsp_st2.io.deq.bits.activeMask//VecInit(Seq.fill(NLanes)(true.B))
  // memReq(st3)
  io.memReq.get.bits := memReq_st3
  io.memReq.get.bits.a_addr.get := memReq_st3_addr.get

  val memReq_valid = RegInit(false.B)
  when(memReq_Q.io.deq.fire ^ io.memReq.get.fire){
    memReq_valid := memReq_Q.io.deq.fire
  }
  io.memReq.get.valid := memReq_valid
  // print 
  if(DCACHE_DEBUG){
    when(io.coreReq.fire){
      printf(p"---- REQ: \n instrId = ${io.coreReq.bits.instrId}, opcode = ${io.coreReq.bits.opcode},tag=${Hexadecimal(io.coreReq.bits.tag)},")
      printf(p"set=${Hexadecimal(io.coreReq.bits.setIdx)},data0 = ${Hexadecimal(io.coreReq.bits.data(0))}\n")
    }    
    when(io.coreRsp.valid){
      printf(p"++++ RSP: \n instrId = ${io.coreRsp.bits.instrId}, data0 = ${Hexadecimal(io.coreRsp.bits.data(0))}\n")
    }
    when(io.memReq.get.fire){
      printf(p"===mem Req from cache addr = ${Hexadecimal(io.memReq.get.bits.a_addr.get)}, opcode = ${io.memReq.get.bits.a_opcode}, param = ${io.memReq.get.bits.a_param}\n")
    }
  }
}