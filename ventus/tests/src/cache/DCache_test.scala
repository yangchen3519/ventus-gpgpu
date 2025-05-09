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
    )(p)).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      // 初始化信号
      dut.io.coreReq.initSource().setSourceClock(dut.clock)
      dut.io.coreRsp.initSink().setSinkClock(dut.clock)
      dut.io.coreRsp.ready.poke(true.B)

      // 测试写操作
      val writeReq = new DCacheCoreReq(None)(p)
      writeReq.instrId := 0.U
      writeReq.opcode := 1.U  // 写操作
      writeReq.param := 0.U
      writeReq.tag := "h123".U
      writeReq.setIdx := "h45".U
      writeReq.perLaneAddr(0).activeMask := true.B
      writeReq.perLaneAddr(0).blockOffset := 0.U
      writeReq.perLaneAddr(0).wordOffset1H := "b1111".U
      writeReq.data(0) := "h12345678".U
      dut.io.coreReq.enqueue(writeReq)
      
      dut.clock.step(5)

      // 测试读操作
      val readReq = new DCacheCoreReq(None)(p)
      readReq.instrId := 1.U
      readReq.opcode := 0.U  // 读操作
      readReq.param := 0.U
      readReq.tag := "h123".U
      readReq.setIdx := "h45".U
      readReq.perLaneAddr(0).activeMask := true.B
      readReq.perLaneAddr(0).blockOffset := 0.U
      readReq.perLaneAddr(0).wordOffset1H := "b1111".U
      dut.io.coreReq.enqueue(readReq)

      // 等待响应
      dut.clock.step(10)
      

    }
  }
} 