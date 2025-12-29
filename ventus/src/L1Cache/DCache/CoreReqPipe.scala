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
package L1Cache

import L1Cache.DCache._
import SRAMTemplate._
import chisel3._
import chisel3.util._
import config.config.Parameters
import top.parameters._
import mmu.SV32.{asidLen, paLen, vaLen}

class CoreReqPipe_st1(implicit p: Parameters) extends DCacheBundle{
  val Req  = new DCacheCoreReq
  val Ctrl = new DCacheControl
}
class CoreRspPipe_st2(implicit p: Parameters) extends DCacheBundle{
  val Rsp = new DCacheCoreRsp_d
  val perLaneAddr = Vec(NLanes, new DCachePerLaneAddr)
  val validFromCoreReq = Bool()
}
class CoreReqPipe(implicit p: Parameters) extends DCacheModule{
  val io = IO(new Bundle{
    //st0
    val CoreReq        = Flipped(DecoupledIO(new DCacheCoreReq))
    val RTABHit        = Input(Bool())
    val hasDirty       = Input(Bool())
    val MSHREmpty      = Input(Bool())
    val SMSHREmpty     = Input(Bool())
    val tA_dirtySetIdx_st0 = Input(UInt(dcache_SetIdxBits.W))
    val tA_dirtyWayMask_st0= Input(UInt(dcache_NWays.W))
    val reqSource      = Input(Bool()) // 1- from RTAB 0 - from io

    val Probe_MSHR     = Output(new MSHRprobe(bABits, asidLen))
    val probeAsid      = if(MMU_ENABLED) {Some(Output(UInt(asidLen.W)))} else None
    val Probe_tA       = Output(new SRAMBundleA(NSets))  // todo have ready issue
    val Probe_tA_ready = Input(Bool())
    val Req_st0_RTAB   = Valid(new RTABReq())
    val flushDirty_tA  = Output(Bool())

    val st0_ready      = Output(Bool())
    val st0_valid      = Output(Bool())

    //st1
    
    val tA_Hit_st1          = Input(new hitStatus(NWays, TagBits))
    val tA_dirtyTag_st1     = Input(UInt(TagBits.W))
    val tA_dirtyAsid_st1    = if(MMU_ENABLED) {Some(Input(UInt(asidLen.W)))} else None
    val MSHR_ProbeStatus    = Input(new MSHRprobeOut(NMshrEntry, NMshrSubEntry))
    val SMSHR_ProbeStatus   = Input(new SMSHRprobeOut(NMshrEntry))
    val WSHR_CheckResult    = Input(new WSHRCheckResult(NWshrEntry))
    val Mshr_st1_ready      = Input(Bool())
    val memRsp_coreRsp      = Flipped(DecoupledIO(new CoreRspPipe_st2))

    val tagFromCore_tA_st1  = Output(UInt(dcache_TagBits.W))
    val asidFromCore_tA_st1 = if(MMU_ENABLED) {Some(Output(UInt(asidLen.W)))} else None
    val coreReq_Control_st1 = Output(new DCacheControl)
    val perLaneAddr_st1     = Output(Vec(NLanes, new DCachePerLaneAddr))
    val read_Req_dA         = ValidIO(Vec(BlockWords,new SRAMBundleA(NSets*NWays)))
    val CacheHit_st1        = Output(Bool())
    val Req_st1_RTAB        = ValidIO(new RTABReq())
    val CheckReq_WSHR       = Output(new WSHRreq)
    val Probe_SMSHR         = DecoupledIO(new SMSHRmissReq(bABits, tIBits, WIdBits, asidLen))//TODO add special MSHR
    val MissReq_MSHR        = DecoupledIO(new MSHRmissReq(bABits, tIBits, WIdBits, asidLen))
    val MissCached_MSHR     = Output(Bool())
    val st1_valid           = Output(Bool())
    val st1_ready           = Output(Bool())
    // missReq_Mem for read write miss and flu inv dirty write back
    val MissReq_Mem         = DecoupledIO(new WshrMemReq)
    val WriteReq_dA         = Output(Vec(BlockWords, new SRAMBundleAW(UInt(8.W), NSets * NWays, BytesOfWord)))
    val WriteReq_dA_valid   = Output(Vec(BlockWords,Bool()))
    val WriteHit_st1        = Output(Bool())

    //st2
    val dA_data        = Input(Vec(BlockWords, UInt(WordLength.W)))
    val memReq_coreRsp = Flipped(DecoupledIO(new DCacheCoreRsp))

    val CoreRsp = DecoupledIO(new DCacheCoreRsp)

    val memRspIsFlu = Input(Bool())
    val st2_ready = Output(Bool())
    val invalidate_tA = Output(Bool())
  })

  //====== st0 =======
  val st0_valid = Wire(Bool()) // for enqueue st1 pipe reg
  val st0_ready = Wire(Bool()) // for dequeue st0 pipe reg
  //Control Generate
  val Control = Module(new genControl)

