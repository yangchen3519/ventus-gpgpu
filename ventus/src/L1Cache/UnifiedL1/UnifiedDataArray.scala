package L1Cache.UnifiedL1

import chisel3._
import chisel3.util._
import top.parameters._

class UnifiedDCacheReadReq extends Bundle {
  val physicalSlot = UInt(log2Ceil(unified_l1_partition_banks).W)
}

class UnifiedDCacheReadResp extends Bundle {
  val data = Vec(dcache_BlockWords, UInt(xLen.W))
}

class UnifiedDCacheWriteReq extends Bundle {
  val physicalSlot = UInt(log2Ceil(unified_l1_partition_banks).W)
  val data = Vec(dcache_BlockWords, UInt(xLen.W))
  val mask = Vec(dcache_BlockWords, UInt(BytesOfWord.W))
}

class UnifiedSMemReq extends Bundle {
  val physicalSlot = UInt(log2Ceil(unified_l1_partition_banks).W)
  val isWrite = Bool()
  val bankEn = Vec(sharedmem_BlockWords, Bool())
  val wdata = Vec(sharedmem_BlockWords, UInt(xLen.W))
  val wmask = Vec(sharedmem_BlockWords, UInt(BytesOfWord.W))
}

class UnifiedSMemResp extends Bundle {
  val rdata = Vec(sharedmem_BlockWords, UInt(xLen.W))
}

class UnifiedDataArray extends Module {
  require(dcache_BlockWords == sharedmem_BlockWords)
  require(BytesOfWord == 4)

  private val nWords = dcache_BlockWords
  private val slotBits = log2Ceil(unified_l1_partition_banks)

  val io = IO(new Bundle {
    val dcacheReadReq = Flipped(Decoupled(new UnifiedDCacheReadReq))
    val dcacheReadResp = Valid(new UnifiedDCacheReadResp)
    val dcacheWriteReq = Flipped(Decoupled(new UnifiedDCacheWriteReq))

    val smemReq = Flipped(Decoupled(new UnifiedSMemReq))
    val smemResp = Valid(new UnifiedSMemResp)
  })

  io.dcacheReadReq.ready := true.B
  io.dcacheWriteReq.ready := true.B
  io.smemReq.ready := true.B

  val dcacheReadFire = io.dcacheReadReq.fire
  val dcacheWriteFire = io.dcacheWriteReq.fire
  val smemReadFire = io.smemReq.fire && !io.smemReq.bits.isWrite
  val smemWriteFire = io.smemReq.fire && io.smemReq.bits.isWrite

  io.dcacheReadResp.valid := RegNext(dcacheReadFire, false.B)
  io.smemResp.valid := RegNext(smemReadFire, false.B)

  for (word <- 0 until nWords) {
    val array = SyncReadMem(unified_l1_partition_banks, Vec(BytesOfWord, UInt(8.W)))

    val dcacheReadBytes = array.read(io.dcacheReadReq.bits.physicalSlot, dcacheReadFire)
    val smemReadBytes = array.read(io.smemReq.bits.physicalSlot, smemReadFire && io.smemReq.bits.bankEn(word))

    val dcacheWriteMask = io.dcacheWriteReq.bits.mask(word)
    val dcacheWriteEn = dcacheWriteFire && dcacheWriteMask.orR
    val dcacheWriteBytes = io.dcacheWriteReq.bits.data(word).asTypeOf(Vec(BytesOfWord, UInt(8.W)))

    val smemWriteMask = io.smemReq.bits.wmask(word)
    val smemWriteEn = smemWriteFire && io.smemReq.bits.bankEn(word) && smemWriteMask.orR
    val smemWriteBytes = io.smemReq.bits.wdata(word).asTypeOf(Vec(BytesOfWord, UInt(8.W)))

    when(dcacheWriteEn) {
      array.write(io.dcacheWriteReq.bits.physicalSlot, dcacheWriteBytes, dcacheWriteMask.asBools)
    }
    when(smemWriteEn) {
      array.write(io.smemReq.bits.physicalSlot, smemWriteBytes, smemWriteMask.asBools)
    }

    when(dcacheWriteEn && smemWriteEn) {
      assert(io.dcacheWriteReq.bits.physicalSlot =/= io.smemReq.bits.physicalSlot,
        "UnifiedDataArray DCache/SMEM write conflict on same physical slot")
    }

    val dcacheBypassHit =
      RegNext(dcacheReadFire && dcacheWriteEn &&
        io.dcacheReadReq.bits.physicalSlot === io.dcacheWriteReq.bits.physicalSlot, false.B)
    val smemBypassToDCacheHit =
      RegNext(dcacheReadFire && smemWriteEn &&
        io.dcacheReadReq.bits.physicalSlot === io.smemReq.bits.physicalSlot, false.B)
    val smemBypassHit =
      RegNext(smemReadFire && io.smemReq.bits.bankEn(word) && smemWriteEn &&
        io.smemReq.bits.physicalSlot === io.smemReq.bits.physicalSlot, false.B)
    val dcacheBypassToSMemHit =
      RegNext(smemReadFire && io.smemReq.bits.bankEn(word) && dcacheWriteEn &&
        io.smemReq.bits.physicalSlot === io.dcacheWriteReq.bits.physicalSlot, false.B)

    val dcacheBypassMask = RegNext(dcacheWriteMask)
    val dcacheBypassBytes = RegNext(dcacheWriteBytes)
    val smemBypassMask = RegNext(smemWriteMask)
    val smemBypassBytes = RegNext(smemWriteBytes)

    val dcacheRespBytes = Wire(Vec(BytesOfWord, UInt(8.W)))
    val smemRespBytes = Wire(Vec(BytesOfWord, UInt(8.W)))
    for (byte <- 0 until BytesOfWord) {
      val dcacheAfterDCacheWrite = Mux(
        dcacheBypassHit && dcacheBypassMask(byte),
        dcacheBypassBytes(byte),
        dcacheReadBytes(byte)
      )
      dcacheRespBytes(byte) := Mux(
        smemBypassToDCacheHit && smemBypassMask(byte),
        smemBypassBytes(byte),
        dcacheAfterDCacheWrite
      )

      val smemAfterSMemWrite = Mux(
        smemBypassHit && smemBypassMask(byte),
        smemBypassBytes(byte),
        smemReadBytes(byte)
      )
      smemRespBytes(byte) := Mux(
        dcacheBypassToSMemHit && dcacheBypassMask(byte),
        dcacheBypassBytes(byte),
        smemAfterSMemWrite
      )
    }

    io.dcacheReadResp.bits.data(word) := Cat(dcacheRespBytes.reverse)
    io.smemResp.bits.rdata(word) := Cat(smemRespBytes.reverse)
  }
}
