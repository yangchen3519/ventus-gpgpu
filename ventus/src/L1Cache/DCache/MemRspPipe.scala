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
import L1Cache.DCache.DCacheParameters._
import SRAMTemplate._
import chisel3._
import chisel3.util._
import config.config.Parameters
import top.parameters._
import mmu.SV32.{asidLen, paLen, vaLen}
import L1Cache._

class memRspPipe_st1(implicit p: Parameters) extends DCacheBundle{
  val Rsp  = new DCacheMemRsp
  val isRead = Bool()
  val isSpecial = Bool()
  val isCached = Bool()
}

class MemRspPipe(implicit p: Parameters) extends DCacheModule{
    val io = IO(new Bundle{
         val memRsp = Flipped(DecoupledIO(new DCacheMemRsp))
         val memRspIsFlu = Output(Bool())
         //st0
         val tAAllocateWriteReq = ValidIO(new SRAMBundleA(NSets)) // ta allocate write
         val RTABUpdateReq      = ValidIO(new RTABUpdate) // Update RTAB
         val MSHRMissRsp        = Decoupled(new MSHRmissRspIn(NMshrEntry))
         val SMSHRMissRsp       = Decoupled(new MSHRmissRspIn(NMshrEntry))
         val WSHRPopReq         = ValidIO(UInt(log2Up(NMshrEntry).W))         
         val MSHRMissRspOutUCached = Input(Bool())

         //st1
         val memRsp_coreRsp     = DecoupledIO(new CoreRspPipe_st2)
         val MSHRMissRspOut     = Flipped(DecoupledIO(new MSHRmissRspOut(bABits, tIBits, WIdBits, asidLen)))
         val MSHRMissRspOutAsid = if(MMU_ENABLED) Some(Input(UInt(asidLen.W))) else None

         val SMSHRMissRspOut     = Flipped(DecoupledIO(new MSHRmissRspOut(bABits, tIBits, WIdBits, asidLen)))
         val SMSHRMissRspOutAsid = if(MMU_ENABLED) Some(Input(UInt(asidLen.W))) else None

         val dAmemRsp_wReq         = Output(Vec(BlockWords, new SRAMBundleAW(UInt(8.W), NSets * NWays, BytesOfWord)))
         val dAmemRsp_wReq_valid   = Output(Bool())
         val dAReplace_rReq        = Output(Vec(BlockWords, new SRAMBundleA(NSets * NWays)))
         val dAReplace_rReq_valid  = Output(Bool())
         val memReq_valid          = Output(Bool())
         val tAWayMask             = Input(UInt(NWays.W))
         val needReplace           = Input(Bool())
         val memReq_ready          = Input(Bool())

    })
    // st0
    val MemRsp_pipeReg_st0_st1 = Module(new Queue(new memRspPipe_st1,entries = 1,flow=false,pipe=true)).io

    val st0_ready = Wire(Bool())
    val st0_valid = Wire(Bool())
    val RTABUpdateReq_valid = Wire(Bool())
    val idx_st0 = get_ID(io.memRsp.bits.d_source)
    val memRspisRead = io.memRsp.bits.d_opcode === 1.U && io.memRsp.bits.d_param === 0.U
    val memRspisWrite = io.memRsp.bits.d_opcode === 0.U
    val memRspisFlushOrInv = io.memRsp.bits.d_opcode === 2.U
    val memRspisLRSC = io.memRsp.bits.d_opcode === 1.U && (get_Type(io.memRsp.bits.d_source) === 2.U)
    val memRspisAMO  = io.memRsp.bits.d_opcode === 1.U && (get_Type(io.memRsp.bits.d_source) === 2.U)
    io.memRsp.ready := st0_ready
    // st1
    val missRspTI_st1 = Wire(new VecMshrTargetInfo)
    val memRsp_st1_isRead = MemRsp_pipeReg_st0_st1.deq.bits.isRead
    val memRsp_st1_isSpecial = MemRsp_pipeReg_st0_st1.deq.bits.isSpecial
    val dAReq_valid = Wire(Bool())
    val st1_ready = Wire(Bool())

    // -----st0-----
    io.memRspIsFlu := memRspisFlushOrInv && io.memRsp.valid
    io.tAAllocateWriteReq.valid := io.memRsp.valid && io.memRsp.bits.d_opcode === 1.U && io.memRsp.bits.d_param === 0.U && !io.MSHRMissRspOutUCached// CACHED READ RESPONSE
    io.tAAllocateWriteReq.bits.setIdx := io.memRsp.bits.d_source(SetIdxBits-1,0)
    io.MSHRMissRsp.valid := io.memRsp.valid && memRspisRead
    io.MSHRMissRsp.bits.instrId := idx_st0
    io.SMSHRMissRsp.valid := io.memRsp.valid && (memRspisLRSC || memRspisAMO)
    io.SMSHRMissRsp.bits.instrId := idx_st0

    io.RTABUpdateReq.bits.mshrIdx := idx_st0
    io.RTABUpdateReq.bits.wshrIdx := idx_st0
    io.RTABUpdateReq.bits.blockAddr := get_blockAddr(io.memRsp.bits.d_addr)
    io.RTABUpdateReq.bits.updateType := 0.U
    io.RTABUpdateReq.valid := RTABUpdateReq_valid
    io.WSHRPopReq.bits := idx_st0
    io.WSHRPopReq.valid := io.memRsp.valid && memRspisWrite