  //====== st1 =======
  val CoreReq_pipeReg_st0_st1 = Module(new Queue(new CoreReqPipe_st1,entries = 1,flow=false,pipe=true)).io
  val ReplayType = Wire(UInt(4.W))
  val MshrIdx = Wire(UInt(log2Up(NMshrEntry).W))
  val WshrIdx = Wire(UInt(log2Up(NWshrEntry).W))
  val MshrStatus = Wire(UInt(3.W))
  val st1_valid = Wire(Bool())
  val st1_ready = Wire(Bool())
  // missReq st1
  val missMemReq_st1   = Wire(new WshrMemReq)
  val evictMemReq_st1  = Wire(new WshrMemReq)
  val FluInvMemReq_st1 = Wire(new WshrMemReq)
  val missMemReq_valid    = Wire(Bool())
  val evictMemReq_valid   = Wire(Bool())
  val FluInvMemReq_valid  = Wire(Bool())

  //====== st2 ======
  val CoreRsp_pipeReg_st1_st2 = Module(new Queue(new CoreRspPipe_st2,entries = 1, pipe = true,flow = false)).io
  val DataMemOrder_st2 = Wire(Vec(BlockWords, UInt(WordLength.W)))
  val DataCoreOrder_st2 = Wire(Vec(NLanes, UInt(WordLength.W)))
  val coreReq_st2_ready = Wire(Bool())
  // st3
  val coreRsp_Q_entries: Int = NLanes
  val CoreRsp_st3 = Module(new Queue(new DCacheCoreRsp,entries = coreRsp_Q_entries,flow=false,pipe=false))

  // submodule
  val OpcodeGen = Module(new ProConver)
  val addrGen = Module(new dataReqCrossBar)

  //=== st0 ===
  // tagaccess: proberead request, for tA(SRAM) to get the tags in the corresponding set and way
  // MSHR: probe, get the MSHR status, will decide the behavier in st1
  // RTAB: check hit status, if hit in RTAB, will go directly into RTAB without enqueue the st1 pipe reg
  val CoreReqControl_st0 = Control.io.control
  Control.io.opcode := io.CoreReq.bits.opcode
  Control.io.param  := io.CoreReq.bits.param

  val BlockAddr_st0 = Cat(io.CoreReq.bits.tag, io.CoreReq.bits.setIdx)

  //output bits
  io.Probe_MSHR.blockAddr := BlockAddr_st0
  if(MMU_ENABLED){
    io.probeAsid.get :=io.CoreReq.bits.asid.get
  }
  io.Probe_tA.setIdx := io.CoreReq.bits.setIdx
  io.Req_st0_RTAB.bits.CoreReqData := io.CoreReq.bits
  io.Req_st0_RTAB.bits.ReqType     := DontCare
  io.Req_st0_RTAB.bits.mshrIdx     := DontCare
  io.Req_st0_RTAB.bits.wshrIdx     := DontCare
  io.Req_st0_RTAB.valid            := io.RTABHit && io.CoreReq.valid && !io.reqSource
  io.CoreReq.ready := st0_ready
  //Flush L2 FSM
  val idle :: flushing :: responding :: Nil = Enum(3)
  val FlushInvstateReg = RegInit(idle)
  val FlushInvstateReg_next = WireInit(FlushInvstateReg)
  val fluInvReq_st0 = io.CoreReq.valid && (CoreReqControl_st0.isFlush || CoreReqControl_st0.isInvalidate)
  val fluInvStartOk_st0 = fluInvReq_st0 && io.MSHREmpty && io.SMSHREmpty
  FlushInvstateReg_next := FlushInvstateReg
  FlushInvstateReg := FlushInvstateReg_next
  when(FlushInvstateReg === idle){
    when(fluInvStartOk_st0 && !io.hasDirty && CoreReqControl_st0.isFluInvL2){
      FlushInvstateReg_next := flushing
  }.elsewhen(fluInvStartOk_st0 && !io.hasDirty){
    FlushInvstateReg_next := responding
  }
  }.elsewhen(FlushInvstateReg === flushing){
    when(io.memRspIsFlu){
      FlushInvstateReg_next := responding
    }
  }.elsewhen(FlushInvstateReg === responding){
    when(CoreReq_pipeReg_st0_st1.enq.ready && io.CoreReq.valid){
      FlushInvstateReg_next := idle
    }
  }
  val flushDirtyReq_st0 = fluInvStartOk_st0 && io.hasDirty && (FlushInvstateReg_next === idle)
  io.flushDirty_tA := flushDirtyReq_st0
  val FluInv_st1 = CoreReq_pipeReg_st0_st1.deq.bits.Ctrl.isFlush || CoreReq_pipeReg_st0_st1.deq.bits.Ctrl.isInvalidate
  val FluInvReq_st1_valid = CoreReq_pipeReg_st0_st1.deq.valid && FluInv_st1
  val FluInvIsPut_st1 = CoreReq_pipeReg_st0_st1.deq.valid && (FlushInvstateReg === idle) && FluInv_st1
  val FluInvIsFluL2_st1 =  CoreReq_pipeReg_st0_st1.deq.valid && (FlushInvstateReg === flushing) && FluInv_st1

