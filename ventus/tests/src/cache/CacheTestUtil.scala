package cache

import chisel3._
import chisel3.util._
import L2cache.{TLBundleA_lite_custom, TLBundleD_lite_plus_custom, InclusiveCacheParameters_lite, TLBundleA_lite, TLBundleD_lite_plus}
import L1Cache.AtomicUnit.AtomicUnit
import top.parameters._
import config.config.Parameters

class L2CacheUtil(params: InclusiveCacheParameters_lite) extends Module {
  val io = IO(new Bundle {
    val memReq = Flipped(Decoupled(new TLBundleA_lite_custom(params)))
    val memRsp = Decoupled(new TLBundleD_lite_plus_custom(params))
  })

  // ROM storage
  val romSize = 256  // 1KB ROM
  val rom = Mem(romSize, UInt((dcache_BlockWords * xLen).W))
  
  // Initialize ROM with some test data
  for (i <- 0 until romSize) {
    rom.write(i.U, (i * 2).U)
  }

  // Request queue
  val reqQueue = Module(new Queue(new Bundle {
    val addr = UInt(params.addressBits.W)
    val source = UInt(params.source_bits_custom.W)
    val opcode = UInt(3.W)
    val param = UInt(3.W)
    val size = UInt(3.W)
    val data = UInt((dcache_BlockWords*xLen).W)
    val mask = UInt((dcache_BlockWords*4).W)
  }, 4))

  // Default outputs
  io.memReq.ready := reqQueue.io.enq.ready
  io.memRsp.valid := false.B
  io.memRsp.bits := DontCare

  // Handle incoming requests
  reqQueue.io.enq.valid := io.memReq.valid
  reqQueue.io.enq.bits.addr := io.memReq.bits.address
  reqQueue.io.enq.bits.source := io.memReq.bits.source
  reqQueue.io.enq.bits.opcode := io.memReq.bits.opcode
  reqQueue.io.enq.bits.param := io.memReq.bits.param
  reqQueue.io.enq.bits.size := io.memReq.bits.size
  reqQueue.io.enq.bits.data := io.memReq.bits.data
  reqQueue.io.enq.bits.mask := io.memReq.bits.mask

  // Handle write operations immediately
  when(io.memReq.valid && (io.memReq.bits.opcode === 1.U || io.memReq.bits.opcode === 0.U)) {
    val writeAddr = io.memReq.bits.address >> (2+dcache_BlockOffsetBits)
    val writeData = io.memReq.bits.data
    val writeMask = io.memReq.bits.mask
    
    // Read current data
    val currentData = rom.read(writeAddr)
    
    // Apply mask and write new data
    val newData = (currentData & ~writeMask) | (writeData & writeMask)
    rom.write(writeAddr, newData)
  }

  // Handle responses
  when(reqQueue.io.deq.valid) {
    io.memRsp.valid := true.B
    io.memRsp.bits.opcode := Mux(reqQueue.io.deq.bits.opcode === 4.U, 1.U, 0.U)
    io.memRsp.bits.param := reqQueue.io.deq.bits.param
    io.memRsp.bits.size := reqQueue.io.deq.bits.size
    io.memRsp.bits.source := reqQueue.io.deq.bits.source
    io.memRsp.bits.data := rom.read(reqQueue.io.deq.bits.addr >> (2+dcache_BlockOffsetBits))
    io.memRsp.bits.address := reqQueue.io.deq.bits.addr

    when(io.memRsp.ready) {
      reqQueue.io.deq.ready := true.B
    }.otherwise {
      reqQueue.io.deq.ready := false.B
    }
  }.otherwise {
    reqQueue.io.deq.ready := false.B
  }
} 
  


class AtomicWrapper(params: InclusiveCacheParameters_lite)(implicit p: Parameters) extends Module {
  val io = IO(new Bundle {
    val L12ATUmemReq = Flipped(Decoupled(new TLBundleA_lite(params)))
    val ATU2L1memRsp = Decoupled(new TLBundleD_lite_plus(params))
  })

  // Instantiate AtomicUnit
  val atomicUnit = Module(new AtomicUnit(params))
  
  // Instantiate L2ROM
  val l2 = Module(new L2CacheUtil(params))

  // Connect AtomicUnit to L2ROM
  atomicUnit.io.ATU2L2memReq <> l2.io.memReq
  atomicUnit.io.L22ATUmemRsp <> l2.io.memRsp

  // Connect external ports
  io.L12ATUmemReq <> atomicUnit.io.L12ATUmemReq
  io.ATU2L1memRsp <> atomicUnit.io.ATU2L1memRsp
} 