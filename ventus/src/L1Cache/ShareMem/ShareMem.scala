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
package L1Cache.ShareMem

import L1Cache.UnifiedL1.{UnifiedSMemReq, UnifiedSMemResp}
import chisel3._
import chisel3.util._
import config.config.Parameters
import top.parameters.sharedmem_BlockWords
import top.parameters.xLen
import top.parameters.sharedmem_depth
import top.parameters.num_thread
import top.parameters.BytesOfWord
import top.parameters.num_lane
import top.parameters.CTA_SCHE_CONFIG

/*Version Note
* DCacheCoreReq spec changed, shift some work to LSU
* //byteEn
//00 for byte
//01 for half word, alignment required
//11 for word, alignment required
* */

class ShareMemPerLaneAddr(implicit p: Parameters) extends ShareMemBundle{
  val activeMask = Bool()
  val blockOffset = UInt(BlockOffsetBits.W)
  val wordOffset1H = UInt(BytesOfWord.W)
}
class ShareMemCoreReq(implicit p: Parameters) extends ShareMemBundle{
  //val ctrlAddr = new Bundle{
  val instrId = UInt(WIdBits.W)//TODO length unsure
  val isWrite = Bool()//Vec(NLanes, Bool())
  //val tag = UInt(TagBits.W)
  val setIdx = UInt(SetIdxBits.W)
  val smemBankCount = UInt(log2Ceil(CTA_SCHE_CONFIG.GPU.UNIFIED_L1_PARTITION_BANKS+1).W)
  val perLaneAddr = Vec(NLanes, new ShareMemPerLaneAddr)
  val data = Vec(NLanes, UInt(WordLength.W))
}

class ShareMemCoreRsp(implicit p: Parameters) extends ShareMemBundle{
  val instrId = UInt(WIdBits.W)
  val isWrite = Bool()
  val data = Vec(NLanes, UInt(WordLength.W))
  val activeMask = Vec(NLanes, Bool())//UInt(NLanes.W)
}

class ShareMemGrantMeta(implicit p: Parameters) extends ShareMemBundle{
  val instrId = UInt(WIdBits.W)
  val isWrite = Bool()
  val setIdx = UInt(SetIdxBits.W)
  val activeMask = Vec(NLanes, Bool())
  val addrCrsbarOut = Vec(NBanks, new AddrBundle1T)
  val dataCrsbarSel1H = Vec(NBanks, UInt(NBanks.W))
  val dataArrayEn = Vec(NBanks, Bool())
  val data = Vec(NLanes, UInt(WordLength.W))
}

class SharedMemory(implicit p: Parameters) extends ShareMemModule{
  val io = IO(new Bundle{
    val coreReq = Flipped(DecoupledIO(new ShareMemCoreReq))
    val coreRsp = DecoupledIO(new ShareMemCoreRsp)
    val dataArrayReq = Decoupled(new UnifiedSMemReq)
    val dataArrayResp = Flipped(Valid(new UnifiedSMemResp))
    })

  // ******     important submodules     ******
  val BankConfArb = Module(new BankConflictArbiter)
  //val TagAccess = Module(new L1TagAccess(set=NSets, way=NWays, tagBits=TagBits))
  val DataCorssBarForWrite = Module(new DataCrossbar)
  val DataCorssBarForRead = Module(new DataCrossbar)
  /*val EntryValidArray = RegInit(
    VecInit(Seq.fill(NSets)(
      VecInit(Seq.fill(BlockWords)(
        VecInit(Seq.fill(BytesOfWord)(false.B)))))))*/

  // ******     queues     ******
  val DepthCoreRsp_Q: Int = num_thread
  val coreRsp_Q = Module(new Queue(new ShareMemCoreRsp,entries = DepthCoreRsp_Q,flow=false,pipe=true))
  //this queue also work as a pipeline reg, so cannot flow