    when(memRspisLRSC || memRspisAMO){
        io.RTABUpdateReq.bits.updateType := 2.U
    }.elsewhen(memRspisWrite){
        io.RTABUpdateReq.bits.updateType := 1.U
    }
    st0_ready := false.B
    when(memRspisFlushOrInv){
        st0_ready := true.B      
    }.elsewhen(memRspisWrite){
        st0_ready := true.B
    }.elsewhen(memRspisLRSC || memRspisAMO){
        st0_ready := io.SMSHRMissRsp.ready && MemRsp_pipeReg_st0_st1.enq.ready
    }.elsewhen(memRspisRead){
        st0_ready := io.MSHRMissRsp.ready && MemRsp_pipeReg_st0_st1.enq.ready
    }
    RTABUpdateReq_valid := io.memRsp.valid && st0_ready
    st0_valid := false.B
    when(io.memRsp.valid){
        when(memRspisFlushOrInv){
           st0_valid :=  false.B      
       }.elsewhen(memRspisWrite){
           st0_valid := false.B
       }.elsewhen(memRspisLRSC || memRspisAMO){
           st0_valid := io.SMSHRMissRsp.ready // to check
       }.elsewhen(memRspisRead){
           st0_valid := !io.MSHRMissRsp.ready
       }
    }
    MemRsp_pipeReg_st0_st1.enq.valid := st0_valid
    MemRsp_pipeReg_st0_st1.enq.bits.Rsp := io.memRsp.bits
    MemRsp_pipeReg_st0_st1.enq.bits.isRead := memRspisRead
    MemRsp_pipeReg_st0_st1.enq.bits.isSpecial := memRspisFlushOrInv || memRspisLRSC || memRspisAMO
    MemRsp_pipeReg_st0_st1.enq.bits.isCached := Mux(memRspisRead,io.MSHRMissRspOutUCached,false.B)

    missRspTI_st1 := Mux(memRsp_st1_isSpecial,
        io.SMSHRMissRspOut.bits.targetInfo.asTypeOf(new VecMshrTargetInfo),
        io.MSHRMissRspOut.bits.targetInfo.asTypeOf(new VecMshrTargetInfo))
    val missRspAsid_st1 = if(MMU_ENABLED) Some(UInt(asidLen.W)) else None
    if(MMU_ENABLED){
        missRspAsid_st1.get := Mux(memRsp_st1_isSpecial,
            io.SMSHRMissRspOutAsid.get,
            io.MSHRMissRspOutAsid.get)
    }
    io.MSHRMissRspOut.ready := io.memRsp_coreRsp.ready
    io.SMSHRMissRspOut.ready := io.memRsp_coreRsp.ready
    // ---st1---
    // coreRsp
    io.memRsp_coreRsp.valid := Mux(MemRsp_pipeReg_st0_st1.deq.bits.isSpecial,
        io.SMSHRMissRspOut.valid,
        io.MSHRMissRspOut.valid)
    io.memRsp_coreRsp.bits.Rsp.data := MemRsp_pipeReg_st0_st1.deq.bits.Rsp.d_data
    io.memRsp_coreRsp.bits.Rsp.isWrite := false.B
    io.memRsp_coreRsp.bits.Rsp.instrId := missRspTI_st1.instrId
    io.memRsp_coreRsp.bits.Rsp.activeMask := missRspTI_st1.perLaneAddr.map(_.activeMask)
    io.memRsp_coreRsp.bits.perLaneAddr := missRspTI_st1.perLaneAddr
    io.memRsp_coreRsp.bits.validFromCoreReq := false.B
    // write to dA sram
    io.dAmemRsp_wReq.foreach(_.waymask.get := Fill(BytesOfWord, true.B))
    io.dAmemRsp_wReq.foreach(_.setIdx := Cat(MemRsp_pipeReg_st0_st1.deq.bits.Rsp.d_source(SetIdxBits-1,0),OHToUInt(io.tAWayMask)))
    for (i <- 0 until BlockWords) {
      io.dAmemRsp_wReq(i).data := MemRsp_pipeReg_st0_st1.deq.bits.Rsp.d_data(i).asTypeOf(Vec(BytesOfWord, UInt(8.W)))
    }
    val idle :: dAread :: memReq :: Nil = Enum(3)
    val tagRequestStatus = RegInit(idle)
    val tagRequestStatus_next = WireInit(tagRequestStatus)
    val st1_valid = MemRsp_pipeReg_st0_st1.deq.valid && MemRsp_pipeReg_st0_st1.deq.bits.isRead && MemRsp_pipeReg_st0_st1.deq.bits.isCached
    st1_ready := io.memRsp_coreRsp.ready
    tagRequestStatus := tagRequestStatus_next
    tagRequestStatus_next := tagRequestStatus
    switch(tagRequestStatus){
        is(idle){
            when(io.needReplace && st1_valid){
                tagRequestStatus_next := dAread
            }.elsewhen(st1_valid && !io.needReplace){
                tagRequestStatus_next := tagRequestStatus
            }
          when(io.needReplace){
            st1_ready := false.B
          }.otherwise{
            st1_ready := io.memRsp_coreRsp.ready
          }
        }
        is(dAread){
                tagRequestStatus_next := memReq
                st1_ready := false.B
        }
        is(memReq){
            when(io.memReq_ready){
                tagRequestStatus_next := idle
                st1_ready := io.memRsp_coreRsp.ready
            }.otherwise{
                st1_ready := false.B
            }
        }
    }
    MemRsp_pipeReg_st0_st1.deq.ready := st1_ready
    io.dAmemRsp_wReq_valid := dAReq_valid
    io.dAReplace_rReq.foreach(_.setIdx := Cat(MemRsp_pipeReg_st0_st1.deq.bits.Rsp.d_source(SetIdxBits-1,0),OHToUInt(io.tAWayMask)))
    dAReq_valid := tagRequestStatus === idle && st1_valid && st1_ready
    io.dAReplace_rReq_valid := tagRequestStatus_next === dAread && st1_valid
    io.memReq_valid := tagRequestStatus === memReq && st1_valid
}