  // valid ready
  // st0 st1 pipe reg enq valid
  st0_valid := false.B
  st0_ready := false.B
  when(!(io.RTABHit && !io.reqSource)){
    // probe SMSHR MSHR and tag
    when(CoreReqControl_st0.isRead || CoreReqControl_st0.isWrite|| CoreReqControl_st0.isAMO || CoreReqControl_st0.isLR || CoreReqControl_st0.isSC){
      st0_valid  := io.CoreReq.valid && io.Probe_tA_ready
      st0_ready := CoreReq_pipeReg_st0_st1.enq.ready && io.Probe_tA_ready
    }.elsewhen(CoreReqControl_st0.isWaitMSHR){
      //wait until MSHR empty
      st0_valid  := io.CoreReq.valid && io.MSHREmpty && io.SMSHREmpty
      st0_ready := CoreReq_pipeReg_st0_st1.enq.ready && io.MSHREmpty && io.SMSHREmpty
    }.elsewhen(CoreReqControl_st0.isFlush || CoreReqControl_st0.isInvalidate){
      
        when(FlushInvstateReg_next === idle && FlushInvstateReg === idle){
           when(!io.MSHREmpty || !io.SMSHREmpty){
             st0_valid := false.B
             st0_ready := false.B
           }.elsewhen(io.hasDirty){
             //write back dirty cacheline
             st0_valid  := io.CoreReq.valid
             st0_ready := false.B
           }
        }.elsewhen(FlushInvstateReg_next === flushing){
          st0_valid := io.CoreReq.valid && (FlushInvstateReg === idle)
          st0_ready := false.B
        }.elsewhen(FlushInvstateReg_next === responding){
          st0_valid := io.CoreReq.valid
          st0_ready := CoreReq_pipeReg_st0_st1.enq.ready
        }
    }
  }.otherwise{
    st0_valid := false.B
    st0_ready := true.B
  }
  io.invalidate_tA := (FlushInvstateReg === responding) && FluInvReq_st1_valid && CoreReq_pipeReg_st0_st1.deq.bits.Ctrl.isInvalidate
  io.st0_valid := st0_valid
  io.st0_ready := st0_ready

  //st0_ready := io.Probe_tA_ready && CoreReq_pipeReg_st0_st1.enq.ready
  // =============
  // st1 pipe reg
  val BlockAddr_st1 = Cat(CoreReq_pipeReg_st0_st1.deq.bits.Req.tag, CoreReq_pipeReg_st0_st1.deq.bits.Req.setIdx)
  CoreReq_pipeReg_st0_st1.enq.valid := st0_valid
  CoreReq_pipeReg_st0_st1.enq.bits.Req := io.CoreReq.bits
  CoreReq_pipeReg_st0_st1.enq.bits.Ctrl := CoreReqControl_st0
  //=== st1 ===

  io.tagFromCore_tA_st1 := CoreReq_pipeReg_st0_st1.deq.bits.Req.tag // check the tag from core with tag from tA block
  if(MMU_ENABLED){
    io.asidFromCore_tA_st1.get := CoreReq_pipeReg_st0_st1.deq.bits.Req.asid.get
  }
  io.perLaneAddr_st1 := CoreReq_pipeReg_st0_st1.deq.bits.Req.perLaneAddr
  io.coreReq_Control_st1 := CoreReq_pipeReg_st0_st1.deq.bits.Ctrl
  val Control_st1 = CoreReq_pipeReg_st0_st1.deq.bits.Ctrl
  io.read_Req_dA.bits.foreach(_.setIdx := Cat(CoreReq_pipeReg_st0_st1.deq.bits.Req.setIdx,OHToUInt(io.tA_Hit_st1.waymask))) // dA r req addr
  when((CoreReqControl_st0.isFlush || CoreReqControl_st0.isInvalidate)&& io.hasDirty){
    io.read_Req_dA.bits.foreach(_.setIdx := Cat(io.tA_dirtySetIdx_st0,OHToUInt(io.tA_dirtyWayMask_st0)))
  }
  io.Req_st1_RTAB.bits.CoreReqData := CoreReq_pipeReg_st0_st1.deq.bits.Req
  io.Req_st1_RTAB.bits.ReqType     := ReplayType
  io.Req_st1_RTAB.bits.mshrIdx     := MshrIdx
  io.Req_st1_RTAB.bits.wshrIdx     := WshrIdx
  io.CheckReq_WSHR.blockAddr := BlockAddr_st1
  // FSM for evict
  val evictidle :: evictrsp:: Nil = Enum(2)
  val evictstateReg = RegInit(evictidle)
  val evictReg_next = WireInit(evictstateReg)
// mem request io connection
  when(FluInvMemReq_valid){
    io.MissReq_Mem.bits := FluInvMemReq_st1
  }.elsewhen(evictMemReq_valid){
    io.MissReq_Mem.bits := evictMemReq_st1
  }.otherwise{
    io.MissReq_Mem.bits := missMemReq_st1
  }
  io.MissReq_Mem.valid := missMemReq_valid || FluInvMemReq_valid || evictMemReq_valid


