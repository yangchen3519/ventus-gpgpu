package play.cache

import chisel3._
import chiseltest._
import org.scalatest.freespec.AnyFreeSpec
import top.parameters._
import L1Cache.DCache._
import L1Cache.{MSHRv2, MyConfig}
import chiseltest.{ChiselScalatestTester, WriteVcdAnnotation}
import org.scalatest.freespec.AnyFreeSpec

class cache_MSHR_test extends AnyFreeSpec with ChiselScalatestTester{
  "mshr_test" in {
    test(new MSHRv2(25,2,1,24,10,3,4,2,2)).withAnnotations(Seq(WriteVcdAnnotation)){ dut =>
      dut.clock.step(5)
      //probe in st0
      dut.io.probe.valid.poke(true.B)
      dut.io.probe.bits.blockAddr.poke(2.U)//set 0
      dut.clock.step(1)
      //missreq
      dut.io.probe.valid.poke(false.B)
      dut.io.missReq.valid.poke(true.B)
      dut.io.missReq.bits.blockAddr.poke(2.U)
      dut.io.missReq.bits.instrId.poke(0.U)
      dut.io.missReq.bits.targetInfo.poke(1.U)
      dut.io.missReq.bits.missUncached.poke(false.B)
    }
  }
}
