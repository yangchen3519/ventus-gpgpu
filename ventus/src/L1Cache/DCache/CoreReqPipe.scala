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

    val Probe_MSHR     = Output(new MSHRprobe(bABits, asidLen))
    val probeAsid      = if(MMU_ENABLED) {Some(Output(UInt(asidLen.W)))} else None
    val Probe_tA       = Output(new SRAMBundleA(NSets))  // todo have ready issue
    val Probe_tA_ready = Input(Bool())
    val Req_st0_RTAB   = Valid(new RTABReq())

    val st0_ready      = Output(Bool())
    val st0_valid      = Output(Bool())

    //st1
    
    val tA_Hit_st1       = Input(new hitStatus(NWays, TagBits))
    val MSHR_ProbeStatus = Input(new MSHRprobeOut(NMshrEntry, NMshrSubEntry))
    val SMSHR_hit        = Input(Bool())
    val SMSHR_hitblock   = Input(Bool())
    val SMSHR_LRexist    = Input(Bool())
    val WSHR_CheckResult = Input(new WSHRCheckResult(NWshrEntry))
    val Mshr_st1_ready   = Input(Bool())

    val tagFromCore_tA_st1  = Output(UInt(dcache_TagBits.W))
    val coreReq_Control_st1 = Output(new DCacheControl)
    val read_Req_dA         = Output(Vec(BlockWords,new SRAMBundleA(NSets*NWays)))
    val CacheHit_st1        = Output(Bool())
    val Req_st1_RTAB        = ValidIO(new RTABReq())
    val CheckReq_WSHR       = Output(new WSHRreq)
    val Probe_SMSHR         = Output(new SMSHRmissReq(bABits, tIBits, WIdBits, asidLen))//TODO add special MSHR
    val MissReq_MSHR        = DecoupledIO(new MSHRmissReq(bABits, tIBits, WIdBits, asidLen))
    val st1_valid           = Output(Bool())
    val st1_ready           = Output(Bool())
    val MissReq_Mem         = DecoupledIO(new WshrMemReq)
    val memRsp_coreRsp      = Flipped(DecoupledIO(new CoreRspPipe_st2))

    //st2
    val dA_data        = Input(Vec(BlockWords, UInt(WordLength.W)))
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
  // missReq st1
  val missMemReq_st1   = Wire(new WshrMemReq)

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
  io.Req_st0_RTAB.valid            := io.RTABHit && io.CoreReq.valid
  io.CoreReq.ready := st0_ready
  //Flush L2 FSM
  val idle :: flushing :: responding :: Nil = Enum(3)
  val FlushInvstateReg = RegInit(idle)
  val FlushInvstateReg_next = WireInit(FlushInvstateReg)
  // todo: add FSM defination
  when(FlushInvstateReg === idle){
    when(io.CoreReq.valid && !io.hasDirty && (CoreReqControl_st0.isFlush || CoreReqControl_st0.isInvalidate) && CoreReqControl_st0.isFluInvL2){
      FlushInvstateReg_next := flushing
  }.elsewhen(io.CoreReq.valid && !io.hasDirty && (CoreReqControl_st0.isFlush || CoreReqControl_st0.isInvalidate)){
    FlushInvstateReg_next := responding
  }
  }.elsewhen(FlushInvstateReg === flushing){
//TODO add memRsp connection
  }.elsewhen(FlushInvstateReg === responding){
    when(st0_ready){
      FlushInvstateReg_next := idle
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
      
        when(FlushInvstateReg_next === idle){
           when(!io.MSHREmpty || !io.SMSHREmpty){
             st0_valid := false.B
             st0_ready := false.B
           }.elsewhen(io.hasDirty){
             //write back dirty cacheline
             st0_valid  := io.CoreReq.valid
             st0_ready := false.B
           }
        }.elsewhen(FlushInvstateReg_next === flushing){
          st0_valid := false.B
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
  val Req_RTAB_st1_valid = Wire(Bool())
  Req_RTAB_st1_valid := false.B
  io.Req_st1_RTAB.valid := Req_RTAB_st1_valid && st1_ready // request RTAB when st1_ready
  ReplayType := 0.U
  when(Control_st1.isUncached && CacheHitDirty_st1 && Control_st1.isWrite){
    Req_RTAB_st1_valid :=CoreReq_pipeReg_st0_st1.deq.valid
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
  }.elsewhen(WriteMiss_st1 && (MshrStatus === SecondaryAvail || MshrStatus === SecondaryFull)){
    Req_RTAB_st1_valid := CoreReq_pipeReg_st0_st1.deq.valid
    ReplayType := writeMissHitMSHR
  }.elsewhen(WriteMiss_st1 && io.WSHR_CheckResult.Hit){
    Req_RTAB_st1_valid := CoreReq_pipeReg_st0_st1.deq.valid
    ReplayType := writeMissHitWSHR
  }.elsewhen(io.SMSHR_hitblock){
    Req_RTAB_st1_valid := CoreReq_pipeReg_st0_st1.deq.valid
    ReplayType := HitSMSHR
  }.elsewhen(Control_st1.isSC && io.SMSHR_LRexist){
    Req_RTAB_st1_valid := CoreReq_pipeReg_st0_st1.deq.valid
    ReplayType := SCLRexist
  }

  //missReq 2 mem, request type and data generator
  OpcodeGen.io.coreReqCtrl := Control_st1
  OpcodeGen.io.coreReqParam := CoreReq_pipeReg_st0_st1.deq.bits.Req.param
  OpcodeGen.io.hit_dirty := io.tA_Hit_st1.hit && io.tA_Hit_st1.isDirty
  addrGen.io.dataIn := CoreReq_pipeReg_st0_st1.deq.bits.Req.data
  addrGen.io.perLaneAddrIn := CoreReq_pipeReg_st0_st1.deq.bits.Req.perLaneAddr
  missMemReq_st1.a_opcode := OpcodeGen.io.memReq_a_opcode
  missMemReq_st1.a_addr.get := Cat(CoreReq_pipeReg_st0_st1.deq.bits.Req.tag, CoreReq_pipeReg_st0_st1.deq.bits.Req.setIdx, 0.U((WordLength - TagBits - SetIdxBits).W))
  missMemReq_st1.a_param  := OpcodeGen.io.memReq_a_param
  missMemReq_st1.a_data := addrGen.io.dataOut
  //regular read miss req mask is all 1
  missMemReq_st1.a_mask := Mux(missMemReq_st1.a_opcode === TLAOp_Get &&missMemReq_st1.a_param === 0.U,VecInit(Seq.fill(BlockWords)(Fill(BytesOfWord,1.U))),addrGen.io.MaskOut)
  //MSHR miss Req
  val mshrMissReqTI = Wire(new VecMshrTargetInfo)
  mshrMissReqTI.instrId := CoreReq_pipeReg_st0_st1.deq.bits.Req.instrId
  mshrMissReqTI.perLaneAddr := CoreReq_pipeReg_st0_st1.deq.bits.Req.perLaneAddr
  io.MissReq_MSHR.valid := ReadMiss_st1 && st1_valid
  io.MissReq_MSHR.bits.blockAddr := BlockAddr_st1
  io.MissReq_MSHR.bits.targetInfo := mshrMissReqTI.asUInt
  io.MissReq_MSHR.bits.instrId := CoreReq_pipeReg_st0_st1.deq.bits.Req.instrId

  io.Probe_SMSHR.blockAddr := BlockAddr_st1
  io.Probe_SMSHR.instrId := CoreReq_pipeReg_st0_st1.deq.bits.Req.instrId
  io.Probe_SMSHR.targetInfo := mshrMissReqTI.asUInt
  io.Probe_SMSHR.wordOffset := CoreReq_pipeReg_st0_st1.deq.bits.Req.perLaneAddr.map(lane => 
  Mux(lane.activeMask, lane.blockOffset, 0.U)
).reduce(_ | _)
  io.Probe_SMSHR.Type := 0.U

  //st1 ready
  st1_ready := false.B
  when(!Req_RTAB_st1_valid) { // when not request RTAB
    when(Control_st1.isRead || Control_st1.isWrite) {
      when(io.tA_Hit_st1.hit) {
          when(CoreRsp_pipeReg_st1_st2.enq.ready && io.Mshr_st1_ready) { //todo check ready condition
            when(Control_st1.isUncached && io.tA_Hit_st1.isDirty){ // uncached read hit dirty will write back to mem and rsp to core
              st1_ready := !io.memRsp_coreRsp.valid && io.MissReq_Mem.ready
            }.otherwise{
              st1_ready := !io.memRsp_coreRsp.valid //true.B
            }
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
          }
        }.otherwise { //isWrite
         when(CoreRsp_pipeReg_st1_st2.enq.ready && io.MissReq_Mem.ready && io.Mshr_st1_ready) { //memReq_Q.io.enq.ready
            st1_ready := true.B
          }
        }
      } //.otherwise{//coreReq is not valid
      //  coreReq_st1_ready := true.B
      //}//TODO invalidate and flush handling
    }.elsewhen(Control_st1.isAMO){
      st1_ready := io.MissReq_Mem.ready
    }.elsewhen(Control_st1.isFlush || Control_st1.isInvalidate){
      st1_ready := CoreRsp_pipeReg_st1_st2.enq.ready
    }.otherwise{st1_ready := true.B}
  }.otherwise{// when requesting RTAB
    when(ReplayType === UCacheHitDirty){ // when hit in UCache and dirty, will write back to memory
      st1_ready := io.MissReq_Mem.ready
    }.otherwise{
      st1_ready := true.B
    }
  }
    //st1 valid: enqueue st1 st2 pipe reg for coreRsp
    // indicating coreRsp is valid from core Req
    // case: regular read/write hit, uncached read hit, uncache write hit undirty, write miss, flush invalidate complete
  st1_valid := false.B
  when(!Req_RTAB_st1_valid){
    when(Control_st1.isRead && io.tA_Hit_st1.hit){
      st1_valid := CoreReq_pipeReg_st0_st1.deq.valid
    }.elsewhen(Control_st1.isWrite){
      when(CacheHitDirty_st1){
        st1_valid := false.B
    }.otherwise{
      st1_valid := CoreReq_pipeReg_st0_st1.deq.valid
    }
  }.elsewhen(Control_st1.isFlush || Control_st1.isInvalidate){
    st1_valid := CoreReq_pipeReg_st0_st1.deq.valid
  }.otherwise{
    st1_valid := false.B
  }
}

  //RTAB req will deq st1 but not enq st2: except for uncache read hit dirty
  CoreReq_pipeReg_st0_st1.deq.ready := st1_ready
  val CoreReq_st1_deq_fire = CoreRsp_pipeReg_st1_st2.deq.valid && st1_ready
  io.st1_valid := CoreReq_pipeReg_st0_st1.deq.valid
  io.st1_ready := CoreReq_pipeReg_st0_st1.deq.ready

  //==========
  // st2 pipe reg
  when(st1_valid && st1_ready){
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
  }
  //TODO add flush inv
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