  MshrIdx := io.MSHR_ProbeStatus.a_source
  WshrIdx := io.WSHR_CheckResult.HitIdx

  MshrStatus := io.MSHR_ProbeStatus.probeStatus
  //important signals
  val ReadHit_st1   = io.tA_Hit_st1.hit  && Control_st1.isRead
  val ReadMiss_st1  = !io.tA_Hit_st1.hit && Control_st1.isRead
  val WriteHit_st1  = io.tA_Hit_st1.hit  && Control_st1.isWrite
  val WriteMiss_st1 = !io.tA_Hit_st1.hit && Control_st1.isWrite
  val AMO_LR_SC_st1 = Control_st1.isAMO || Control_st1.isLR || Control_st1.isSC
  val CacheHit_st1 = io.tA_Hit_st1.hit
  val CacheMiss_st1 = !io.tA_Hit_st1.hit
  val CacheHitDirty_st1 = io.tA_Hit_st1.hit && io.tA_Hit_st1.isDirty
  val UCReqHitNDirty = io.tA_Hit_st1.hit && !io.tA_Hit_st1.isDirty && CoreReq_pipeReg_st0_st1.deq.bits.Ctrl.isUncached
  val UCReqHitDirty  = io.tA_Hit_st1.hit && io.tA_Hit_st1.isDirty && CoreReq_pipeReg_st0_st1.deq.bits.Ctrl.isUncached
  io.CacheHit_st1 := CacheHit_st1
  io.WriteHit_st1 := WriteHit_st1
  missMemReq_valid := (CacheMiss_st1 && !FluInv_st1 || UCReqHitNDirty) && CoreReq_pipeReg_st0_st1.deq.fire && !io.Req_st1_RTAB.valid && io.MSHR_ProbeStatus.probeStatus === 0.U
  // RTABReqType req
  val Req_RTAB_st1_valid = Wire(Bool())
  Req_RTAB_st1_valid := false.B
  io.Req_st1_RTAB.valid := Req_RTAB_st1_valid && st1_ready // request RTAB when st1_ready
  ReplayType := 0.U
  when(Control_st1.isUncached && CacheHitDirty_st1 && (Control_st1.isWrite || Control_st1.isAMO || Control_st1.isSC)){
    Req_RTAB_st1_valid :=CoreReq_pipeReg_st0_st1.deq.valid && (evictstateReg === evictrsp)
    ReplayType := UCacheHitDirty
  }.elsewhen(MshrStatus === SecondaryFull){
    Req_RTAB_st1_valid := CoreReq_pipeReg_st0_st1.deq.valid
    ReplayType := SubEntryFull
  }.elsewhen(ReadMiss_st1 && MshrStatus === PrimaryFull){
    Req_RTAB_st1_valid := CoreReq_pipeReg_st0_st1.deq.valid
    ReplayType := EntryFull
  }.elsewhen(Control_st1.isRead && io.WSHR_CheckResult.Hit){
    Req_RTAB_st1_valid := CoreReq_pipeReg_st0_st1.deq.valid
    ReplayType := readHitWSHR
  }.elsewhen((WriteMiss_st1 || AMO_LR_SC_st1) && (MshrStatus === SecondaryAvail || MshrStatus === SecondaryFull)){
    Req_RTAB_st1_valid := CoreReq_pipeReg_st0_st1.deq.valid
    ReplayType := writeMissHitMSHR
  }.elsewhen(WriteMiss_st1 && io.WSHR_CheckResult.Hit){
    Req_RTAB_st1_valid := CoreReq_pipeReg_st0_st1.deq.valid
    ReplayType := writeMissHitWSHR
  }.elsewhen(io.SMSHR_ProbeStatus.hitblock){
    Req_RTAB_st1_valid := CoreReq_pipeReg_st0_st1.deq.valid
    ReplayType := HitSMSHR
  }.elsewhen(Control_st1.isSC && io.SMSHR_ProbeStatus.LRexist){
    Req_RTAB_st1_valid := CoreReq_pipeReg_st0_st1.deq.valid
    ReplayType := SCLRexist
  }
  io.read_Req_dA.valid := ReadHit_st1 || UCReqHitDirty || flushDirtyReq_st0

