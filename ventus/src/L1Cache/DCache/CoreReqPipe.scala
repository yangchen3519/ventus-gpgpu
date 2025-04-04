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
    val CoreReq = Flipped(DecoupledIO(new DCacheCoreReq))
    val RTABHit = Input(Bool())
    val hasDirty = Input(Bool())
    val MSHREmpty = Input(Bool())
    val SMSHREmpty = Input(Bool())

    val Probe_MSHR = Output(new MSHRprobe(bABits, asidLen))
    val Probe_SMSHR = Output(new MSHRprobe(bABits, asidLen))//TODO add special MSHR
    val Probe_tA = Output(new SRAMBundleA(NSets))  // todo have ready issue
    val Probe_tA_ready = Input(Bool())
    val Req_st0_RTAB = Valid(new RTABReq())

    val st0_ready = Output(Bool())
    val st0_valid = Output(Bool())

    //st1
    val tA_Hit_st1 = Input(new hitStatus(NWays, TagBits))
    val MSHR_ProbeStatus = Input(new MSHRprobeOut(NMshrEntry, NMshrSubEntry))
    val WSHR_CheckResult = Input(new WSHRCheckResult(NWshrEntry))
    val Mshr_st1_ready = Input(Bool())

    val tagFromCore_tA_st1 = Output(UInt(dcache_TagBits.W))
    val coreReq_Control_st1 = Output(new DCacheControl)
    val read_Req_dA = Output(Vec(BlockWords,new SRAMBundleA(NSets*NWays)))
    val CacheHit_st1 = Output(Bool())
    val Req_st1_RTAB = ValidIO(new RTABReq())
    val CheckReq_WSHR = Output(new WSHRreq)
    val MissReq_MSHR = DecoupledIO(new MSHRmissReq(bABits, tIBits, WIdBits, asidLen))
    val st1_valid = Output(Bool())
    val st1_ready = Output(Bool())
    val MissReq_Mem = DecoupledIO(new WshrMemReq) // for memReq Pipe
    val Dirty_Invalidate = ValidIO(new WshrMemReq)
    val memRsp_coreRsp = Flipped(DecoupledIO(new CoreRspPipe_st2))

    //st2
    val dA_data = Input(Vec(BlockWords, UInt(WordLength.W)))
    val memReq_coreRsp = Flipped(DecoupledIO(new DCacheCoreRsp_d))

    val CoreRsp = DecoupledIO(new DCacheCoreRsp)
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
  val missMemReq_st1 = Wire(new WshrMemReq)

  //====== st2 ======
  val CoreRsp_pipeReg_st1_st2 = Module(new Queue(new CoreRspPipe_st2,entries = 1, pipe = true,flow = false)).io
  val DataMemOrder_st2 = Wire(Vec(BlockWords, UInt(WordLength.W)))
  val DataCoreOrder_st2 = Wire(Vec(NLanes, UInt(WordLength.W)))
  val coreReq_st2_ready = Wire(Bool())
  // st3
  val coreRsp_Q_entries: Int = NLanes
  val CoreRsp_st3 = Module(new Queue(new DCacheCoreRsp,entries = coreRsp_Q_entries,flow=false,pipe=false))

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
  io.Probe_tA.setIdx := io.CoreReq.bits.setIdx
  io.Req_st0_RTAB.bits.CoreReqData := io.CoreReq.bits
  io.Req_st0_RTAB.bits.ReqType     := DontCare
  io.Req_st0_RTAB.bits.mshrIdx     := DontCare
  io.Req_st0_RTAB.bits.wshrIdx     := DontCare
  io.Req_st0_RTAB.valid            := io.RTABHit && io.CoreReq.valid
  io.CoreReq.ready := st0_ready
  //Flush L2 FSM
  val idle :: flushing :: responding :: Nil = Enum(3)
  val FlushInvstateReg = RegInit(idle)
  // todo: add FSM defination
  when(FlushInvstateReg === idle){
    when(io.CoreReq.valid && !io.hasDirty && (CoreReqControl_st0.isFlush || CoreReqControl_st0.isInvalidate) && CoreReqControl_st0.isFluInvL2){
      FlushInvstateReg := flushing
  }.elsewhen(io.CoreReq.valid && !io.hasDirty && (CoreReqControl_st0.isFlush || CoreReqControl_st0.isInvalidate)){
    FlushInvstateReg := responding
  }
  }.elsewhen(FlushInvstateReg === flushing){
//TODO add memRsp connection
  }.elsewhen(FlushInvstateReg === responding){
    when(st0_ready){
      FlushInvstateReg := idle
    }
  }
  // valid ready
  // st0 st1 pipe reg enq valid
  st0_valid := false.B
  st0_ready := false.B
  when(!io.RTABHit ){
    // probe SMSHR MSHR and tag
    when(CoreReqControl_st0.isRead || CoreReqControl_st0.isWrite|| CoreReqControl_st0.isAMO || CoreReqControl_st0.isLR || CoreReqControl_st0.isSC){
      st0_valid  := io.CoreReq.valid && io.Probe_tA_ready
      st0_ready := CoreReq_pipeReg_st0_st1.enq.ready && io.Probe_tA_ready
    }.elsewhen(CoreReqControl_st0.isWaitMSHR){
      //wait until MSHR empty
      st0_valid  := io.CoreReq.valid && io.MSHREmpty && io.SMSHREmpty
      st0_ready := CoreReq_pipeReg_st0_st1.enq.ready && io.MSHREmpty && io.SMSHREmpty
    }.elsewhen(CoreReqControl_st0.isFlush || CoreReqControl_st0.isInvalidate){
      when(io.hasDirty){
        //write back dirty cacheline
        st0_valid  := io.CoreReq.valid
        st0_ready := false.B
      }.otherwise{
        when(FlushInvstateReg === idle){
          st0_valid  := io.CoreReq.valid && io.Probe_tA_ready
          st0_ready := false.B
        }.elsewhen(FlushInvstateReg === flushing){
          st0_valid := false.B
          st0_ready := false.B
        }.elsewhen(FlushInvstateReg === responding){
          st0_valid := io.CoreReq.valid
          st0_ready := CoreReq_pipeReg_st0_st1.enq.ready
        }
      }
    }
  }

  //st0_ready := io.Probe_tA_ready && CoreReq_pipeReg_st0_st1.enq.ready
  // =============
  // st1 pipe reg
  val BlockAddr_st1 = Cat(CoreReq_pipeReg_st0_st1.deq.bits.Req.tag, CoreReq_pipeReg_st0_st1.deq.bits.Req.setIdx)
  CoreReq_pipeReg_st0_st1.enq.valid := st0_valid
  CoreReq_pipeReg_st0_st1.enq.bits.Req := io.CoreReq.bits
  CoreReq_pipeReg_st0_st1.enq.bits.Ctrl := CoreReqControl_st0
  //=== st1 ===

  io.tagFromCore_tA_st1 := CoreReq_pipeReg_st0_st1.deq.bits.Req.tag // check the tag from core with tag from tA block
  io.coreReq_Control_st1 := CoreReq_pipeReg_st0_st1.deq.bits.Ctrl
  val Control_st1 = CoreReq_pipeReg_st0_st1.deq.bits.Ctrl
  io.read_Req_dA.foreach(_.setIdx := Cat(CoreReq_pipeReg_st0_st1.deq.bits.Req.setIdx,OHToUInt(io.tA_Hit_st1.waymask))) // dA r req addr
  io.Req_st1_RTAB.bits.CoreReqData := CoreReq_pipeReg_st0_st1.deq.bits.Req
  io.Req_st1_RTAB.bits.ReqType     := ReplayType
  io.Req_st1_RTAB.bits.mshrIdx     := MshrIdx
  io.Req_st1_RTAB.bits.wshrIdx     := WshrIdx
  io.CheckReq_WSHR.blockAddr := BlockAddr_st1
  io.MissReq_Mem.bits := missMemReq_st1


  MshrIdx := io.MSHR_ProbeStatus.a_source
  WshrIdx := io.WSHR_CheckResult.HitIdx

  MshrStatus := io.MSHR_ProbeStatus.probeStatus
  //important signals
  val ReadHit_st1   = io.tA_Hit_st1.hit  && Control_st1.isRead
  val ReadMiss_st1  = !io.tA_Hit_st1.hit && Control_st1.isRead
  val WriteHit_st1  = io.tA_Hit_st1.hit  && Control_st1.isWrite
  val WriteMiss_st1 = !io.tA_Hit_st1.hit && Control_st1.isWrite
  val CacheHit_st1 = io.tA_Hit_st1.hit
  val CacheMiss_st1 = !io.tA_Hit_st1.hit
  val CacheHitDirty_st1 = io.tA_Hit_st1.hit && io.tA_Hit_st1.isDirty
  io.CacheHit_st1 := CacheHit_st1
  io.MissReq_Mem.valid := CacheMiss_st1
  // RTABReqType req
  io.Req_st1_RTAB.valid := false.B
  when(Control_st1.isUncached && CacheHitDirty_st1){
    io.Req_st1_RTAB.valid := st1_valid
    ReplayType := UCacheHitDirty
  }.elsewhen(MshrStatus === SecondaryFull){
    io.Req_st1_RTAB.valid := st1_valid
    ReplayType := SubEntryFull
  }.elsewhen(ReadMiss_st1 && MshrStatus === PrimaryFull){
    io.Req_st1_RTAB.valid := st1_valid
    ReplayType := EntryFull
  }.elsewhen(Control_st1.isRead && io.WSHR_CheckResult.Hit){
    io.Req_st1_RTAB.valid := st1_valid
    ReplayType := readHitWSHR
  }.elsewhen(WriteMiss_st1 && (MshrStatus === SecondaryAvail || MshrStatus === SecondaryFull)){
    io.Req_st1_RTAB.valid := st1_valid
    ReplayType := writeMissHitMSHR
  }.elsewhen(WriteMiss_st1 && io.WSHR_CheckResult.Hit){
    io.Req_st1_RTAB.valid := st1_valid
    ReplayType := writeMissHitWSHR
  }

  //missReq 2 mem

  //MSHR miss Req
  val mshrMissReqTI = Wire(new VecMshrTargetInfo)
  mshrMissReqTI.instrId := CoreReq_pipeReg_st0_st1.deq.bits.Req.instrId
  mshrMissReqTI.perLaneAddr := CoreReq_pipeReg_st0_st1.deq.bits.Req.perLaneAddr
  io.MissReq_MSHR.valid := ReadMiss_st1 && st1_valid
  io.MissReq_MSHR.bits.blockAddr := BlockAddr_st1
  io.MissReq_MSHR.bits.targetInfo := mshrMissReqTI.asUInt
  io.MissReq_MSHR.bits.instrId := CoreReq_pipeReg_st0_st1.deq.bits.Req.instrId

  //st1 ready
  st1_ready := false.B
  when(!io.Req_st1_RTAB.valid) {
    when(Control_st1.isRead || Control_st1.isWrite) {
      when(io.tA_Hit_st1.hit) {
        when(CoreRsp_pipeReg_st1_st2.enq.ready && io.Mshr_st1_ready) { //todo check ready condition
          st1_ready := !io.memRsp_coreRsp.valid //true.B
        }
      }.otherwise { //Miss
        when(Control_st1.isRead) {
          when(io.MissReq_MSHR.ready && (MshrStatus === PrimaryAvail || MshrStatus === SecondaryAvail) //即memReq_Q.io.enq.ready
            && io.Mshr_st1_ready) {
            st1_ready := io.MissReq_Mem.ready //true.B
          }
        }.otherwise { //isWrite
          //TODO before 7.30: add hit in-flight miss
          when(CoreRsp_pipeReg_st1_st2.enq.ready && io.MissReq_Mem.ready && io.Mshr_st1_ready) { //memReq_Q.io.enq.ready
            st1_ready := true.B
          }
        }
      } //.otherwise{//coreReq is not valid
      //  coreReq_st1_ready := true.B
      //}//TODO invalidate and flush handling
    }.otherwise { //TODO: amo
      st1_ready := true.B
    }
  }
  //RTAB req will deq st1 but not enq st2
  CoreReq_pipeReg_st0_st1.deq.ready := st1_ready || io.Req_st1_RTAB.valid
  val CoreReq_st1_deq_fire = CoreRsp_pipeReg_st1_st2.deq.valid && st1_ready
  io.st1_valid := CoreReq_pipeReg_st0_st1.deq.valid
  io.st1_ready := CoreReq_pipeReg_st0_st1.deq.ready

  //==========
  // st2 pipe reg
  when(CoreReq_st1_deq_fire && CacheHit_st1){
    CoreRsp_pipeReg_st1_st2.enq.valid            := true.B
    CoreRsp_pipeReg_st1_st2.enq.bits.Rsp.isWrite := CoreReq_pipeReg_st0_st1.deq.bits.Ctrl.isWrite
    CoreRsp_pipeReg_st1_st2.enq.bits.Rsp.data    := DontCare
    CoreRsp_pipeReg_st1_st2.enq.bits.Rsp.activeMask := CoreReq_pipeReg_st0_st1.deq.bits.Req.perLaneAddr.map(_.activeMask)
    CoreRsp_pipeReg_st1_st2.enq.bits.validFromCoreReq := true.B
    CoreRsp_pipeReg_st1_st2.enq.bits.perLaneAddr := CoreReq_pipeReg_st0_st1.deq.bits.Req.perLaneAddr
  }.elsewhen(io.memRsp_coreRsp.valid){
    CoreRsp_pipeReg_st1_st2.enq.valid            := true.B
    CoreRsp_pipeReg_st1_st2.enq.bits.Rsp.isWrite := false.B
    CoreRsp_pipeReg_st1_st2.enq.bits.Rsp.data    := io.memRsp_coreRsp.bits.Rsp.data
    CoreRsp_pipeReg_st1_st2.enq.bits.Rsp.activeMask := io.memRsp_coreRsp.bits.perLaneAddr.map(_.activeMask)
    CoreRsp_pipeReg_st1_st2.enq.bits.validFromCoreReq := false.B
    CoreRsp_pipeReg_st1_st2.enq.bits.perLaneAddr := io.memRsp_coreRsp.bits.perLaneAddr
  }//TODO add flush inv
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
  val st2_valid =  coreRspFromCoreReq_st2 || coreRspFromMemRsp_st2 || io.memRsp_coreRsp.valid
  DataMemOrder_st2 := Mux(CoreRsp_pipeReg_st1_st2.deq.bits.validFromCoreReq, DataAccessReadHit, CoreRsp_pipeReg_st1_st2.deq.bits.Rsp.data)
  //data covertion
  for (i <- 0 until NLanes) {
    DataCoreOrder_st2(i) := DataMemOrder_st2(CoreRsp_pipeReg_st1_st2.deq.bits.perLaneAddr(i).blockOffset)
  }
  //== st3 enq
  CoreRsp_st3.io.enq.bits.data    := DataCoreOrder_st2
  CoreRsp_st3.io.enq.bits.instrId := Mux(io.memReq_coreRsp.valid, io.memReq_coreRsp.bits.instrId, CoreRsp_pipeReg_st1_st2.deq.bits.Rsp.instrId)
  CoreRsp_st3.io.enq.bits.isWrite := Mux(io.memReq_coreRsp.valid, io.memReq_coreRsp.bits.isWrite, CoreRsp_pipeReg_st1_st2.deq.bits.Rsp.isWrite)
  CoreRsp_st3.io.enq.bits.activeMask := Mux(io.memReq_coreRsp.valid, io.memReq_coreRsp.bits.activeMask, CoreRsp_pipeReg_st1_st2.deq.bits.Rsp.activeMask)
  //
  io.CoreRsp <> CoreRsp_st3.io.deq

}