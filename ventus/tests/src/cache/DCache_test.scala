package cache

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.freespec.AnyFreeSpec
import L2cache.{TLBundleA_lite_custom, TLBundleD_lite_plus_custom, InclusiveCacheParameters_lite, TLBundleA_lite, TLBundleD_lite_plus, CacheParameters, InclusiveCacheMicroParameters}
import L1Cache.AtomicUnit.AtomicUnit
import L1Cache.DCache._
import L1Cache._
import L1Cache.{ MyConfig}
import top.parameters._
import config.config.Parameters
import mmu.SVParam

class DCacheWrapperTest extends AnyFreeSpec with ChiselScalatestTester {
  implicit val p: Parameters = (new MyConfig).toInstance
  
  "DCacheWrapper_test" in {
    test(new DCacheWrapper(
      params = InclusiveCacheParameters_lite(
        cache = CacheParameters(
          level = 2,
          ways = 8,
          sets = 64,
          l2cs = 1,
          blockBytes = 64,
          beatBytes = 8
        ),
        micro = InclusiveCacheMicroParameters(
          writeBytes = 8,
          memCycles = 40,
          portFactor = 4,
          num_warp = 32,
          num_sm = 4,
          num_sm_in_cluster = 2,
          num_cluster = 2,
          NMshrEntry = 16,
          NSets = 64,
          NInfWriteEntry = 8
        ),
        control = false,
        mmu = false
      )
    )).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      // 初始化信号
      dut.io.coreReq.initSource()
      dut.io.coreRsp.initSink()
      dut.io.coreRsp.ready.poke(true.B)

      // 测试写操作
      dut.io.coreReq.valid.poke(true.B)
      dut.io.coreReq.bits.instrId.poke(0.U)
      dut.io.coreReq.bits.opcode.poke(1.U)  // 写操作
      dut.io.coreReq.bits.param.poke(0.U)
      dut.io.coreReq.bits.tag.poke("h123".U)
      dut.io.coreReq.bits.setIdx.poke("h2".U)
      dut.io.coreReq.bits.perLaneAddr(0).activeMask.poke(true.B)
      dut.io.coreReq.bits.perLaneAddr(0).blockOffset.poke(0.U)
      dut.io.coreReq.bits.perLaneAddr(0).wordOffset1H.poke("b1111".U)
      dut.io.coreReq.bits.data(0).poke("h12345678".U)
      dut.clock.step(1)
      dut.io.coreReq.valid.poke(false.B)
      //dut.io.coreReq.enqueue((new DCacheCoreReq(None)(p)).Lit(
      //  _.instrId     -> 0.U,
      //  _.opcode      -> 1.U,  // 写操作
      //  _.param       -> 0.U,
      //  _.tag         -> "h123".U,
      //  _.setIdx                     ->"h45".U,
      //  _.perLaneAddr(0).activeMask   ->true.B,
      //  _.perLaneAddr(0).blockOffset  ->0.U,
      //  _.perLaneAddr(0).wordOffset1H ->"b1111".U,
      //  _.data(0)      -> "h12345678".U
      //))
      
      dut.clock.step(5)

      //// 测试读操作
      //val readReq = new DCacheCoreReq(None)(p)
      //readReq.instrId.poke(1.U)
      //readReq.opcode.poke(0.U)  // 读操作
      //readReq.param.poke(0.U)
      //readReq.tag.poke("h123".U)
      //readReq.setIdx.poke("h45".U)
      //readReq.perLaneAddr(0).activeMask.poke(true.B)
      //readReq.perLaneAddr(0).blockOffset.poke(0.U)
      //readReq.perLaneAddr(0).wordOffset1H.poke("b1111".U)
      //dut.io.coreReq.enqueue(readReq)
//
      //// 等待响应
      //dut.clock.step(10)
    }
  }
} 