  //missReq 2 mem, request type and data generator
  OpcodeGen.io.coreReqCtrl := Control_st1
  OpcodeGen.io.coreReqParam := CoreReq_pipeReg_st0_st1.deq.bits.Req.param
  OpcodeGen.io.hit_dirty := io.tA_Hit_st1.hit && io.tA_Hit_st1.isDirty
  addrGen.io.dataIn := CoreReq_pipeReg_st0_st1.deq.bits.Req.data
  addrGen.io.perLaneAddrIn := CoreReq_pipeReg_st0_st1.deq.bits.Req.perLaneAddr
  // cache miss mem Req
  missMemReq_st1.a_opcode := OpcodeGen.io.memReq_a_opcode
  missMemReq_st1.a_addr.get := Cat(CoreReq_pipeReg_st0_st1.deq.bits.Req.tag, CoreReq_pipeReg_st0_st1.deq.bits.Req.setIdx, 0.U((WordLength - TagBits - SetIdxBits).W))
  missMemReq_st1.a_param  := OpcodeGen.io.memReq_a_param
  missMemReq_st1.a_data := addrGen.io.dataOut
  missMemReq_st1.hasCoreRsp := Control_st1.isWrite
  missMemReq_st1.coreRspInstrId := CoreReq_pipeReg_st0_st1.deq.bits.Req.instrId
  missMemReq_st1.spike_info.foreach( left =>
    left := CoreReq_pipeReg_st0_st1.deq.bits.Req.spike_info.getOrElse(0.U)
  )
  when(Control_st1.isRead){
    missMemReq_st1.a_source := Cat("d1".U, io.MSHR_ProbeStatus.a_source, CoreReq_pipeReg_st0_st1.deq.bits.Req.setIdx)
  }.elsewhen(Control_st1.isLR || Control_st1.isSC || Control_st1.isAMO){
    missMemReq_st1.a_source := Cat("d2".U, io.SMSHR_ProbeStatus.a_source, CoreReq_pipeReg_st0_st1.deq.bits.Req.setIdx)
  }.otherwise{
    missMemReq_st1.a_source := DontCare
  }
  if(MMU_ENABLED){
    missMemReq_st1.Asid.get := CoreReq_pipeReg_st0_st1.deq.bits.Req.asid.get
    FluInvMemReq_st1.Asid.get := io.tA_dirtyAsid_st1.get
    evictMemReq_st1.Asid.get := CoreReq_pipeReg_st0_st1.deq.bits.Req.asid.get
  }
  //regular read miss req mask is all 1
  missMemReq_st1.a_mask := Mux(missMemReq_st1.a_opcode === TLAOp_Get &&missMemReq_st1.a_param === 0.U,VecInit(Seq.fill(BlockWords)(Fill(BytesOfWord,1.U))),addrGen.io.MaskOut)
  // flu or inv mem req
  FluInvMemReq_st1.a_opcode := Mux(FluInvIsPut_st1,TLAOp_PutFull,TLAOp_Flush)
  FluInvMemReq_st1.a_param := Mux(FluInvIsPut_st1, 0.U, Mux(CoreReq_pipeReg_st0_st1.deq.bits.Ctrl.isFlush, TLAParam_Flush, TLAParam_Inv))
  val dirtySetIdx_st1 = RegNext(io.tA_dirtySetIdx_st0)
  FluInvMemReq_st1.a_addr.get := Cat(io.tA_dirtyTag_st1, dirtySetIdx_st1, 0.U((WordLength - TagBits - SetIdxBits).W))
  FluInvMemReq_st1.a_data := io.dA_data
  FluInvMemReq_st1.a_source := DontCare
  FluInvMemReq_st1.hasCoreRsp := false.B
  FluInvMemReq_st1.coreRspInstrId := DontCare
  FluInvMemReq_st1.a_mask := VecInit(Seq.fill(BlockWords)(Fill(BytesOfWord,1.U)))
  FluInvMemReq_valid := (FluInvIsPut_st1 || FluInvIsFluL2_st1) && CoreReq_pipeReg_st0_st1.deq.valid
  FluInvMemReq_st1.spike_info.foreach(_ := DontCare )
  // uncache hit dirty cacheline evict request
  evictMemReq_st1.a_opcode := TLAOp_PutFull
  evictMemReq_st1.a_param  := 0.U
  evictMemReq_st1.a_addr.get := Cat(CoreReq_pipeReg_st0_st1.deq.bits.Req.tag, CoreReq_pipeReg_st0_st1.deq.bits.Req.setIdx, 0.U((WordLength - TagBits - SetIdxBits).W))
  evictMemReq_st1.a_data := io.dA_data
  evictMemReq_st1.hasCoreRsp := false.B
  evictMemReq_st1.a_source := DontCare
  evictMemReq_st1.a_mask := VecInit(Seq.fill(BlockWords)(Fill(BytesOfWord,1.U)))
  evictMemReq_st1.spike_info.foreach(_ := DontCare )
  evictstateReg := evictReg_next
  evictMemReq_st1.coreRspInstrId := DontCare
  evictReg_next := evictstateReg
  // FSM for read da
  when(evictstateReg === evictidle){
    when(CoreReq_pipeReg_st0_st1.deq.valid && UCReqHitDirty){
      evictReg_next := evictrsp
    }.otherwise{
      evictReg_next := evictstateReg
    }
  }.elsewhen(evictstateReg === evictrsp && io.MissReq_Mem.ready){
    evictReg_next := evictidle
  }
  evictMemReq_valid := evictstateReg === evictrsp

