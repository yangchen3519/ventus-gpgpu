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

import L1Cache._
import SRAMTemplate.SRAMTemplate
import chisel3._
import chisel3.util._
import top.parameters._

//abstract class MSHRBundle extends Bundle with L1CacheParameters

class MSHRprobe(val bABits: Int) extends Bundle {
  val blockAddr = UInt(bABits.W)
}
class MSHRprobeOut(val NEntry:Int, val NSub:Int) extends Bundle {
  val probeStatus = UInt(3.W)
  val a_source = UInt(log2Up(NEntry).W)
}
class MSHRmissReq(val bABits: Int, val tIWdith: Int, val WIdBits: Int) extends Bundle {// Use this bundle when handle miss issued from pipeline
  val blockAddr = UInt(bABits.W)
  val instrId = UInt(WIdBits.W)
  val targetInfo = UInt(tIWdith.W)
}
class MSHRmissRspIn(val NEntry: Int) extends Bundle {//Use this bundle when a block return from Lower cache
  val instrId = UInt(log2Up(NEntry).W)
}
class MSHRmissRspOut[T <: Data](val bABits: Int, val tIWdith: Int, val WIdBits: Int) extends Bundle {
  val targetInfo = UInt(tIWdith.W)
  val instrId = UInt(WIdBits.W)
  //val burst = Bool()//This bit indicate the Rsp transaction comes from subentry
  //val last = Bool()
}

class getEntryStatusReq(nEntry: Int) extends Module{
  val io = IO(new Bundle{
    val valid_list = Input(UInt(nEntry.W))
    val alm_full = Output(Bool())
    val full = Output(Bool())
    val next = Output(UInt(log2Up(nEntry).W))
    //val used = Output(UInt())
  })

  val used: UInt = PopCount(io.valid_list)
  io.alm_full := used === (nEntry.U-1.U)
  io.full := io.valid_list.andR
  io.next := VecInit(io.valid_list.asBools).indexWhere(_ === false.B)
}

class getEntryStatusRsp(nEntry: Int) extends Module{
  val io = IO(new Bundle{
    val valid_list = Input(UInt(nEntry.W))
    val next2cancel = Output(UInt(log2Up(nEntry).W))
    val used = Output(UInt((log2Up(nEntry)+1).W))
  })
  io.next2cancel := VecInit(io.valid_list.asBools).indexWhere(_ === true.B)
  io.used := PopCount(io.valid_list)

}

class MSHRpipe1Reg(WidthMatchProbe: Int, SubEntryNext: Int) extends Bundle{
  val entryMatchProbe = UInt(WidthMatchProbe.W)
  val subEntryIdx = UInt(SubEntryNext.W)
}



class MSHRmissReqv2(val bABits: Int, val tIWdith: Int, val WIdBits: Int) extends Bundle {// Use this bundle when handle miss issued from pipeline
  val blockAddr = UInt(bABits.W)
  val instrId   = UInt(WIdBits.W)
  val targetInfo = UInt(tIWdith.W)
  val missUncached = Bool() // 0-cached 1-uncached
}

object MSHRStatus{
  def PrimaryAvail : UInt = 0.U(3.W)
  def PrimaryFull : UInt = 1.U(3.W)
  def SecondaryAvail : UInt = 2.U(3.W)
  def SecondaryFull : UInt = 3.U(3.W)
  def ReturnMatch : UInt = 4.U(3.W)

}
class MSHRv2(val bABits: Int, val MshrSet: Int,val MshrSetBits: Int, val MshrTagBits:Int, val tIWidth: Int, val WIdBits: Int, val MshrWay:Int, val NMshrSubEntry:Int) extends Module {
  val io = IO(new Bundle {
    val probe = Flipped(ValidIO(new MSHRprobe(bABits)))
    val probeOut_st1 = Output(new MSHRprobeOut(MshrWay, NMshrSubEntry))
    val missReq = Flipped(Decoupled(new MSHRmissReqv2(bABits, tIWidth, WIdBits)))
    val UncacheRsp = Output(Bool())//0-cached 1-no cache
    val missRspIn = Flipped(Decoupled(new MSHRmissRspIn(NMshrEntry)))
    val missRspOut = Decoupled(new MSHRmissRspOut(bABits, tIWidth, WIdBits))
    //For InOrFlu
    val empty = Output(Bool())

  })
  // use SRAM for MSHR
  // way associate