  // ******      Arbiter      ******
  val coreReq_st1 = RegEnable(io.coreReq.bits, io.coreReq.fire)
  val coreReqHasActiveLane = io.coreReq.bits.perLaneAddr.map(_.activeMask).reduce(_ || _)
  when(io.coreReq.fire && coreReqHasActiveLane) {
    assert(io.coreReq.bits.setIdx < io.coreReq.bits.smemBankCount,
      "SharedMemory access exceeds active SMEM partition")
  }
  BankConfArb.io.coreReqArb.enable := io.coreReq.fire
  BankConfArb.io.coreReqArb.isWrite := Mux(BankConfArb.io.busy,coreReq_st1.isWrite,io.coreReq.bits.isWrite)
  BankConfArb.io.coreReqArb.perLaneAddr := io.coreReq.bits.perLaneAddr

  // ******      valid write      ******
  // crossbar switch for perWord addr
  /*val wordIdx1H = Wire(Vec(NLanes,UInt(BlockWords.W)))
  val rawCoreReqPerLaneWordMask1H = Wire(Vec(NLanes,UInt(BytesOfWord.W)))
  (0 until NLanes).foreach { i =>
    wordIdx1H(i) := UIntToOH(io.coreReq.bits.perLaneAddr(i).blockOffset)
    rawCoreReqPerLaneWordMask1H(i) := io.coreReq.bits.perLaneAddr(i).wordOffset1H
  }
  val perWordReq1H = Wire(Vec(BlockWords,UInt(NLanes.W)))
  //注意每个coreReq或者说周期里每个word只能被一个lane访问，多的话违反独热
  //单个coreReq或者说周期里能处理的coalesce请求的范围是一个block
  val activeByteMaskArray = Wire(Vec(BlockWords,UInt(BytesOfWord.W)))
  (0 until BlockWords).foreach{ i =>
    perWordReq1H(i) := Reverse(Cat(wordIdx1H.map(_(i))))
    assert(PopCount(perWordReq1H(i))<=1.U)
    activeByteMaskArray(i) := Mux1H(perWordReq1H(i),rawCoreReqPerLaneWordMask1H)
  }
  // end crossbar switch for perWord addr
  for (iofS <- 0 until NSets) {
    for (iofW <- 0 until BlockWords) { //iofW index of word
      for (iofB <- 0 until BytesOfWord) { //iofB index of byte
        when(io.coreReq.fire && io.coreReq.bits.isWrite &&
          io.coreReq.bits.setIdx === iofS.asUInt) {
          when(activeByteMaskArray(iofW)(iofB)) {
            EntryValidArray(iofS)(iofW)(iofB) := true.B
          }
        }
        when(io.coreReq.fire && !io.coreReq.bits.isWrite &&
          io.coreReq.bits.setIdx === iofS.asUInt) {
          when(activeByteMaskArray(iofW)(iofB)) {
            assert(EntryValidArray(iofS)(iofW)(iofB) === true.B)
          }
        }
      }
    }
  }*/

  // ******     pipeline regs      ******
  val activeReq = Wire(new ShareMemCoreReq)
  activeReq := Mux(BankConfArb.io.busy,coreReq_st1,io.coreReq.bits)

  val grantMeta = Wire(new ShareMemGrantMeta)
  grantMeta.instrId := activeReq.instrId
  grantMeta.isWrite := activeReq.isWrite
  grantMeta.setIdx := activeReq.setIdx
  grantMeta.activeMask := BankConfArb.io.activeLane
  grantMeta.addrCrsbarOut := BankConfArb.io.addrCrsbarOut
  grantMeta.dataCrsbarSel1H := BankConfArb.io.dataCrsbarSel1H
  grantMeta.dataArrayEn := BankConfArb.io.dataArrayEn
  grantMeta.data := activeReq.data

  val rspPipe_st1_valid = RegInit(false.B)
  val rspPipe_st1_bits = Reg(new ShareMemGrantMeta)
  val rspPipe_st1_dataValid = RegInit(false.B)
  val rspPipe_st1_data = Reg(Vec(NBanks, UInt(WordLength.W)))
  val rspPipe_st2_valid = RegInit(false.B)
  val rspPipe_st2_bits = Reg(new ShareMemGrantMeta)
  val rspPipe_st2_data = Reg(Vec(NBanks, UInt(WordLength.W)))