  //MSHR miss Req
  val mshrMissReqTI = Wire(new VecMshrTargetInfo)
  mshrMissReqTI.instrId := CoreReq_pipeReg_st0_st1.deq.bits.Req.instrId
  mshrMissReqTI.perLaneAddr := CoreReq_pipeReg_st0_st1.deq.bits.Req.perLaneAddr
  io.MissReq_MSHR.valid := ReadMiss_st1 && CoreReq_pipeReg_st0_st1.deq.valid && !Req_RTAB_st1_valid
  io.MissReq_MSHR.bits.blockAddr := BlockAddr_st1
  io.MissReq_MSHR.bits.targetInfo := mshrMissReqTI.asUInt
  io.MissReq_MSHR.bits.instrId := CoreReq_pipeReg_st0_st1.deq.bits.Req.instrId
  io.MissCached_MSHR := Control_st1.isUncached
  io.Probe_SMSHR.bits.blockAddr := BlockAddr_st1
  io.Probe_SMSHR.bits.instrId := CoreReq_pipeReg_st0_st1.deq.bits.Req.instrId
  io.Probe_SMSHR.bits.targetInfo := mshrMissReqTI.asUInt
  io.Probe_SMSHR.bits.wordOffset := CoreReq_pipeReg_st0_st1.deq.bits.Req.perLaneAddr.map(lane => 
  Mux(lane.activeMask, lane.blockOffset, 0.U)
).reduce(_ | _)
  io.Probe_SMSHR.bits.Type := 0.U
  // todo add probe type
  io.Probe_SMSHR.valid := CoreReq_pipeReg_st0_st1.deq.valid && !Req_RTAB_st1_valid
  when(Control_st1.isAMO){
    io.Probe_SMSHR.bits.Type := 3.U
  }.elsewhen(Control_st1.isLR){
    io.Probe_SMSHR.bits.Type := 1.U
  }.elsewhen(Control_st1.isSC){
    io.Probe_SMSHR.bits.Type := 2.U
  }

  //st1 ready
  st1_ready := false.B
  when(!(Req_RTAB_st1_valid || ReplayType === UCacheHitDirty)) { // when not request RTAB
    when(Control_st1.isRead || Control_st1.isWrite) {
      when(io.tA_Hit_st1.hit) {
          when(CoreRsp_pipeReg_st1_st2.enq.ready && io.Mshr_st1_ready) { //todo check ready condition
            when(UCReqHitDirty){ // uncached read hit dirty will write back to mem and rsp to core
              st1_ready := !io.memRsp_coreRsp.valid && io.MissReq_Mem.ready && (evictstateReg === evictrsp)
            }.otherwise{
              st1_ready := !io.memRsp_coreRsp.valid //true.B
            }
          }.otherwise{
            st1_ready := false.B
          }
      }.otherwise { //Miss
        when(Control_st1.isRead) {
          when(io.MissReq_MSHR.ready && (MshrStatus === PrimaryAvail || MshrStatus === SecondaryAvail) //即memReq_Q.io.enq.ready
            && io.Mshr_st1_ready) {
              when(MshrStatus === SecondaryAvail){
                st1_ready := true.B //true.B
              }.otherwise{
                st1_ready := io.MissReq_Mem.ready
              }
          }.otherwise{
            st1_ready := false.B
          }
        }.otherwise { //isWrite
         when(CoreRsp_pipeReg_st1_st2.enq.ready && io.MissReq_Mem.ready && io.Mshr_st1_ready) { //memReq_Q.io.enq.ready
            st1_ready := true.B
          }.otherwise{
            st1_ready := false.B
          }
        }
      }
    }.elsewhen(Control_st1.isAMO){
      st1_ready := io.MissReq_Mem.ready
    }.elsewhen(Control_st1.isFlush || Control_st1.isInvalidate){
      when(FlushInvstateReg === idle || FlushInvstateReg === flushing){
        st1_ready := io.MissReq_Mem.ready
      }.otherwise{
        st1_ready := CoreRsp_pipeReg_st1_st2.enq.ready
      }
    }.otherwise{st1_ready := true.B}
  }.otherwise{// when requesting RTAB
    when(ReplayType === UCacheHitDirty){ // when hit in UCache and dirty, will write back to memory
      st1_ready := io.MissReq_Mem.ready && (evictstateReg === evictrsp)
    }.otherwise{
      st1_ready := true.B
    }
  }
  //write hit
  val getBankEn = Module(new getDataAccessBankEn(NBank = BlockWords, NLane = NLanes))
  getBankEn.io.perLaneBlockIdx :=  CoreReq_pipeReg_st0_st1.deq.bits.Req.perLaneAddr.map(_.blockOffset)
  getBankEn.io.perLaneValid :=  CoreReq_pipeReg_st0_st1.deq.bits.Req.perLaneAddr.map(_.activeMask)