  // head of entry, for comparison
  //val blockAddr_Access = RegInit(VecInit(Seq.fill(NMshrEntry)(0.U(bABits.W))))
  val targetInfo_Accesss = RegInit(VecInit(Seq.fill(NMshrEntry)(VecInit(Seq.fill(NMshrSubEntry)(0.U(tIWidth.W))))))

  //SRAM to store blockAddr
  val BlockAddrAccess = Module(new SRAMTemplate(
    UInt(MshrTagBits.W),
    set=MshrSet,
    way=MshrWay,
    shouldReset = false,
    holdRead = true,
    singlePort = false,
    bypassWrite = false
  ))
  val cacheStatus_Access = RegInit(VecInit(Seq.fill(MshrSet)(VecInit(Seq.fill(MshrWay)(false.B)))))
  val subentry_valid = RegInit(VecInit(Seq.fill(MshrSet*MshrWay)(VecInit(Seq.fill(NMshrSubEntry)(false.B)))))
  val entry_valid_vec = Reverse(Cat(subentry_valid.map(Cat(_).orR)))//in form of vec
  val entry_valid = WireInit(VecInit(Seq.fill(MshrSet)(VecInit(Seq.fill(MshrWay)(false.B))))) // array set * wat
  val BlockAddrRRsp = WireInit(VecInit(Seq.fill(MshrSet)(VecInit(Seq.fill(MshrWay)(0.U(MshrTagBits.W))))))

  io.empty := !entry_valid_vec.orR

  for(i<-0 until MshrSet){
    for(j<-0 until MshrWay){
      entry_valid(i)(j) := entry_valid_vec(i*MshrWay+j)
    }
  }

  // cut probeRead bAbits into set and tag
  val bAset_st0 = if (MshrSetBits != 0) {io.probe.bits.bABits.asUInt(MshrSetBits -1,0)} else 0.U
  val bAtag_st0 = io.probe.bits.bABits.asUInt(bABits-1,MshrSetBits)
  val bAtag_st1 = io.missReq.bits.bABits.asUInt(bABits-1,MshrSetBits)
  val bAset_st1 = if (MshrSetBits != 0) {io.missReq.bits.bABits.asUInt(MshrSetBits -1,0)} else 0.U
  val itagChecker = Module(new tagChecker(MshrWay,MshrTagBits))
  // hit status in BlockAccess, aka hit in main entry
  val hitWayMask = Wire(Vec(MshrWay,Bool()))
  val hitMainEntry = Wire(Bool()) // indicate hit in blockAccess, req is secondary miss, but may not be handle due to secondary full

  // probe read interface
  BlockAddrAccess.io.r.req.valid := io.probe.valid
  BlockAddrAccess.io.r.req.bits.setIdx := bAset_st0
  // hit in MSHR tag check, will happen in stage1
  BlockAddrRRsp := BlockAddrAccess.io.r.resp.data
  itagChecker.io.tag_of_set := BlockAddrRRsp
  itagChecker.io.tag_from_pipe := bAtag_st1
  itagChecker.io.way_valid := entry_valid(bAset_st1)
  hitWayMask := itagChecker.io.waymask
  hitMainEntry := itagChecker.io.cache_hit




  //  ******     missReq decide selected subentries are full or not     ******
  val entryMatchMissRsp = Wire(UInt(log2Up(NMshrEntry).W))
  val entryMatchProbe = Wire(UInt(NMshrEntry.W))
  val allfalse_subentryvalidtype = Wire(Vec(NMshrSubEntry,Bool()))
  for (i<-0 until NMshrSubEntry){
    allfalse_subentryvalidtype(i) := false.B
  }
  val subentryStatus = Module(new getEntryStatusReq(NMshrSubEntry)) // Output: alm_full, full, next


