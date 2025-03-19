package L1Cache.DCache

import L1Cache._
import chisel3._
import chisel3.util._
import top.parameters._

class WSHRreq extends Bundle{
  val blockAddr = UInt((dcache_SetIdxBits+dcache_TagBits).W)
}
class WSHRCheckResult(Depth:Int) extends Bundle{
  val Hit = Bool()
  val HitIdx = UInt(log2Up(Depth).W)
}
class DCacheWSHR(Depth:Int) extends Module{
  val io = IO(new Bundle{
    //push
    val pushReq = Flipped(DecoupledIO(new WSHRreq))
    val conflict = Output(Bool())
    val pushedIdx = Output(UInt(log2Up(Depth).W))
    //for invOrFlu
    val empty = Output(Bool())
    //pop
    val popReq = Flipped(ValidIO(UInt(log2Up(Depth).W)))
    //check
    val checkReq = Input(new WSHRreq)
    val wshrHit = Output(Bool())
    val hitIdx = Output(UInt(log2Up(Depth).W))

  })
 // assert(!(io.pushReq.valid && io.popReq.valid),"WSHR cant pop and push in same cycle")

  val blockAddrEntries = RegInit(VecInit(Seq.fill(Depth)(0.U((dcache_SetIdxBits+dcache_TagBits).W))))
  val valid: Vec[Bool] = RegInit(VecInit(Seq.fill(Depth)(false.B)))
  io.empty := !valid.reduceTree(_|_)

  //following circuit are same to L1TagAccess.scala tagChecker
  val pushMatchMask = Wire(UInt(Depth.W))
  pushMatchMask := Reverse(Cat(blockAddrEntries.zip(valid).map{
    case(bA,valid) => (bA === io.pushReq.bits.blockAddr) && valid}))
  assert(PopCount(pushMatchMask) <= 1.U,"WSHR dont store same bA in 2 entries")
  io.conflict := pushMatchMask.orR
  //check entry
  val checkMatchPush = (io.pushReq.bits.blockAddr === io.checkReq.blockAddr) && io.pushReq.valid
  val checkMatchMask = Wire(UInt(Depth.W))
  checkMatchMask := Reverse(Cat(blockAddrEntries.zip(valid).map{
    case(bA,valid) => (bA === io.checkReq.blockAddr) && valid}))
  val checkmatchIdx = OHToUInt(checkMatchMask)
  val checkMatchPop = (checkmatchIdx === io.popReq.bits) && io.popReq.valid
  io.wshrHit := (checkMatchMask.orR && !checkMatchPop) || checkMatchPush
  io.hitIdx := Mux(checkMatchPush, io.pushedIdx, checkmatchIdx)

  //pushReq.ready := !full
  io.pushReq.ready := !valid.reduceTree(_ & _)

  val nextEntryIdx = valid.indexWhere((x: Bool) => x === false.B)
  val PopPushInSameCycle = io.pushReq.fire && io.popReq.fire
  io.pushedIdx := Mux(PopPushInSameCycle,io.popReq.bits,nextEntryIdx)

  when(io.pushReq.fire && io.popReq.fire){
    blockAddrEntries(io.popReq.bits) := io.pushReq.bits.blockAddr
    valid(io.popReq.bits) := true.B
  }.elsewhen(io.pushReq.fire){
    blockAddrEntries(nextEntryIdx) := io.pushReq.bits.blockAddr
    valid(nextEntryIdx) := true.B
  }.elsewhen(io.popReq.valid){
    valid(io.popReq.bits) := false.B
  }
}