  // ******      dataAccess write hit      ******
  val DataAccessWriteHitSRAMWReq: Vec[SRAMBundleAW[UInt]] = Wire(Vec(BlockWords,new SRAMBundleAW(UInt(8.W), NSets*NWays, BytesOfWord)))
  //this setIdx = setIdx + wayIdx
  DataAccessWriteHitSRAMWReq.foreach(_.setIdx := Cat( CoreReq_pipeReg_st0_st1.deq.bits.Req.setIdx,OHToUInt(io.tA_Hit_st1.waymask)))
  for (i <- 0 until BlockWords){
    DataAccessWriteHitSRAMWReq(i).waymask.get := addrGen.io.MaskOut(i)
    io.WriteReq_dA_valid(i) := addrGen.io.MaskOut(i).orR
    DataAccessWriteHitSRAMWReq(i).data := addrGen.io.dataOut(i).asTypeOf(Vec(BytesOfWord,UInt(8.W)))
  }
  io.WriteReq_dA := DataAccessWriteHitSRAMWReq
    //st1 valid: enqueue st1 st2 pipe reg for coreRsp
    // indicating coreRsp is valid from core Req
    // case: regular read/write hit, uncached read hit, uncache write hit undirty, write miss, flush invalidate complete
  st1_valid := false.B
  when(!Req_RTAB_st1_valid ){
    when(Control_st1.isRead && io.tA_Hit_st1.hit){
      st1_valid := CoreReq_pipeReg_st0_st1.deq.valid
    }.elsewhen(Control_st1.isWrite){     
      st1_valid := CoreReq_pipeReg_st0_st1.deq.valid
    
  }.elsewhen(Control_st1.isFlush || Control_st1.isInvalidate){
    st1_valid := CoreReq_pipeReg_st0_st1.deq.valid && (FlushInvstateReg === responding)
  }.otherwise{
    st1_valid := false.B
  }
}

  //RTAB req will deq st1 but not enq st2: except for uncache read hit dirty
  CoreReq_pipeReg_st0_st1.deq.ready := st1_ready
  io.st1_valid := CoreReq_pipeReg_st0_st1.deq.valid
  io.st1_ready := CoreReq_pipeReg_st0_st1.deq.ready