 // subentryStatus.io.valid_list := Reverse(Cat(subentrySelectedForReq))

  //  ******     missRsp status      ******
  val subentryStatusForRsp = Module(new getEntryStatusRsp(NMshrSubEntry))

  //  ******     missReq decide MSHR is full or not     ******
  val entryStatus = Module(new getEntryStatusReq(NMshrEntry))
  entryStatus.io.valid_list := entry_valid

  // ******     enum vec_mshr_status     ******
  val mshrStatus_st1_w = Wire(UInt(3.W))
  val mshrStatus_st0 = Wire(UInt(3.W))

  /*PRIMARY_AVAIL         000
  * PRIMARY_FULL          001
  * SECONDARY_AVAIL       010
  * SECONDARY_FULL        011
  * SECONDARY_FULL_RETURN 100
  * PRIMARY_ALM_FULL      101
  * SECONDARY_ALM_FULL    111
  * see as always valid, validity relies on external procedures
  * */
  // ******      mshr::probe_vec    ******
  entryMatchProbe := Reverse(Cat(blockAddr_Access.map(_ === io.missReq.bits.blockAddr))) & entry_valid
  assert(PopCount(entryMatchProbe) <= 1.U)
  val entryMatchProbeid = OHToUInt(entryMatchProbe)//RegEnable(OHToUInt(entryMatchProbe),io.missReq.fire())
  val secondaryMiss = entryMatchProbe.orR
  val primaryMiss = !secondaryMiss
  val mainEntryFull = entryStatus.io.full
  subentryStatus.io.valid_list := subentry_valid(entryMatchProbeid)
  val subentryFull_sel = subentryStatus.io.full
  val subentryAvail_sel = !subentryStatus.io.full
  val RspReqMatch = (blockAddr_Access(entryMatchMissRsp) === io.missReq.bits.blockAddr) && io.missReq.valid && io.missRspIn.valid

  when(mainEntryFull){
    mshrStatus_st1_w := MSHRStatus.PrimaryFull
  }.elsewhen(subentryFull_sel){
    mshrStatus_st1_w := MSHRStatus.SecondaryFull
  }.elsewhen(RspReqMatch){
    mshrStatus_st1_w := MSHRStatus.ReturnMatch
  }.elsewhen(subentryAvail_sel){
    mshrStatus_st1_w := MSHRStatus.SecondaryAvail
  }.otherwise{
    mshrStatus_st1_w := MSHRStatus.PrimaryAvail
  }



  // mshrStatus_st0 := mshrStatus_st1_w
  val subEntryIdx_st1 = subentryStatus.io.next
  //mshrStatus依赖primaryMiss和SecondaryMiss，它们依赖entryValid。
  //mshrStatus必须是寄存器，需要在probe valid的下个周期正确显示。entryValid更新的下一个周期已经来不及。
  //所以用组合逻辑加工一次mshrStatus。
  io.probeOut_st1.probeStatus := mshrStatus_st1_w

  //  ******     mshr::allocate_vec_sub/allocate_vec_main     ******
  /*0:PRIMARY_AVAIL 1:PRIMARY_FULL 2:SECONDARY_AVAIL 3:SECONDARY_FULL*/
  io.missReq.ready := !(mshrStatus_st1_w === MSHRStatus.PrimaryFull || mshrStatus_st1_w === MSHRStatus.SecondaryFull || mshrStatus_st1_w === MSHRStatus.ReturnMatch )
  assert(!io.missReq.fire || (io.missReq.fire && !io.missRspIn.fire), "MSHR cant have Req & Rsp valid in same cycle, later the prior")
  val real_SRAMAddrUp = Mux(secondaryMiss, entryMatchProbeid, entryStatus.io.next)
  val real_SRAMAddrDown = Mux(secondaryMiss, subEntryIdx_st1, 0.U)
  when(io.missReq.fire) {
    targetInfo_Accesss(real_SRAMAddrUp)(real_SRAMAddrDown) := io.missReq.bits.targetInfo
//    cacheStatus_Access(real_SRAMAddrUp)(real_SRAMAddrDown) := io.missReq.bits.missUncached
  }

