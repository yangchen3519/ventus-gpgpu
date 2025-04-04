package cache

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import chisel3.util._
import chiseltest._
import org.scalatest.freespec.AnyFreeSpec
import top.parameters._
import L1Cache.DCache._
import L1Cache.AtomicUnit.AtomicUnit
import L1Cache.{ MyConfig,  RVGParameters }
import L2cache._
import chiseltest.{ChiselScalatestTester, WriteVcdAnnotation}
import config.config.Parameters

class AtomicUnitTest extends AnyFreeSpec with ChiselScalatestTester {
  implicit val p = Parameters.empty
  val params = InclusiveCacheParameters_lite(CacheParameters(2,l2cache_NSets,l2cache_NWays,num_l2cache,blockBytes=(l2cache_BlockWords<<2),beatBytes=(l2cache_BlockWords<<2)),InclusiveCacheMicroParameters(l2cache_writeBytes,l2cache_memCycles,l2cache_portFactor,num_warp,num_sm,num_sm_in_cluster,num_cluster,dcache_MshrEntry,dcache_NSets,atuns_NInfWriteEntry),false, MMU_ENABLED)
  val param = (new MyConfig).toInstance
//  "AtomicUnit Arithmetic Operations" in {
//    test(new AtomicUnit(params)).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
//      // Initialize signals
//      dut.io.isAtomic.poke(true.B)
//      dut.io.L12ATUmemReq.initSource().setSourceClock(dut.clock)
//      dut.io.L22ATUmemRsp.initSink().setSinkClock(dut.clock)
//      dut.io.ATU2L2memReq.initSink().setSinkClock(dut.clock)
//      dut.io.ATU2L1memRsp.initSource().setSourceClock(dut.clock)
//      
//      // Test ADD operation
//      dut.io.L12ATUmemReq.enqueue(new TLBundleA_lite(params).Lit(
//        _.opcode -> TLAOp_Arith.U,
//        _.param -> TLAParam_ArithAdd.U,
//        _.address -> "h1000".U,
//        _.data -> 5.U,
//        _.mask -> "b0001".U,
//        _.source -> 1.U
//      ))
//      
//      // Wait for Get request
//      dut.clock.step(1)
//      dut.io.ATU2L2memReq.valid.expect(true.B)
//      dut.io.ATU2L2memReq.bits.opcode.expect(TLAOp_Get.U)
//      
//      // Send response with old value
//      dut.io.L22ATUmemRsp.enqueue(new TLBundleD_lite_plus_custom(params).Lit(
//        _.opcode -> 0.U,
//        _.data -> 3.U,
//        _.source -> "b1100001".U,
//        _.param -> 0.U
//      ))
//      
//      // Wait for Put request with result
//      dut.clock.step(1)
//      dut.io.ATU2L2memReq.valid.expect(true.B)
//      dut.io.ATU2L2memReq.bits.opcode.expect(TLAOp_PutPart.U)
//      dut.io.ATU2L2memReq.bits.data.expect(8.U) // 5 + 3 = 8
      