  //==========
  // st2 pipe reg
  when(st1_valid && st1_ready && (CacheHit_st1 || WriteMiss_st1)){
    CoreRsp_pipeReg_st1_st2.enq.valid            := true.B
    CoreRsp_pipeReg_st1_st2.enq.bits.Rsp.isWrite := CoreReq_pipeReg_st0_st1.deq.bits.Ctrl.isWrite
    CoreRsp_pipeReg_st1_st2.enq.bits.Rsp.data    := DontCare
    CoreRsp_pipeReg_st1_st2.enq.bits.Rsp.instrId := CoreReq_pipeReg_st0_st1.deq.bits.Req.instrId
    CoreRsp_pipeReg_st1_st2.enq.bits.Rsp.activeMask := CoreReq_pipeReg_st0_st1.deq.bits.Req.perLaneAddr.map(_.activeMask)
    CoreRsp_pipeReg_st1_st2.enq.bits.validFromCoreReq := true.B
    CoreRsp_pipeReg_st1_st2.enq.bits.perLaneAddr := CoreReq_pipeReg_st0_st1.deq.bits.Req.perLaneAddr
  }.elsewhen(io.memRsp_coreRsp.valid){
    CoreRsp_pipeReg_st1_st2.enq.valid            := true.B
    CoreRsp_pipeReg_st1_st2.enq.bits.Rsp.isWrite := false.B
    CoreRsp_pipeReg_st1_st2.enq.bits.Rsp.data    := io.memRsp_coreRsp.bits.Rsp.data
     CoreRsp_pipeReg_st1_st2.enq.bits.Rsp.instrId := io.memRsp_coreRsp.bits.Rsp.instrId
    CoreRsp_pipeReg_st1_st2.enq.bits.Rsp.activeMask := io.memRsp_coreRsp.bits.perLaneAddr.map(_.activeMask)
    CoreRsp_pipeReg_st1_st2.enq.bits.validFromCoreReq := false.B
    CoreRsp_pipeReg_st1_st2.enq.bits.perLaneAddr := io.memRsp_coreRsp.bits.perLaneAddr
  }.elsewhen(st1_valid && st1_ready && (FlushInvstateReg === responding)){
    CoreRsp_pipeReg_st1_st2.enq.valid            := true.B
    CoreRsp_pipeReg_st1_st2.enq.bits.Rsp.isWrite := CoreReq_pipeReg_st0_st1.deq.bits.Ctrl.isWrite
    CoreRsp_pipeReg_st1_st2.enq.bits.Rsp.data    := DontCare
    CoreRsp_pipeReg_st1_st2.enq.bits.Rsp.instrId := CoreReq_pipeReg_st0_st1.deq.bits.Req.instrId
    CoreRsp_pipeReg_st1_st2.enq.bits.Rsp.activeMask := CoreReq_pipeReg_st0_st1.deq.bits.Req.perLaneAddr.map(_.activeMask)
    CoreRsp_pipeReg_st1_st2.enq.bits.validFromCoreReq := true.B
    CoreRsp_pipeReg_st1_st2.enq.bits.perLaneAddr := CoreReq_pipeReg_st0_st1.deq.bits.Req.perLaneAddr
  }.otherwise{
    CoreRsp_pipeReg_st1_st2.enq.valid := false.B
    CoreRsp_pipeReg_st1_st2.enq.bits := DontCare
    CoreRsp_pipeReg_st1_st2.enq.bits.Rsp.activeMask := CoreReq_pipeReg_st0_st1.deq.bits.Req.perLaneAddr.map(_.activeMask)
  }
  io.memRsp_coreRsp.ready := CoreRsp_pipeReg_st1_st2.deq.ready
  coreReq_st2_ready := CoreRsp_st3.io.enq.ready && !io.memReq_coreRsp.valid
  CoreRsp_pipeReg_st1_st2.deq.ready := coreReq_st2_ready
  //== st2 ==
  //hold dataaccess data when there is conflict
  val coreRsp_st2_coreRsp_data_hold = Module(new Queue(Vec(dcache_BlockWords, UInt(WordLength.W)),1,true,false))
  coreRsp_st2_coreRsp_data_hold.io.enq.valid := CoreRsp_pipeReg_st1_st2.deq.valid && !coreReq_st2_ready && CoreRsp_pipeReg_st1_st2.deq.bits.validFromCoreReq
  coreRsp_st2_coreRsp_data_hold.io.enq.bits := io.dA_data
  coreRsp_st2_coreRsp_data_hold.io.deq.ready := coreReq_st2_ready
  val DataAccessReadHit = Mux(coreRsp_st2_coreRsp_data_hold.io.deq.valid,coreRsp_st2_coreRsp_data_hold.io.deq.bits,io.dA_data)
  val coreRspFromCoreReq_st2 = CoreRsp_pipeReg_st1_st2.deq.valid && CoreRsp_pipeReg_st1_st2.deq.bits.validFromCoreReq && !io.memReq_coreRsp.valid
  val coreRspFromMemRsp_st2 = CoreRsp_pipeReg_st1_st2.deq.valid && !CoreRsp_pipeReg_st1_st2.deq.bits.validFromCoreReq && !io.memReq_coreRsp.valid
  val st2_valid =  coreRspFromCoreReq_st2 || coreRspFromMemRsp_st2 || io.memReq_coreRsp.valid
  DataMemOrder_st2 := Mux(CoreRsp_pipeReg_st1_st2.deq.bits.validFromCoreReq, DataAccessReadHit, CoreRsp_pipeReg_st1_st2.deq.bits.Rsp.data)
  //data covertion
  for (i <- 0 until NLanes) {
    DataCoreOrder_st2(i) := DataMemOrder_st2(CoreRsp_pipeReg_st1_st2.deq.bits.perLaneAddr(i).blockOffset)
  }
  io.memReq_coreRsp.ready := CoreRsp_st3.io.enq.ready
  //== st3 enq
  CoreRsp_st3.io.enq.valid := io.memReq_coreRsp.valid || CoreRsp_pipeReg_st1_st2.deq.valid
  CoreRsp_st3.io.enq.bits.data    := DataCoreOrder_st2
  CoreRsp_st3.io.enq.bits.instrId := Mux(io.memReq_coreRsp.valid, io.memReq_coreRsp.bits.instrId, CoreRsp_pipeReg_st1_st2.deq.bits.Rsp.instrId)
  CoreRsp_st3.io.enq.bits.isWrite := Mux(io.memReq_coreRsp.valid, io.memReq_coreRsp.bits.isWrite, CoreRsp_pipeReg_st1_st2.deq.bits.Rsp.isWrite)
  CoreRsp_st3.io.enq.bits.activeMask :=  CoreRsp_pipeReg_st1_st2.deq.bits.Rsp.activeMask
  //
  io.CoreRsp <> CoreRsp_st3.io.deq
  io.st2_ready := CoreRsp_st3.io.enq.ready
}