  when(io.missReq.fire && mshrStatus_st1_w === 0.U) { //PRIMARY_AVAIL
    blockAddr_Access(entryStatus.io.next) := io.missReq.bits.blockAddr
  }
  when(io.missReq.fire){
    cacheStatus_Access(real_SRAMAddrUp) := io.missReq.bits.missUncached
  }

  io.probeOut_st1.a_source := Mux(io.missReq.valid,real_SRAMAddrUp,entryMatchProbeid)

  //  ******      mshr::vec_arrange_core_rsp    ******
  subentryStatusForRsp.io.valid_list := Reverse(Cat(subentry_valid(entryMatchMissRsp)))
  // priority: missRspIn > missReq
  //assert(!io.missRspIn.fire || (io.missRspIn.fire && subentryStatus.io.used >= 1.U))
  //This version allow missRspIn fire when no subentry are left
  //如果后面发现missRspOut端口这一级不能取消，使用这段注释掉的代码
  //io.missRspIn.ready := !(subentryStatusForRsp.io.used >= 2.U ||
  //  (subentryStatusForRsp.io.used === 1.U && !io.missRspOut.ready))
  io.missRspIn.ready := !((subentryStatusForRsp.io.used >= 2.U) ||
    ((mshrStatus_st1_w === 4.U || mshrStatus_st1_w === 3.U) && subentryStatusForRsp.io.used === 1.U))

  entryMatchMissRsp := io.missRspIn.bits.instrId
  //entryMatchMissRsp := Reverse(Cat(instrId_Access.map(_ === io.missRspIn.bits.instrId))) & entry_valid
  //assert(PopCount(entryMatchMissRsp) <= 1.U,"MSHR missRspIn, cant match multiple entries")
  val subentry_next2cancel = Wire(UInt(log2Up(NMshrSubEntry).W))
  subentry_next2cancel := subentryStatusForRsp.io.next2cancel

  val missRspTargetInfo_st0 = targetInfo_Accesss(entryMatchMissRsp)(subentry_next2cancel)
  val missRspBlockAddr_st0 = blockAddr_Access(entryMatchMissRsp)

  io.UncacheRsp := cacheStatus_Access(entryMatchMissRsp)(subentry_next2cancel)

  io.missRspOut.bits.targetInfo := RegNext(missRspTargetInfo_st0)
  io.missRspOut.bits.blockAddr := RegNext(missRspBlockAddr_st0)
  io.missRspOut.bits.instrId := io.missRspIn.bits.instrId
  io.missRspOut.valid := RegNext(io.missRspIn.valid ) && !(RegNext(subentryStatusForRsp.io.used)===0.U)
  //io.missRspOut := RegNext(io.missRspIn.valid) &&
  //  subentryStatusForRsp.io.used >= 1.U//如果上述Access中改出SRAM，本信号需要延迟一个周期

  //  ******     maintain subentries    ******
  /*0:PRIMARY_AVAIL 1:PRIMARY_FULL 2:SECONDARY_AVAIL 3:SECONDARY_FULL*/
  for (iofEn <- 0 until NMshrEntry) {
    for (iofSubEn <- 0 until NMshrSubEntry) {
      when(iofEn.asUInt === entryStatus.io.next &&
        iofSubEn.asUInt === 0.U && io.missReq.fire && primaryMiss) {
        subentry_valid(iofEn)(iofSubEn) := true.B
      }.elsewhen(iofEn.asUInt === entryMatchMissRsp && iofSubEn.asUInt === subentry_next2cancel &&
        io.missRspIn.valid) {
        subentry_valid(iofEn)(iofSubEn) := false.B
      }
    }.elsewhen(iofSubEn.asUInt === subEntryIdx_st1 &&
      io.missReq.fire && secondaryMiss && iofEn.asUInt === entryMatchProbeid) {
      subentry_valid(iofEn)(iofSubEn) := true.B
    } //order of when & elsewhen matters, as elsewhen cover some cases of when, but no op to them
  }
}