  val rspPipe_st2_ready = !rspPipe_st2_valid || coreRsp_Q.io.enq.ready
  val rspPipe_st1_canMove = rspPipe_st1_valid && rspPipe_st2_ready
  val rspPipe_st1_ready = !rspPipe_st1_valid || rspPipe_st1_canMove
  BankConfArb.io.grantReady := rspPipe_st1_ready
  val grantFire = BankConfArb.io.grantValid && BankConfArb.io.grantReady

  // ******     Unified data array request      ******
  io.dataArrayReq.valid := grantFire
  io.dataArrayReq.bits.physicalSlot := grantMeta.setIdx
  io.dataArrayReq.bits.isWrite := grantMeta.isWrite
  io.dataArrayReq.bits.bankEn := grantMeta.dataArrayEn
  for(i <- 0 until NBanks) {
    io.dataArrayReq.bits.wdata(i) := DataCorssBarForWrite.io.DataOut(i)
    io.dataArrayReq.bits.wmask(i) := grantMeta.addrCrsbarOut(i).wordOffset1H
  }
  assert(io.dataArrayReq.ready, "UnifiedDataArray SMEM port must be always-ready in phase-1")

  // ******      data crossbar for write     ******
  DataCorssBarForWrite.io.DataIn := grantMeta.data
  DataCorssBarForWrite.io.Select1H := grantMeta.dataCrsbarSel1H
  // ******      data crossbar for read     ******
  DataCorssBarForRead.io.DataIn := rspPipe_st2_data
  DataCorssBarForRead.io.Select1H := rspPipe_st2_bits.dataCrsbarSel1H

  val rspPipe_st1_dataNext = io.dataArrayResp.bits.rdata
  when(rspPipe_st2_ready){
    rspPipe_st2_valid := rspPipe_st1_valid
    when(rspPipe_st1_valid){
      rspPipe_st2_bits := rspPipe_st1_bits
      rspPipe_st2_data := Mux(rspPipe_st1_dataValid,rspPipe_st1_data,rspPipe_st1_dataNext)
    }
  }
  when(rspPipe_st1_valid && !rspPipe_st1_dataValid && !rspPipe_st1_canMove){
    rspPipe_st1_dataValid := true.B
    rspPipe_st1_data := rspPipe_st1_dataNext
  }
  when(rspPipe_st1_ready){
    rspPipe_st1_valid := grantFire
    rspPipe_st1_dataValid := false.B
    when(grantFire){
      rspPipe_st1_bits := grantMeta
    }
  }

  // ******      core rsp
  coreRsp_Q.io.deq <> io.coreRsp
  coreRsp_Q.io.enq.valid := rspPipe_st2_valid
  coreRsp_Q.io.enq.bits.isWrite := rspPipe_st2_bits.isWrite
  coreRsp_Q.io.enq.bits.data := Mux(
    rspPipe_st2_bits.isWrite,
    VecInit(Seq.fill(NLanes)(0.U(WordLength.W))),
    DataCorssBarForRead.io.DataOut
  )
  coreRsp_Q.io.enq.bits.instrId := rspPipe_st2_bits.instrId
  coreRsp_Q.io.enq.bits.activeMask := rspPipe_st2_bits.activeMask

  val stalledRspBits = RegNext(coreRsp_Q.io.enq.bits.asUInt)
  when(RegNext(coreRsp_Q.io.enq.valid && !coreRsp_Q.io.enq.ready, false.B)){
    assert(coreRsp_Q.io.enq.valid)
    assert(coreRsp_Q.io.enq.bits.asUInt === stalledRspBits)
  }

  // ******      core req ready
  //coreReq_ok_to_in := MshrAccess.io.missReq.ready && !missRspFromMshr_st2 && !io.memRsp.valid && coreRsp_Q.io.enq.ready && !Arbiter.io.bankConflict
  io.coreReq.ready := BankConfArb.io.reqReady
}