      // Send final response
//      dut.io.L22ATUmemRsp.enqueue(new TLBundleD_lite_plus_custom(params).Lit(
//        _.opcode -> 1.U,
//        _.data -> 0.U,
//        _.source -> "b1100001".U,
//        _.param -> 0.U
//      ))
//      
//      // Check final response
//      dut.clock.step(1)
//      dut.io.ATU2L1memRsp.valid.expect(true.B)
//      dut.io.ATU2L1memRsp.bits.data.expect(8.U)
//    }
//  }
  
//"AtomicUnit Logical Operations" in {
//  test(new AtomicUnit(params)).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
//    // Initialize signals
//    dut.io.isAtomic.poke(true.B)
//    dut.io.L12ATUmemReq.initSource().setSourceClock(dut.clock)
//    dut.io.L22ATUmemRsp.initSink().setSinkClock(dut.clock)
//    dut.io.ATU2L2memReq.initSink().setSinkClock(dut.clock)
//    dut.io.ATU2L1memRsp.initSource().setSourceClock(dut.clock)
//    
//    // Test AND operation
//    dut.io.L12ATUmemReq.enqueue(new TLBundleA_lite(params).Lit(
//      _.opcode -> TLAOp_Logic.U,
//      _.param -> TLAParam_LogicAnd.U,
//      _.address -> "h1000".U,
//      _.data -> "b1010".U,
//      _.mask -> "b1111".U,
//      _.source -> 1.U
//    ))
//    
//    // Wait for Get request
//    dut.clock.step(1)
//    dut.io.ATU2L2memReq.valid.expect(true.B)
//    dut.io.ATU2L2memReq.bits.opcode.expect(TLAOp_Get.U)
//    
//    // Send response with old value
//    dut.io.L22ATUmemRsp.enqueue(new TLBundleD_lite_plus_custom(params).Lit(
//      _.opcode -> 1.U,
//      _.data -> "b1100".U,
//      _.source -> "b1100001".U,
//      _.param -> 0.U
//    ))
//    
//    // Wait for Put request with result
//    dut.clock.step(1)
//    dut.io.ATU2L2memReq.valid.expect(true.B)
//    dut.io.ATU2L2memReq.bits.opcode.expect(TLAOp_PutPart.U)
//    dut.io.ATU2L2memReq.bits.data.expect("b1000".U) // 1010 & 1100 = 1000
//    
//    // Send final response
//    dut.io.L22ATUmemRsp.enqueue(new TLBundleD_lite_plus_custom(params).Lit(
//      _.opcode -> 1.U,
//      _.data -> 0.U,
//      _.source -> "b1100001".U,
//      _.param -> 0.U
//    ))
//    
//    // Check final response
//    dut.clock.step(1)
//    dut.io.ATU2L1memRsp.valid.expect(true.B)
//    dut.io.ATU2L1memRsp.bits.data.expect("b1000".U)
//  }
//}
  
  "AtomicUnit LR/SC Operations" in {
    test(new AtomicUnit(params)(param)).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      // Initialize signals
    dut.io.L12ATUmemReq.initSource().setSourceClock(dut.clock)
    //dut.io.L22ATUmemRsp.initSource().setSourceClock(dut.clock)
    //dut.io.ATU2L2memReq.initSink().setSinkClock(dut.clock)
    //dut.io.ATU2L1memRsp.initSink().setSinkClock(dut.clock)
    dut.io.ATU2L1memRsp.ready.poke(true.B)
      // Create two threads
      fork {
        // Test LR operation
        dut.io.L12ATUmemReq.enqueue(new TLBundleA_lite(params).Lit(
          _.opcode -> 4.U,
          _.param -> 1.U, // LR
          _.size -> 0.U,
          _.address -> "h1000".U,
          _.data -> 0.U,
          _.mask -> "b0000_0000_0000_1111".U,
          _.source -> 1.U
        ))
        
        dut.clock.step(5)
        
        // Send response
        dut.io.L22ATUmemRsp.enqueue(new TLBundleD_lite_plus_custom(params).Lit(
          _.opcode -> 1.U,
          _.data -> 4.U,
          _.size -> 0.U,
          _.address -> "h1000".U,
          _.source -> 1.U,
          _.param -> 1.U
        ))
       
        // Test SC operation
        dut.clock.step(2)
        dut.io.L12ATUmemReq.enqueue(new TLBundleA_lite(params).Lit(
          _.opcode -> 1.U,
          _.param -> 1.U, // SC
          _.size -> 0.U,
          _.address -> "h1000".U,
          _.data -> 43.U,
          _.mask -> "b0000_0000_0000_1111".U,
          _.source -> 1.U
        ))
        
        dut.clock.step(5)
        
        // Send response
        dut.io.L22ATUmemRsp.enqueue(new TLBundleD_lite_plus_custom(params).Lit(
          _.opcode -> 0.U,
          _.size -> 0.U,
          _.address -> "h1000".U,
          _.data -> 0.U,
          _.source -> "b100_0000_0000_0000_0001".U,
          _.param -> 0.U
        ))
        
        dut.clock.step(6)
      }.fork {
        // Toggle ready signal every 5 clock cycles
        for (i <- 0 until 20) {
          dut.io.ATU2L2memReq.ready.poke((i % 10 < 5).B)
          dut.clock.step(1)
        }
      }.join()
    }
  }
} 