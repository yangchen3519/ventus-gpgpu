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
package top

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFile
import chisel3.experimental.hierarchy.{Definition, Instance, instantiable, public, Instantiate}
import parameters._
import L1Cache.ICache._
import L1Cache._
import L1Cache.DCache._
import L1Cache.AtomicUnit._
import L1Cache.ShareMem._
import config.config._
import pipeline._
import L2cache._
//import CTA._
import cta.cta_scheduler_top
import axi._
import freechips.rocketchip.amba.axi4._
import mmu.{AsidLookup, L1TLB, L1TlbAutoReflect, L1ToL2TlbXBar, L2TLB, L2TlbReq, L2TlbRsp, L2TlbToL2CacheXBar}
import scala.Option.option2Iterable
import gvm._

class host2CTA_data extends Bundle{
  val host_wg_id                = UInt(CTA_SCHE_CONFIG.WG.WG_ID_WIDTH)
  val host_num_wf               = UInt(log2Ceil(CTA_SCHE_CONFIG.WG.NUM_WF_MAX+1).W)
  val host_wf_size              = UInt(log2Ceil(CTA_SCHE_CONFIG.GPU.NUM_THREAD+1).W)
  val host_start_pc             = UInt(CTA_SCHE_CONFIG.GPU.MEM_ADDR_WIDTH)
  val host_kernel_asid          = UInt(KNL_ASID_WIDTH.W)
  val host_kernel_size_x        = UInt(log2Ceil(CTA_SCHE_CONFIG.KERNEL.NUM_WG_MAX+1).W)
  val host_kernel_size_y        = UInt(log2Ceil(CTA_SCHE_CONFIG.KERNEL.NUM_WG_MAX+1).W)
  val host_kernel_size_z        = UInt(log2Ceil(CTA_SCHE_CONFIG.KERNEL.NUM_WG_MAX+1).W)
  val host_wgIdx_x              = UInt(log2Ceil(CTA_SCHE_CONFIG.KERNEL.NUM_WG_MAX).W)
  val host_wgIdx_y              = UInt(log2Ceil(CTA_SCHE_CONFIG.KERNEL.NUM_WG_MAX).W)
  val host_wgIdx_z              = UInt(log2Ceil(CTA_SCHE_CONFIG.KERNEL.NUM_WG_MAX).W)
  val host_num_thread_per_wg_x  = UInt(log2Ceil(CTA_SCHE_CONFIG.WG.NUM_THREAD_PER_WG_MAX+1).W)
  val host_num_thread_per_wg_y  = UInt(log2Ceil(CTA_SCHE_CONFIG.WG.NUM_THREAD_PER_WG_MAX+1).W)
  val host_num_thread_per_wg_z  = UInt(log2Ceil(CTA_SCHE_CONFIG.WG.NUM_THREAD_PER_WG_MAX+1).W)
  val host_threadIdx_global_offset_x = UInt(log2Ceil(CTA_SCHE_CONFIG.KERNEL.NUM_WG_MAX).W)
  val host_threadIdx_global_offset_y = UInt(log2Ceil(CTA_SCHE_CONFIG.KERNEL.NUM_WG_MAX).W)
  val host_threadIdx_global_offset_z = UInt(log2Ceil(CTA_SCHE_CONFIG.KERNEL.NUM_WG_MAX).W)
  val host_csr_knl          = UInt(CTA_SCHE_CONFIG.GPU.MEM_ADDR_WIDTH)
  val host_vgpr_size_total  = UInt(log2Ceil(CTA_SCHE_CONFIG.WG.NUM_VGPR_MAX+1).W)
  val host_sgpr_size_total  = UInt(log2Ceil(CTA_SCHE_CONFIG.WG.NUM_SGPR_MAX+1).W)
  val host_lds_size_total   = UInt(log2Ceil(CTA_SCHE_CONFIG.WG.NUM_LDS_MAX+1).W)
  val host_vgpr_size_per_wf = UInt(log2Ceil(CTA_SCHE_CONFIG.WG.NUM_VGPR_MAX+1).W)
  val host_sgpr_size_per_wf = UInt(log2Ceil(CTA_SCHE_CONFIG.WG.NUM_SGPR_MAX+1).W)
  val host_pds_size_per_wf  = UInt(log2Ceil(CTA_SCHE_CONFIG.WG.NUM_PDS_MAX+1).W)
  val host_pds_baseaddr     = UInt(CTA_SCHE_CONFIG.GPU.MEM_ADDR_WIDTH)
  val host_gds_baseaddr     = UInt(CTA_SCHE_CONFIG.GPU.MEM_ADDR_WIDTH)
  val host_gds_size_total   = UInt(CTA_SCHE_CONFIG.GPU.MEM_ADDR_WIDTH)  // Useless ?
  val host_kernel_dim       = UInt(2.W) // how many dimension does this kernel have
  val host_asid             = UInt(CTA_SCHE_CONFIG.GPU.ASID_WIDTH)
}
class CTA2host_data extends Bundle{
  val inflight_wg_buffer_host_wf_done_wg_id = (UInt(WG_ID_WIDTH.W))
}
class CTAinterface extends Module{
  val io=IO(new Bundle{
    val host2CTA = Flipped(DecoupledIO(new host2CTA_data))
    val CTA2host = DecoupledIO(new CTA2host_data)
    val CTA2warp = Vec(NUMBER_CU,DecoupledIO(new CTAreqData))
    val warp2CTA = Vec(NUMBER_CU,Flipped(DecoupledIO(new CTArspData)))
  })
  val cta_sche = Module(new cta_scheduler_top)
  cta_sche.io.host_wg_new.valid <> io.host2CTA.valid
  cta_sche.io.host_wg_new.ready <> io.host2CTA.ready
  cta_sche.io.host_wg_new.bits.wg_id              := io.host2CTA.bits.host_wg_id
  cta_sche.io.host_wg_new.bits.num_wf             := io.host2CTA.bits.host_num_wf
  cta_sche.io.host_wg_new.bits.num_thread_per_wf  := io.host2CTA.bits.host_wf_size
  cta_sche.io.host_wg_new.bits.start_pc           := io.host2CTA.bits.host_start_pc
  cta_sche.io.host_wg_new.bits.num_wg_x           := io.host2CTA.bits.host_kernel_size_x
  cta_sche.io.host_wg_new.bits.num_wg_y           := io.host2CTA.bits.host_kernel_size_y
  cta_sche.io.host_wg_new.bits.num_wg_z           := io.host2CTA.bits.host_kernel_size_z
  cta_sche.io.host_wg_new.bits.wgIdx_x            := io.host2CTA.bits.host_wgIdx_x
  cta_sche.io.host_wg_new.bits.wgIdx_y            := io.host2CTA.bits.host_wgIdx_y
  cta_sche.io.host_wg_new.bits.wgIdx_z            := io.host2CTA.bits.host_wgIdx_z
  cta_sche.io.host_wg_new.bits.num_thread_per_wg_x:= io.host2CTA.bits.host_num_thread_per_wg_x
  cta_sche.io.host_wg_new.bits.num_thread_per_wg_y:= io.host2CTA.bits.host_num_thread_per_wg_y
  cta_sche.io.host_wg_new.bits.num_thread_per_wg_z:= io.host2CTA.bits.host_num_thread_per_wg_z
  cta_sche.io.host_wg_new.bits.threadIdx_in_grid_offset_x := io.host2CTA.bits.host_threadIdx_global_offset_x
  cta_sche.io.host_wg_new.bits.threadIdx_in_grid_offset_y := io.host2CTA.bits.host_threadIdx_global_offset_y
  cta_sche.io.host_wg_new.bits.threadIdx_in_grid_offset_z := io.host2CTA.bits.host_threadIdx_global_offset_z
  cta_sche.io.host_wg_new.bits.pds_base           := io.host2CTA.bits.host_pds_baseaddr
  cta_sche.io.host_wg_new.bits.num_pds_per_wf     := io.host2CTA.bits.host_pds_size_per_wf
  cta_sche.io.host_wg_new.bits.csr_kernel         := io.host2CTA.bits.host_csr_knl
  cta_sche.io.host_wg_new.bits.num_lds            := io.host2CTA.bits.host_lds_size_total
  cta_sche.io.host_wg_new.bits.num_sgpr           := io.host2CTA.bits.host_sgpr_size_total
  cta_sche.io.host_wg_new.bits.num_vgpr           := io.host2CTA.bits.host_vgpr_size_total
  cta_sche.io.host_wg_new.bits.num_sgpr_per_wf    := io.host2CTA.bits.host_sgpr_size_per_wf
  cta_sche.io.host_wg_new.bits.num_vgpr_per_wf    := io.host2CTA.bits.host_vgpr_size_per_wf
  cta_sche.io.host_wg_new.bits.gds_base           := io.host2CTA.bits.host_gds_baseaddr
  if (MMU_ENABLED) {
    cta_sche.io.host_wg_new.bits.asid_kernel.get  := io.host2CTA.bits.host_asid
  }

  io.CTA2host.bits.inflight_wg_buffer_host_wf_done_wg_id := cta_sche.io.host_wg_done.bits.wg_id
  io.CTA2host.valid := cta_sche.io.host_wg_done.valid
  cta_sche.io.host_wg_done.ready := io.CTA2host.ready

  for (i <- 0 until NUMBER_CU){
    io.CTA2warp(i).valid := cta_sche.io.cu_wf_new(i).valid
    io.CTA2warp(i).bits.dispatch2cu_wg_wf_count := cta_sche.io.cu_wf_new(i).bits.num_wf
    io.CTA2warp(i).bits.dispatch2cu_wf_size_dispatch   := cta_sche.io.cu_wf_new(i).bits.num_thread_per_wf
    io.CTA2warp(i).bits.dispatch2cu_lds_base_dispatch  := cta_sche.io.cu_wf_new(i).bits.lds_base
    io.CTA2warp(i).bits.dispatch2cu_sgpr_base_dispatch := cta_sche.io.cu_wf_new(i).bits.sgpr_base
    io.CTA2warp(i).bits.dispatch2cu_vgpr_base_dispatch := cta_sche.io.cu_wf_new(i).bits.vgpr_base
    io.CTA2warp(i).bits.dispatch2cu_wf_tag_dispatch    := cta_sche.io.cu_wf_new(i).bits.wf_tag
    io.CTA2warp(i).bits.dispatch2cu_start_pc_dispatch  := cta_sche.io.cu_wf_new(i).bits.start_pc
    io.CTA2warp(i).bits.dispatch2cu_gds_base_dispatch  := cta_sche.io.cu_wf_new(i).bits.gds_base
    io.CTA2warp(i).bits.dispatch2cu_pds_base_dispatch  := cta_sche.io.cu_wf_new(i).bits.pds_base
    io.CTA2warp(i).bits.dispatch2cu_csr_knl_dispatch   := cta_sche.io.cu_wf_new(i).bits.csr_kernel
    io.CTA2warp(i).bits.dispatch2cu_wgid_x_dispatch    := cta_sche.io.cu_wf_new(i).bits.wgIdx_x
    io.CTA2warp(i).bits.dispatch2cu_wgid_y_dispatch    := cta_sche.io.cu_wf_new(i).bits.wgIdx_y
    io.CTA2warp(i).bits.dispatch2cu_wgid_z_dispatch    := cta_sche.io.cu_wf_new(i).bits.wgIdx_z
    io.CTA2warp(i).bits.dispatch2cu_threadIdx_local_x  := cta_sche.io.cu_wf_new(i).bits.threadIdx_in_wg_x
    io.CTA2warp(i).bits.dispatch2cu_threadIdx_local_y  := cta_sche.io.cu_wf_new(i).bits.threadIdx_in_wg_y
    io.CTA2warp(i).bits.dispatch2cu_threadIdx_local_z  := cta_sche.io.cu_wf_new(i).bits.threadIdx_in_wg_z
    io.CTA2warp(i).bits.dispatch2cu_threadIdx_global_x := cta_sche.io.cu_wf_new(i).bits.threadIdx_in_grid_x
    io.CTA2warp(i).bits.dispatch2cu_threadIdx_global_y := cta_sche.io.cu_wf_new(i).bits.threadIdx_in_grid_y
    io.CTA2warp(i).bits.dispatch2cu_threadIdx_global_z := cta_sche.io.cu_wf_new(i).bits.threadIdx_in_grid_z
    io.CTA2warp(i).bits.dispatch2cu_threadIdx_global_linear := cta_sche.io.cu_wf_new(i).bits.threadIdx_in_grid
    io.CTA2warp(i).bits.dispatch2cu_wg_id := cta_sche.io.cu_wf_new(i).bits.wg_id
    (io.CTA2warp(i).bits.dispatch2cu_knl_asid, cta_sche.io.cu_wf_new(i).bits.asid_kernel) match {
      case (Some(dispatch), Some(asid)) =>
        dispatch := asid
      case _ => // None
    }
    cta_sche.io.cu_wf_new(i).ready := io.CTA2warp(i).ready

    cta_sche.io.cu_wf_done(i).bits.wf_tag := io.warp2CTA(i).bits.cu2dispatch_wf_tag_done
    cta_sche.io.cu_wf_done(i).valid := io.warp2CTA(i).valid
    io.warp2CTA(i).ready := cta_sche.io.cu_wf_done(i).ready
  }
}
class GPGPU_axi_top extends Module{
  val l2cache_axi_params=AXI4BundleParameters(32,64,l2cache_params.source_bits)
  val l2_axi_params=InclusiveCacheParameters_lite_withAXI(l2cache_params,l2cache_axi_params)

  val io=IO(new Bundle{
    val s=Flipped(new AXI4Lite(32, 32))
    val m=(new AXI4Bundle(l2cache_axi_params))
  })
  val l1param = (new MyConfig).toInstance

  val gpgpu_top=Module(new GPGPU_top()(l1param,true))
  val axi_lite_adapter=Module(new AXI4Lite2CTA(32,32))
  val axi_adapter=Module(new AXI4Adapter(l2_axi_params))
  axi_lite_adapter.io.ctl<>io.s
  axi_adapter.io.AXI_master_bundle<>io.m
  gpgpu_top.io.out_a(0)<>axi_adapter.io.l2cache_outa
  gpgpu_top.io.out_d(0)<>axi_adapter.io.l2cache_outd
  gpgpu_top.io.host_req<>axi_lite_adapter.io.data
  gpgpu_top.io.host_rsp<>axi_lite_adapter.io.rsp
  gpgpu_top.io.cycle_cnt:=0.U
  gpgpu_top.io.perfDump:=false.B
  gpgpu_top.io.perfDumpSummary:=false.B
  gpgpu_top.io.icache_invalidate:=false.B
}
class GPGPU_axi_adapter_top extends Module{
  val l2cache_axi_params=AXI4BundleParameters(32,64,log2Up(l2cache_micro.num_sm)+log2Up(l2cache_micro.num_warp)+1)
  val l2_axi_params=InclusiveCacheParameters_lite_withAXI(l2cache_params,l2cache_axi_params)
  val io=IO(new Bundle{
    val s=Flipped(new AXI4Lite(32, 32))
    val m=(new AXI4Bundle(l2cache_axi_params))
  })
  val gpgpu_axi_top=Module(new GPGPU_axi_top)
  io.s<>gpgpu_axi_top.io.s
  io.m<>gpgpu_axi_top.io.m
}

class GPGPU_top(implicit p: Parameters, FakeCache: Boolean = false, SV: Option[mmu.SVParam] = None)
  extends RVGModule{
  override val desiredName = s"GPU"
  val io = IO(new Bundle{
    val host_req=Flipped(DecoupledIO(new host2CTA_data))
    val host_rsp=DecoupledIO(new CTA2host_data)
    val out_a =Vec(NL2Cache,Decoupled(new TLBundleA_lite(l2cache_params)))
    val out_d=Flipped(Vec(NL2Cache,Decoupled(new TLBundleD_lite(l2cache_params))))
    val inst_cnt = if(INST_CNT) Some(Output(Vec(NSms, UInt(32.W)))) else None
    val inst_cnt2 = if(INST_CNT_2) Some(Output(Vec(NSms, Vec(2, UInt(32.W))))) else None
    val cycle_cnt = Input(UInt(20.W))
    val perfDump = Input(Bool())
    val perfDumpSummary = Input(Bool())
    val asid_fill = if(MMU_ENABLED) Some(Input(Flipped(ValidIO(new mmu.AsidLookupEntry(SV.get))))) else None
    val icache_invalidate = Input(Bool())
  })
  val cta = Module(new CTAinterface)
  val sm_wrapper_inst = Seq.tabulate(NSms) { i => Instantiate(new SM_wrapper(FakeCache, SV)) }
  sm_wrapper_inst.zipWithIndex.foreach{ case (inst, i) => inst.sm_id := i.U }
  val sm_wrapper = VecInit.tabulate(NSms)(i => sm_wrapper_inst(i).io)
  val l2cache=VecInit(Seq.fill(NL2Cache)( Module(new Scheduler(l2cache_params)).io))
  val sm2clusterArb = VecInit(Seq.fill(NCluster)(Module(new SM2clusterArbiter(l2cache_params_l)).io))
  val l2distribute = VecInit(Seq.fill(NCluster)(Module(new l2Distribute(l2cache_params_l)).io))
  val cluster2l2Arb = VecInit(Seq.fill(NL2Cache)(Module(new cluster2L2Arbiter(l2cache_params_l,l2cache_params)).io))
  val atomicunit = VecInit(Seq.fill(NL2Cache)( Module(new AtomicUnit(l2cache_params)).io))
 // val sm2L2Arb = Module(new SM2L2Arbiter(l2cache_params))

  for (i<- 0 until NCluster) {
    for(j<- 0 until NSmInCluster) {
      cta.io.CTA2warp(i * NSmInCluster + j) <> sm_wrapper(i * NSmInCluster + j).CTAreq
      cta.io.warp2CTA(i * NSmInCluster + j) <> sm_wrapper(i * NSmInCluster + j).CTArsp
     //sm2clusterArb(i).memReqVecIn(j) <> sm_wrapper(i * NSmInCluster + j).memReq
      //sm_wrapper(i * NSmInCluster + j).memRsp <> sm2clusterArb(i).memRspVecOut(j)
      sm2clusterArb(i).memReqVecIn(j).bits := sm_wrapper(i * NSmInCluster + j).memReq.bits
      sm2clusterArb(i).memReqVecIn(j).valid := sm_wrapper(i * NSmInCluster + j).memReq.valid
      sm_wrapper(i * NSmInCluster + j).memReq.ready :=sm2clusterArb(i).memReqVecIn(j).ready
      sm_wrapper(i * NSmInCluster + j).memRsp.bits := sm2clusterArb(i).memRspVecOut(j).bits
      sm_wrapper(i * NSmInCluster + j).memRsp.valid := sm2clusterArb(i).memRspVecOut(j).valid
       sm2clusterArb(i).memRspVecOut(j).ready := sm_wrapper(i * NSmInCluster + j).memRsp.ready
      sm_wrapper(i * NSmInCluster + j).icache_invalidate := io.icache_invalidate
    }
    l2distribute(i).memReqIn.valid := sm2clusterArb(i).memReqOut.valid
    l2distribute(i).memReqIn.bits := sm2clusterArb(i).memReqOut.bits
    sm2clusterArb(i).memReqOut.ready := l2distribute(i).memReqIn.ready
    //l2distribute(i).memReqIn <> sm2clusterArb(i).memReqOut
    sm2clusterArb(i).memRspIn.valid := l2distribute(i).memRspOut.valid
    sm2clusterArb(i).memRspIn.bits := l2distribute(i).memRspOut.bits
    l2distribute(i).memRspOut.ready := sm2clusterArb(i).memRspIn.ready
    //sm2clusterArb(i).memRspIn <> l2distribute(i).memRspOut
   // cluster2l2Arb.memReqVecIn(i) <> sm2clusterArb(i).memReqOut
   // sm2clusterArb(i).memRspIn <> cluster2l2Arb.memRspVecOut(i)
  //  sm_wrapper(i).memRsp <> sm2L2Arb.io.memRspVecOut(i)
  //  sm2L2Arb.io.memReqVecIn(i) <> sm_wrapper(i).memReq
  }

  MMU_ENABLED match {
    case false => {
      for(i <- 0 until NL2Cache){
        atomicunit(i).L12ATUmemReq.bits := cluster2l2Arb(i).memReqOut.bits
        atomicunit(i).L12ATUmemReq.valid := cluster2l2Arb(i).memReqOut.valid
        cluster2l2Arb(i).memReqOut.ready := atomicunit(i).L12ATUmemReq.ready
        l2cache(i).in_a <> atomicunit(i).ATU2L2memReq
        cluster2l2Arb(i).memRspIn <> atomicunit(i).ATU2L1memRsp
        atomicunit(i).L22ATUmemRsp.bits := l2cache(i).in_d.bits
        atomicunit(i).L22ATUmemRsp.valid := l2cache(i).in_d.valid
        l2cache(i).in_d.ready := atomicunit(i).L22ATUmemRsp.ready

        for(j <- 0 until NCluster){
          cluster2l2Arb(i).memReqVecIn(j) <> l2distribute(j).memReqVecOut(i)
          l2distribute(j).memRspVecIn(i) <> cluster2l2Arb(i).memRspVecOut(j)
        }

        io.out_a(i) <> l2cache(i).out_a
        l2cache(i).out_d <> io.out_d(i)
      }
     /* sm_wrapper.foreach{ sm =>
        sm.l2tlbReq.get.foreach{ l2 =>
          l2.ready := false.B
        }
        sm.l2tlbRsp.get.foreach{ l2 =>
          l2.valid := false.B
          l2.bits := 0.U.asTypeOf(l2.bits)
        }
      }*/
    }
    case true => {
      val l2tlb = Module(new L2TLB(SV.get, L2C = Some(l2cache_params))(Some(this.asInstanceOf[HasRVGParameters])))
      val asid_lookup = Module(new AsidLookup(SV.get, l2tlb.nBanks, 8)) // TODO: parameter of max ASID entries
      asid_lookup.io.lookup_req := l2tlb.io.asid_req
      l2tlb.io.ptbr_rsp := asid_lookup.io.lookup_rsp
      io.asid_fill.foreach{ in =>
        asid_lookup.io.fill_in := in
      }
    //todo  连接 L2 TLB 的 invalidate 信号，目前采用每个SM执行完block的flush信号的或io.host_rsp.valid
    l2tlb.io.invalidate.valid := io.host_rsp.valid
    // val asid_invalid = (asid_lookup.io.fill_in.bits.asid.asUInt - 1.U).asTypeOf(l2tlb.io.invalidate.bits)
    //todo asid的值目前采用asid_lookup的填充值，这个值是会保持的，是否应该有软件给出
    val asid_invalid = asid_lookup.io.fill_in.bits.asid.asTypeOf(l2tlb.io.invalidate.bits)
    l2tlb.io.invalidate.bits := asid_invalid  // ASID 值，要清空指定地址空间的条目

      // sm <-> l2tlb
      val sm_tlb_xbar = Module(new L1ToL2TlbXBar(SV.get, NSms * NCacheInSM)(Some(this)))
      l2tlb.io.in <> sm_tlb_xbar.io.req_l2
      sm_tlb_xbar.io.rsp_l2 <> l2tlb.io.out

      def genXbarReq(in: DecoupledIO[Bundle{val asid: UInt; val vpn: UInt}], index: UInt): DecoupledIO[L2TlbReq] = {
        val out = Wire(DecoupledIO(new L2TlbReq(SV.get)(Some(this.asInstanceOf[HasRVGParameters]))))
        out.bits.asid := in.bits.asid
        out.bits.vpn := in.bits.vpn
        out.bits.id := index
        out.valid := in.valid
        in.ready := out.ready
        out
      }
      def genXbarRsp(in: DecoupledIO[L2TlbRsp]): DecoupledIO[Bundle{val ppn: UInt; val flags: UInt}] = {
        val out = Wire(DecoupledIO(new Bundle{
          val ppn = UInt(SV.get.ppnLen.W)
          val flags = UInt(8.W)
        }))
        out.bits.ppn := in.bits.ppn
        out.bits.flags := in.bits.flag
        out.valid := in.valid
        in.ready := out.ready
        out
      }
      for(i <- 0 until NSms){
        sm_tlb_xbar.io.req_l1(i * NCacheInSM) <> genXbarReq(sm_wrapper(i).l2tlbReq.get(0), (i * NCacheInSM).U)
        sm_wrapper(i).l2tlbRsp.get(0) <> genXbarRsp(sm_tlb_xbar.io.rsp_l1(i * NCacheInSM))
        sm_tlb_xbar.io.req_l1(i * NCacheInSM + 1) <> genXbarReq(sm_wrapper(i).l2tlbReq.get(1), (i * NCacheInSM + 1).U)
        sm_wrapper(i).l2tlbRsp.get(1) <> genXbarRsp(sm_tlb_xbar.io.rsp_l1(i * NCacheInSM + 1))
      }

      // l2tlb <-> l2c
      val tlb_req_arb = Seq.fill(NL2Cache)(Module(new Arbiter(new TLBundleA_lite(l2cache_params), 2)))

      val tlb_l2c_xbar = Module(new L2TlbToL2CacheXBar(SV.get, NL2Cache, l2cache_params)(this.asInstanceOf[HasRVGParameters]))

      tlb_l2c_xbar.io.req_tlb <> l2tlb.io.mem_req
      (0 until l2tlb.nBanks).foreach{ i =>
        val tl_cast: TLBundleA_lite = l2tlb.io.mem_req(i).bits.asTypeOf(new TLBundleA_lite(l2cache_params))
        tlb_l2c_xbar.io.req_tlb(i).bits.source := Cat(i.U(log2Ceil(l2tlb.nBanks).W), tl_cast.source(log2Ceil(NSms), 0))
      }
      l2tlb.io.mem_rsp <> tlb_l2c_xbar.io.rsp_tlb

      for(i <- 0 until NL2Cache) {
        tlb_req_arb(i).io.in(0) <> tlb_l2c_xbar.io.req_cache(i)
        tlb_req_arb(i).io.in(1) <> cluster2l2Arb(i).memReqOut
        tlb_req_arb(i).io.in(1).bits.source := Cat(cluster2l2Arb(i).memReqOut.bits.source, 0.U(1.W))
        l2cache(i).in_a <> tlb_req_arb(i).io.out

        cluster2l2Arb(i).memRspIn.valid := l2cache(i).in_d.valid & !l2cache(i).in_d.bits.source(0)
        cluster2l2Arb(i).memRspIn.bits := l2cache(i).in_d.bits
        cluster2l2Arb(i).memRspIn.bits.source := l2cache(i).in_d.bits.source >> 1

        tlb_l2c_xbar.io.rsp_cache(i).valid := l2cache(i).in_d.valid & l2cache(i).in_d.bits.source(0)
        tlb_l2c_xbar.io.rsp_cache(i).bits := l2cache(i).in_d.bits
        // source LSB is already appended by 1.U(1.W) in tlb crossbar system

        l2cache(i).in_d.ready := Mux(l2cache(i).in_d.bits.source(0), tlb_l2c_xbar.io.rsp_cache(i).ready, cluster2l2Arb(i).memRspIn.ready)

        for(j <- 0 until NCluster){
          cluster2l2Arb(i).memReqVecIn(j) <> l2distribute(j).memReqVecOut(i)
          l2distribute(j).memRspVecIn(i) <> cluster2l2Arb(i).memRspVecOut(j)
        }

        io.out_a(i) <> l2cache(i).out_a
        l2cache(i).out_d <> io.out_d(i)
      }
    }
  }

  io.host_rsp<>cta.io.CTA2host
  io.host_req<>cta.io.host2CTA
  io.inst_cnt.foreach(_.zipWithIndex.foreach{case (l,r) => l := sm_wrapper(r).inst_cnt.getOrElse(0.U)})
  io.inst_cnt2.foreach(_.zipWithIndex.foreach{case (l,r) => l := sm_wrapper(r).inst_cnt2.getOrElse(0.U)})

  def sumPerfCounter(select: DCachePerfCounters => UInt): UInt = {
    sm_wrapper.map(sm => select(sm.dcache_perf)).reduce(_ + _)
  }
  def sumPipelinePerfCounter(select: PipelinePerfCounters => UInt): UInt = {
    if (PMU_PIPELINE) sm_wrapper.map(sm => select(sm.pipeline_perf.get)).reduce(_ + _) else 0.U(64.W)
  }
  def sumInstClassPerfCounter(select: InstClassPerfCounters => UInt): UInt = {
    if (PMU_INST_CLASS) sm_wrapper.map(sm => select(sm.inst_class_perf.get)).reduce(_ + _) else 0.U(64.W)
  }
  def sumLsuPerfCounter(select: LsuPerfCounters => UInt): UInt = {
    sm_wrapper.map(sm => select(sm.lsu_perf)).reduce(_ + _)
  }
  def percentOf(numer: UInt, denom: UInt): UInt = {
    Mux(denom === 0.U, 0.U(32.W), ((numer * 100.U) / denom)(31, 0))
  }

  val l1dTotalReq = sumPerfCounter(_.totalReq)
  val l1dReadReq = sumPerfCounter(_.readReq)
  val l1dWriteReq = sumPerfCounter(_.writeReq)
  val l1dReadMiss = sumPerfCounter(_.readMiss)
  val l1dWriteMiss = sumPerfCounter(_.writeMiss)
  val l1dReadAllocMshr = sumPerfCounter(_.readAllocMshr)
  val l1dReadHitMshr = sumPerfCounter(_.readHitMshr)
  val l1dReadMshrEntryFull = sumPerfCounter(_.readMshrEntryFull)
  val l1dReadMshrSubentryFull = sumPerfCounter(_.readMshrSubentryFull)
  val l1dWriteAllocMshr = sumPerfCounter(_.writeAllocMshr)
  val l1dWriteHitWshr = sumPerfCounter(_.writeHitWshr)
  val l1dReplacement = sumPerfCounter(_.replacements)
  val l1dDirtyWriteback = sumPerfCounter(_.dirtyWritebacks)
  val l1dMshrFullCycles = sumPerfCounter(_.mshrFullCycles)
  val l1dRtabReplays = sumPerfCounter(_.rtabReplays)
  val l1dReadBytes = sumPerfCounter(_.readBytes)
  val l1dWriteBytes = sumPerfCounter(_.writeBytes)
  val l1dReadTransactions = sumPerfCounter(_.readTransactions)
  val l1dWriteTransactions = sumPerfCounter(_.writeTransactions)
  val l1dMshrBusyCycles = sumPerfCounter(_.mshrBusyCycles)
  val l1dBackpressureCycles = sumPerfCounter(_.backpressureCycles)
  val l1dBpBlockedCoreReq = sumPerfCounter(_.bpBlockedCoreReq)
  val l1dBpRtabFull = sumPerfCounter(_.bpRtabFull)
  val l1dBpRtabAlmostFull = sumPerfCounter(_.bpRtabAlmostFull)
  val l1dBpReplay = sumPerfCounter(_.bpReplay)
  val l1dBpSt0TagProbeBusy = sumPerfCounter(_.bpSt0TagProbeBusy)
  val l1dBpSt0RefillHazard = sumPerfCounter(_.bpSt0RefillHazard)
  val l1dBpSt0PendingMissHazard = sumPerfCounter(_.bpSt0PendingMissHazard)
  val l1dBpSt0PipeBusy = sumPerfCounter(_.bpSt0PipeBusy)
  val l1dBpSt1HitBusy = sumPerfCounter(_.bpSt1HitBusy)
  val l1dBpSt1MissMshrBusy = sumPerfCounter(_.bpSt1MissMshrBusy)
  val l1dBpSt1MissMemBusy = sumPerfCounter(_.bpSt1MissMemBusy)
  val l1dBpSt1MissRspBusy = sumPerfCounter(_.bpSt1MissRspBusy)
  val l1dBpSt1ReleaseHazard = sumPerfCounter(_.bpSt1ReleaseHazard)
  val l1dBpOther = sumPerfCounter(_.bpOther)
  val l1dTotalMiss = l1dReadMiss + l1dWriteMiss
  val l1dTotalHit = l1dTotalReq - l1dTotalMiss
  val l1dTotalBytes = l1dReadBytes + l1dWriteBytes

  val pmuActiveCycles = sumPipelinePerfCounter(_.activeCycles)
  val pmuTotalScalarIssued = sumPipelinePerfCounter(_.totalScalarIssued)
  val pmuTotalVectorIssued = sumPipelinePerfCounter(_.totalVectorIssued)
  val pmuThreadIssuedSlots = sumPipelinePerfCounter(_.threadIssuedSlots)
  val pmuExecHazardX = sumPipelinePerfCounter(_.execStructuralHazardCyclesX)
  val pmuExecHazardV = sumPipelinePerfCounter(_.execStructuralHazardCyclesV)
  val pmuDataDepStall = sumPipelinePerfCounter(_.dataDepStallCycles)
  val pmuBarrierStall = sumPipelinePerfCounter(_.barrierStallCycles)
  val pmuCtrlFlushCnt = sumPipelinePerfCounter(_.controlHazardFlushCount)
  val pmuFrontendStall = sumPipelinePerfCounter(_.frontendStallCycles)
  val pmuLsuBackpressure = sumPipelinePerfCounter(_.lsuBackpressureCycles)
  val pmuIbufferFullCycles = sumPipelinePerfCounter(_.ibufferFullCycles)

  val pmuComputeIssued = sumInstClassPerfCounter(_.computeIssued)
  val pmuMemIssued = sumInstClassPerfCounter(_.memIssued)
  val pmuCtrlIssued = sumInstClassPerfCounter(_.ctrlIssued)
  val pmuCtrlBranchIssued = sumInstClassPerfCounter(_.ctrlBranchIssued)
  val pmuCtrlBarrierIssued = sumInstClassPerfCounter(_.ctrlBarrierIssued)
  val pmuCtrlCsrIssued = sumInstClassPerfCounter(_.ctrlCsrIssued)
  val pmuCtrlSimtStackIssued = sumInstClassPerfCounter(_.ctrlSimtStackIssued)
  val pmuCtrlFenceIssued = sumInstClassPerfCounter(_.ctrlFenceIssued)
  val pmuMemLoadIssued = sumInstClassPerfCounter(_.memLoadIssued)
  val pmuMemStoreIssued = sumInstClassPerfCounter(_.memStoreIssued)
  val pmuMemAtomicIssued = sumInstClassPerfCounter(_.memAtomicIssued)
  val pmuComputeSaluIssued = sumInstClassPerfCounter(_.computeSaluIssued)
  val pmuComputeValuIssued = sumInstClassPerfCounter(_.computeValuIssued)
  val pmuComputeFpuIssued = sumInstClassPerfCounter(_.computeFpuIssued)
  val pmuComputeMulIssued = sumInstClassPerfCounter(_.computeMulIssued)
  val pmuComputeSfuIssued = sumInstClassPerfCounter(_.computeSfuIssued)
  val pmuComputeTensorCoreIssued = sumInstClassPerfCounter(_.computeTensorCoreIssued)
  val pmuComputeOps = sumInstClassPerfCounter(_.computeOps)
  val pmuFpuOps = sumInstClassPerfCounter(_.fpuOps)
  val pmuValuOps = sumInstClassPerfCounter(_.valuOps)
  val pmuSaluOps = sumInstClassPerfCounter(_.saluOps)
  val pmuMulOps = sumInstClassPerfCounter(_.mulOps)
  val pmuSfuOps = sumInstClassPerfCounter(_.sfuOps)
  val pmuTotalIssued = pmuTotalScalarIssued + pmuTotalVectorIssued
  val pmuThreadUtilPct =
    Mux(pmuActiveCycles === 0.U, 0.U(32.W), ((pmuThreadIssuedSlots * 100.U) / (pmuActiveCycles * num_thread.U))(31, 0))
  val lsuCoalescableInsts = sumLsuPerfCounter(_.globalMemCoalescableInsts)
  val lsuAtomicInsts = sumLsuPerfCounter(_.globalMemAtomicInsts)
  val lsuActiveThreads = sumLsuPerfCounter(_.globalMemActiveThreads)
  val lsuSegments = sumLsuPerfCounter(_.globalMemSegments)
  val lsuSegmentThreads = sumLsuPerfCounter(_.globalMemSegmentThreads)

  val perfWindowStarted = RegInit(false.B)
  val perfWindowPrinted = RegInit(false.B)
  val programId = RegInit(0.U(32.W))
  val totalProgramWindows = RegInit(0.U(32.W))
  val totalActiveCycles = RegInit(0.U(64.W))
  val totalScalarIssued = RegInit(0.U(64.W))
  val totalVectorIssued = RegInit(0.U(64.W))
  val totalThreadIssuedSlots = RegInit(0.U(64.W))
  val totalExecHazardX = RegInit(0.U(64.W))
  val totalExecHazardV = RegInit(0.U(64.W))
  val totalDataDepStall = RegInit(0.U(64.W))
  val totalBarrierStall = RegInit(0.U(64.W))
  val totalCtrlFlushCnt = RegInit(0.U(64.W))
  val totalFrontendStall = RegInit(0.U(64.W))
  val totalLsuBackpressure = RegInit(0.U(64.W))
  val totalIbufferFullCycles = RegInit(0.U(64.W))
  val totalComputeIssued = RegInit(0.U(64.W))
  val totalMemIssued = RegInit(0.U(64.W))
  val totalCtrlIssued = RegInit(0.U(64.W))
  val totalCtrlBranchIssued = RegInit(0.U(64.W))
  val totalCtrlBarrierIssued = RegInit(0.U(64.W))
  val totalCtrlCsrIssued = RegInit(0.U(64.W))
  val totalCtrlSimtStackIssued = RegInit(0.U(64.W))
  val totalCtrlFenceIssued = RegInit(0.U(64.W))
  val totalMemLoadIssued = RegInit(0.U(64.W))
  val totalMemStoreIssued = RegInit(0.U(64.W))
  val totalMemAtomicIssued = RegInit(0.U(64.W))
  val totalComputeSaluIssued = RegInit(0.U(64.W))
  val totalComputeValuIssued = RegInit(0.U(64.W))
  val totalComputeFpuIssued = RegInit(0.U(64.W))
  val totalComputeMulIssued = RegInit(0.U(64.W))
  val totalComputeSfuIssued = RegInit(0.U(64.W))
  val totalComputeTensorCoreIssued = RegInit(0.U(64.W))
  val totalComputeOps = RegInit(0.U(64.W))
  val totalFpuOps = RegInit(0.U(64.W))
  val totalValuOps = RegInit(0.U(64.W))
  val totalSaluOps = RegInit(0.U(64.W))
  val totalMulOps = RegInit(0.U(64.W))
  val totalSfuOps = RegInit(0.U(64.W))
  val totalL1dReq = RegInit(0.U(64.W))
  val totalL1dHit = RegInit(0.U(64.W))
  val totalL1dReadAllocMshr = RegInit(0.U(64.W))
  val totalL1dReadHitMshr = RegInit(0.U(64.W))
  val totalL1dReadMshrEntryFull = RegInit(0.U(64.W))
  val totalL1dReadMshrSubentryFull = RegInit(0.U(64.W))
  val totalL1dWriteAllocMshr = RegInit(0.U(64.W))
  val totalL1dWriteHitWshr = RegInit(0.U(64.W))
  val totalL1dReplacement = RegInit(0.U(64.W))
  val totalL1dDirtyWriteback = RegInit(0.U(64.W))
  val totalL1dMshrFullCycles = RegInit(0.U(64.W))
  val totalL1dRtabReplays = RegInit(0.U(64.W))
  val totalL1dReadBytes = RegInit(0.U(64.W))
  val totalL1dWriteBytes = RegInit(0.U(64.W))
  val totalL1dReadTransactions = RegInit(0.U(64.W))
  val totalL1dWriteTransactions = RegInit(0.U(64.W))
  val totalL1dMshrBusyCycles = RegInit(0.U(64.W))
  val totalL1dBackpressureCycles = RegInit(0.U(64.W))
  val totalL1dBpBlockedCoreReq = RegInit(0.U(64.W))
  val totalL1dBpRtabFull = RegInit(0.U(64.W))
  val totalL1dBpRtabAlmostFull = RegInit(0.U(64.W))
  val totalL1dBpReplay = RegInit(0.U(64.W))
  val totalL1dBpSt0TagProbeBusy = RegInit(0.U(64.W))
  val totalL1dBpSt0RefillHazard = RegInit(0.U(64.W))
  val totalL1dBpSt0PendingMissHazard = RegInit(0.U(64.W))
  val totalL1dBpSt0PipeBusy = RegInit(0.U(64.W))
  val totalL1dBpSt1HitBusy = RegInit(0.U(64.W))
  val totalL1dBpSt1MissMshrBusy = RegInit(0.U(64.W))
  val totalL1dBpSt1MissMemBusy = RegInit(0.U(64.W))
  val totalL1dBpSt1MissRspBusy = RegInit(0.U(64.W))
  val totalL1dBpSt1ReleaseHazard = RegInit(0.U(64.W))
  val totalL1dBpOther = RegInit(0.U(64.W))
  val totalLsuCoalescableInsts = RegInit(0.U(64.W))
  val totalLsuAtomicInsts = RegInit(0.U(64.W))
  val totalLsuActiveThreads = RegInit(0.U(64.W))
  val totalLsuSegments = RegInit(0.U(64.W))
  val totalLsuSegmentThreads = RegInit(0.U(64.W))
  val perfStartPulse = io.host_req.fire && !perfWindowStarted
  val perfDumpPulse = io.perfDump && perfWindowStarted && !perfWindowPrinted
  when(perfStartPulse){
    perfWindowStarted := true.B
    perfWindowPrinted := false.B
  }.elsewhen(perfDumpPulse){
    perfWindowStarted := false.B
    perfWindowPrinted := true.B
    programId := programId + 1.U
    totalProgramWindows := totalProgramWindows + 1.U
    totalActiveCycles := totalActiveCycles + pmuActiveCycles
    totalScalarIssued := totalScalarIssued + pmuTotalScalarIssued
    totalVectorIssued := totalVectorIssued + pmuTotalVectorIssued
    totalThreadIssuedSlots := totalThreadIssuedSlots + pmuThreadIssuedSlots
    totalExecHazardX := totalExecHazardX + pmuExecHazardX
    totalExecHazardV := totalExecHazardV + pmuExecHazardV
    totalDataDepStall := totalDataDepStall + pmuDataDepStall
    totalBarrierStall := totalBarrierStall + pmuBarrierStall
    totalCtrlFlushCnt := totalCtrlFlushCnt + pmuCtrlFlushCnt
    totalFrontendStall := totalFrontendStall + pmuFrontendStall
    totalLsuBackpressure := totalLsuBackpressure + pmuLsuBackpressure
    totalIbufferFullCycles := totalIbufferFullCycles + pmuIbufferFullCycles
    totalComputeIssued := totalComputeIssued + pmuComputeIssued
    totalMemIssued := totalMemIssued + pmuMemIssued
    totalCtrlIssued := totalCtrlIssued + pmuCtrlIssued
    totalCtrlBranchIssued := totalCtrlBranchIssued + pmuCtrlBranchIssued
    totalCtrlBarrierIssued := totalCtrlBarrierIssued + pmuCtrlBarrierIssued
    totalCtrlCsrIssued := totalCtrlCsrIssued + pmuCtrlCsrIssued
    totalCtrlSimtStackIssued := totalCtrlSimtStackIssued + pmuCtrlSimtStackIssued
    totalCtrlFenceIssued := totalCtrlFenceIssued + pmuCtrlFenceIssued
    totalMemLoadIssued := totalMemLoadIssued + pmuMemLoadIssued
    totalMemStoreIssued := totalMemStoreIssued + pmuMemStoreIssued
    totalMemAtomicIssued := totalMemAtomicIssued + pmuMemAtomicIssued
    totalComputeSaluIssued := totalComputeSaluIssued + pmuComputeSaluIssued
    totalComputeValuIssued := totalComputeValuIssued + pmuComputeValuIssued
    totalComputeFpuIssued := totalComputeFpuIssued + pmuComputeFpuIssued
    totalComputeMulIssued := totalComputeMulIssued + pmuComputeMulIssued
    totalComputeSfuIssued := totalComputeSfuIssued + pmuComputeSfuIssued
    totalComputeTensorCoreIssued := totalComputeTensorCoreIssued + pmuComputeTensorCoreIssued
    totalComputeOps := totalComputeOps + pmuComputeOps
    totalFpuOps := totalFpuOps + pmuFpuOps
    totalValuOps := totalValuOps + pmuValuOps
    totalSaluOps := totalSaluOps + pmuSaluOps
    totalMulOps := totalMulOps + pmuMulOps
    totalSfuOps := totalSfuOps + pmuSfuOps
    totalL1dReq := totalL1dReq + l1dTotalReq
    totalL1dHit := totalL1dHit + l1dTotalHit
    totalL1dReadAllocMshr := totalL1dReadAllocMshr + l1dReadAllocMshr
    totalL1dReadHitMshr := totalL1dReadHitMshr + l1dReadHitMshr
    totalL1dReadMshrEntryFull := totalL1dReadMshrEntryFull + l1dReadMshrEntryFull
    totalL1dReadMshrSubentryFull := totalL1dReadMshrSubentryFull + l1dReadMshrSubentryFull
    totalL1dWriteAllocMshr := totalL1dWriteAllocMshr + l1dWriteAllocMshr
    totalL1dWriteHitWshr := totalL1dWriteHitWshr + l1dWriteHitWshr
    totalL1dReplacement := totalL1dReplacement + l1dReplacement
    totalL1dDirtyWriteback := totalL1dDirtyWriteback + l1dDirtyWriteback
    totalL1dMshrFullCycles := totalL1dMshrFullCycles + l1dMshrFullCycles
    totalL1dRtabReplays := totalL1dRtabReplays + l1dRtabReplays
    totalL1dReadBytes := totalL1dReadBytes + l1dReadBytes
    totalL1dWriteBytes := totalL1dWriteBytes + l1dWriteBytes
    totalL1dReadTransactions := totalL1dReadTransactions + l1dReadTransactions
    totalL1dWriteTransactions := totalL1dWriteTransactions + l1dWriteTransactions
    totalL1dMshrBusyCycles := totalL1dMshrBusyCycles + l1dMshrBusyCycles
    totalL1dBackpressureCycles := totalL1dBackpressureCycles + l1dBackpressureCycles
    totalL1dBpBlockedCoreReq := totalL1dBpBlockedCoreReq + l1dBpBlockedCoreReq
    totalL1dBpRtabFull := totalL1dBpRtabFull + l1dBpRtabFull
    totalL1dBpRtabAlmostFull := totalL1dBpRtabAlmostFull + l1dBpRtabAlmostFull
    totalL1dBpReplay := totalL1dBpReplay + l1dBpReplay
    totalL1dBpSt0TagProbeBusy := totalL1dBpSt0TagProbeBusy + l1dBpSt0TagProbeBusy
    totalL1dBpSt0RefillHazard := totalL1dBpSt0RefillHazard + l1dBpSt0RefillHazard
    totalL1dBpSt0PendingMissHazard := totalL1dBpSt0PendingMissHazard + l1dBpSt0PendingMissHazard
    totalL1dBpSt0PipeBusy := totalL1dBpSt0PipeBusy + l1dBpSt0PipeBusy
    totalL1dBpSt1HitBusy := totalL1dBpSt1HitBusy + l1dBpSt1HitBusy
    totalL1dBpSt1MissMshrBusy := totalL1dBpSt1MissMshrBusy + l1dBpSt1MissMshrBusy
    totalL1dBpSt1MissMemBusy := totalL1dBpSt1MissMemBusy + l1dBpSt1MissMemBusy
    totalL1dBpSt1MissRspBusy := totalL1dBpSt1MissRspBusy + l1dBpSt1MissRspBusy
    totalL1dBpSt1ReleaseHazard := totalL1dBpSt1ReleaseHazard + l1dBpSt1ReleaseHazard
    totalL1dBpOther := totalL1dBpOther + l1dBpOther
    totalLsuCoalescableInsts := totalLsuCoalescableInsts + lsuCoalescableInsts
    totalLsuAtomicInsts := totalLsuAtomicInsts + lsuAtomicInsts
    totalLsuActiveThreads := totalLsuActiveThreads + lsuActiveThreads
    totalLsuSegments := totalLsuSegments + lsuSegments
    totalLsuSegmentThreads := totalLsuSegmentThreads + lsuSegmentThreads
  }
  sm_wrapper.foreach { sm =>
    sm.perfEnable := perfWindowStarted || perfStartPulse
    sm.perfReset := perfStartPulse
  }

  val summaryNeedsCurrentWindow = perfDumpPulse || (io.perfDumpSummary && perfWindowStarted && !perfWindowPrinted)

  def includeCurrentWindow(total: UInt, current: UInt): UInt = {
    total + Mux(summaryNeedsCurrentWindow, current, 0.U(total.getWidth.W))
  }

  val summaryProgramWindows = includeCurrentWindow(totalProgramWindows, 1.U(32.W))
  val summaryActiveCycles = includeCurrentWindow(totalActiveCycles, pmuActiveCycles)
  val summaryScalarIssued = includeCurrentWindow(totalScalarIssued, pmuTotalScalarIssued)
  val summaryVectorIssued = includeCurrentWindow(totalVectorIssued, pmuTotalVectorIssued)
  val summaryThreadIssuedSlots = includeCurrentWindow(totalThreadIssuedSlots, pmuThreadIssuedSlots)
  val summaryExecHazardX = includeCurrentWindow(totalExecHazardX, pmuExecHazardX)
  val summaryExecHazardV = includeCurrentWindow(totalExecHazardV, pmuExecHazardV)
  val summaryDataDepStall = includeCurrentWindow(totalDataDepStall, pmuDataDepStall)
  val summaryBarrierStall = includeCurrentWindow(totalBarrierStall, pmuBarrierStall)
  val summaryCtrlFlushCnt = includeCurrentWindow(totalCtrlFlushCnt, pmuCtrlFlushCnt)
  val summaryFrontendStall = includeCurrentWindow(totalFrontendStall, pmuFrontendStall)
  val summaryLsuBackpressure = includeCurrentWindow(totalLsuBackpressure, pmuLsuBackpressure)
  val summaryIbufferFullCycles = includeCurrentWindow(totalIbufferFullCycles, pmuIbufferFullCycles)
  val summaryComputeIssued = includeCurrentWindow(totalComputeIssued, pmuComputeIssued)
  val summaryMemIssued = includeCurrentWindow(totalMemIssued, pmuMemIssued)
  val summaryCtrlIssued = includeCurrentWindow(totalCtrlIssued, pmuCtrlIssued)
  val summaryCtrlBranchIssued = includeCurrentWindow(totalCtrlBranchIssued, pmuCtrlBranchIssued)
  val summaryCtrlBarrierIssued = includeCurrentWindow(totalCtrlBarrierIssued, pmuCtrlBarrierIssued)
  val summaryCtrlCsrIssued = includeCurrentWindow(totalCtrlCsrIssued, pmuCtrlCsrIssued)
  val summaryCtrlSimtStackIssued = includeCurrentWindow(totalCtrlSimtStackIssued, pmuCtrlSimtStackIssued)
  val summaryCtrlFenceIssued = includeCurrentWindow(totalCtrlFenceIssued, pmuCtrlFenceIssued)
  val summaryMemLoadIssued = includeCurrentWindow(totalMemLoadIssued, pmuMemLoadIssued)
  val summaryMemStoreIssued = includeCurrentWindow(totalMemStoreIssued, pmuMemStoreIssued)
  val summaryMemAtomicIssued = includeCurrentWindow(totalMemAtomicIssued, pmuMemAtomicIssued)
  val summaryComputeSaluIssued = includeCurrentWindow(totalComputeSaluIssued, pmuComputeSaluIssued)
  val summaryComputeValuIssued = includeCurrentWindow(totalComputeValuIssued, pmuComputeValuIssued)
  val summaryComputeFpuIssued = includeCurrentWindow(totalComputeFpuIssued, pmuComputeFpuIssued)
  val summaryComputeMulIssued = includeCurrentWindow(totalComputeMulIssued, pmuComputeMulIssued)
  val summaryComputeSfuIssued = includeCurrentWindow(totalComputeSfuIssued, pmuComputeSfuIssued)
  val summaryComputeTensorCoreIssued = includeCurrentWindow(totalComputeTensorCoreIssued, pmuComputeTensorCoreIssued)
  val summaryComputeOps = includeCurrentWindow(totalComputeOps, pmuComputeOps)
  val summaryFpuOps = includeCurrentWindow(totalFpuOps, pmuFpuOps)
  val summaryValuOps = includeCurrentWindow(totalValuOps, pmuValuOps)
  val summarySaluOps = includeCurrentWindow(totalSaluOps, pmuSaluOps)
  val summaryMulOps = includeCurrentWindow(totalMulOps, pmuMulOps)
  val summarySfuOps = includeCurrentWindow(totalSfuOps, pmuSfuOps)
  val summaryL1dReq = includeCurrentWindow(totalL1dReq, l1dTotalReq)
  val summaryL1dHit = includeCurrentWindow(totalL1dHit, l1dTotalHit)
  val summaryL1dReadAllocMshr = includeCurrentWindow(totalL1dReadAllocMshr, l1dReadAllocMshr)
  val summaryL1dReadHitMshr = includeCurrentWindow(totalL1dReadHitMshr, l1dReadHitMshr)
  val summaryL1dReadMshrEntryFull = includeCurrentWindow(totalL1dReadMshrEntryFull, l1dReadMshrEntryFull)
  val summaryL1dReadMshrSubentryFull = includeCurrentWindow(totalL1dReadMshrSubentryFull, l1dReadMshrSubentryFull)
  val summaryL1dWriteAllocMshr = includeCurrentWindow(totalL1dWriteAllocMshr, l1dWriteAllocMshr)
  val summaryL1dWriteHitWshr = includeCurrentWindow(totalL1dWriteHitWshr, l1dWriteHitWshr)
  val summaryL1dReplacement = includeCurrentWindow(totalL1dReplacement, l1dReplacement)
  val summaryL1dDirtyWriteback = includeCurrentWindow(totalL1dDirtyWriteback, l1dDirtyWriteback)
  val summaryL1dMshrFullCycles = includeCurrentWindow(totalL1dMshrFullCycles, l1dMshrFullCycles)
  val summaryL1dRtabReplays = includeCurrentWindow(totalL1dRtabReplays, l1dRtabReplays)
  val summaryL1dReadBytes = includeCurrentWindow(totalL1dReadBytes, l1dReadBytes)
  val summaryL1dWriteBytes = includeCurrentWindow(totalL1dWriteBytes, l1dWriteBytes)
  val summaryL1dReadTransactions = includeCurrentWindow(totalL1dReadTransactions, l1dReadTransactions)
  val summaryL1dWriteTransactions = includeCurrentWindow(totalL1dWriteTransactions, l1dWriteTransactions)
  val summaryL1dMshrBusyCycles = includeCurrentWindow(totalL1dMshrBusyCycles, l1dMshrBusyCycles)
  val summaryL1dBackpressureCycles = includeCurrentWindow(totalL1dBackpressureCycles, l1dBackpressureCycles)
  val summaryL1dBpBlockedCoreReq = includeCurrentWindow(totalL1dBpBlockedCoreReq, l1dBpBlockedCoreReq)
  val summaryL1dBpRtabFull = includeCurrentWindow(totalL1dBpRtabFull, l1dBpRtabFull)
  val summaryL1dBpRtabAlmostFull = includeCurrentWindow(totalL1dBpRtabAlmostFull, l1dBpRtabAlmostFull)
  val summaryL1dBpReplay = includeCurrentWindow(totalL1dBpReplay, l1dBpReplay)
  val summaryL1dBpSt0TagProbeBusy = includeCurrentWindow(totalL1dBpSt0TagProbeBusy, l1dBpSt0TagProbeBusy)
  val summaryL1dBpSt0RefillHazard = includeCurrentWindow(totalL1dBpSt0RefillHazard, l1dBpSt0RefillHazard)
  val summaryL1dBpSt0PendingMissHazard = includeCurrentWindow(totalL1dBpSt0PendingMissHazard, l1dBpSt0PendingMissHazard)
  val summaryL1dBpSt0PipeBusy = includeCurrentWindow(totalL1dBpSt0PipeBusy, l1dBpSt0PipeBusy)
  val summaryL1dBpSt1HitBusy = includeCurrentWindow(totalL1dBpSt1HitBusy, l1dBpSt1HitBusy)
  val summaryL1dBpSt1MissMshrBusy = includeCurrentWindow(totalL1dBpSt1MissMshrBusy, l1dBpSt1MissMshrBusy)
  val summaryL1dBpSt1MissMemBusy = includeCurrentWindow(totalL1dBpSt1MissMemBusy, l1dBpSt1MissMemBusy)
  val summaryL1dBpSt1MissRspBusy = includeCurrentWindow(totalL1dBpSt1MissRspBusy, l1dBpSt1MissRspBusy)
  val summaryL1dBpSt1ReleaseHazard = includeCurrentWindow(totalL1dBpSt1ReleaseHazard, l1dBpSt1ReleaseHazard)
  val summaryL1dBpOther = includeCurrentWindow(totalL1dBpOther, l1dBpOther)
  val summaryLsuCoalescableInsts = includeCurrentWindow(totalLsuCoalescableInsts, lsuCoalescableInsts)
  val summaryLsuAtomicInsts = includeCurrentWindow(totalLsuAtomicInsts, lsuAtomicInsts)
  val summaryLsuActiveThreads = includeCurrentWindow(totalLsuActiveThreads, lsuActiveThreads)
  val summaryLsuSegments = includeCurrentWindow(totalLsuSegments, lsuSegments)
  val summaryLsuSegmentThreads = includeCurrentWindow(totalLsuSegmentThreads, lsuSegmentThreads)
  val summaryTotalIssued = summaryScalarIssued + summaryVectorIssued
  val summaryTotalClassIssued = summaryComputeIssued + summaryMemIssued + summaryCtrlIssued

  when(perfDumpPulse){
    printf(p"\n[PROGRAM ${programId}] [PMU] first-kernel-start -> last-kernel-end summary\n")

    // ========== 1. 总体概览 ==========
    if (PMU_PIPELINE) {
      printf(p"[PROGRAM ${programId}] [OVERVIEW] active cycles (cycles): ${pmuActiveCycles}\n")
      printf(p"[PROGRAM ${programId}] [OVERVIEW] scalar issued (inst) : ${pmuTotalScalarIssued}\n")
      printf(p"[PROGRAM ${programId}] [OVERVIEW] vector issued (inst) : ${pmuTotalVectorIssued}\n")
      printf(p"[PROGRAM ${programId}] [OVERVIEW] total issued (inst)  : ${pmuTotalIssued}\n")
      printf(p"[PROGRAM ${programId}] [OVERVIEW] thread issued slots (thd-slots): ${pmuThreadIssuedSlots}\n")
      printf(p"[PROGRAM ${programId}] [OVERVIEW] issued thd-slot util : ${pmuThreadUtilPct}%\n")
      printf(p"[PROGRAM ${programId}] [OVERVIEW] IPC (inst/cycle)     : ${Mux(pmuActiveCycles === 0.U, 0.U, (pmuTotalIssued * 100.U) / pmuActiveCycles)} (x0.01)\n")
    }

    // ========== 2. Stall分析 ==========
    if (PMU_PIPELINE) {
      printf(p"[PROGRAM ${programId}] [STALL] exec hazard X (cycles): ${pmuExecHazardX} (${percentOf(pmuExecHazardX, pmuActiveCycles)}%)\n")
      printf(p"[PROGRAM ${programId}] [STALL] exec hazard V (cycles): ${pmuExecHazardV} (${percentOf(pmuExecHazardV, pmuActiveCycles)}%)\n")
      printf(p"[PROGRAM ${programId}] [STALL] data dependency (cycles): ${pmuDataDepStall} (${percentOf(pmuDataDepStall, pmuActiveCycles)}%)\n")
      printf(p"[PROGRAM ${programId}] [STALL] barrier stall (cycles): ${pmuBarrierStall} (${percentOf(pmuBarrierStall, pmuActiveCycles)}%)\n")
      printf(p"[PROGRAM ${programId}] [STALL] control flush (events): ${pmuCtrlFlushCnt}\n")
      printf(p"[PROGRAM ${programId}] [STALL] frontend stall (cycles): ${pmuFrontendStall} (${percentOf(pmuFrontendStall, pmuActiveCycles)}%)\n")
      printf(p"[PROGRAM ${programId}] [STALL] lsu backpressure (cycles): ${pmuLsuBackpressure} (${percentOf(pmuLsuBackpressure, pmuActiveCycles)}%)\n")
      printf(p"[PROGRAM ${programId}] [STALL] ibuffer full (cycles): ${pmuIbufferFullCycles} (${percentOf(pmuIbufferFullCycles, pmuActiveCycles)}%)\n")
    }

    // ========== 3. Memory诊断 ==========
    printf(p"[PROGRAM ${programId}] [L1D BW] read bytes (B)        : ${l1dReadBytes}\n")
    printf(p"[PROGRAM ${programId}] [L1D BW] write bytes (B)       : ${l1dWriteBytes}\n")
    printf(p"[PROGRAM ${programId}] [L1D BW] total bytes (B)       : ${l1dTotalBytes}\n")
    printf(p"[PROGRAM ${programId}] [L1D BW] read bandwidth (B/cycle): ${Mux(pmuActiveCycles === 0.U, 0.U, (l1dReadBytes * 100.U) / pmuActiveCycles)} (x0.01)\n")
    printf(p"[PROGRAM ${programId}] [L1D BW] write bandwidth (B/cycle): ${Mux(pmuActiveCycles === 0.U, 0.U, (l1dWriteBytes * 100.U) / pmuActiveCycles)} (x0.01)\n")
    printf(p"[PROGRAM ${programId}] [L1D BW] total bandwidth (B/cycle): ${Mux(pmuActiveCycles === 0.U, 0.U, (l1dTotalBytes * 100.U) / pmuActiveCycles)} (x0.01)\n")
    printf(p"[PROGRAM ${programId}] [L1D TX] read transactions (req): ${l1dReadTransactions}\n")
    printf(p"[PROGRAM ${programId}] [L1D TX] write transactions (req): ${l1dWriteTransactions}\n")
    printf(p"[PROGRAM ${programId}] [L1D TX] total requests (req) : ${l1dTotalReq}\n")
    printf(p"[PROGRAM ${programId}] [L1D HIT] hit rate             : ${percentOf(l1dTotalHit, l1dTotalReq)}% (${l1dTotalHit}/${l1dTotalReq})\n")
    printf(p"[PROGRAM ${programId}] [L1D MSHR] utilization         : ${percentOf(l1dMshrBusyCycles, pmuActiveCycles)}% (${l1dMshrBusyCycles}/${pmuActiveCycles})\n")

    // ========== 4. L1 miss细分 ==========
    printf(p"[PROGRAM ${programId}] [L1D MISS] read alloc MSHR (req): ${l1dReadAllocMshr}\n")
    printf(p"[PROGRAM ${programId}] [L1D MISS] read hit MSHR (req): ${l1dReadHitMshr}\n")
    printf(p"[PROGRAM ${programId}] [L1D MISS] read entry full (req): ${l1dReadMshrEntryFull}\n")
    printf(p"[PROGRAM ${programId}] [L1D MISS] read subentry full (req): ${l1dReadMshrSubentryFull}\n")
    printf(p"[PROGRAM ${programId}] [L1D MISS] write alloc MSHR (req): ${l1dWriteAllocMshr}\n")
    printf(p"[PROGRAM ${programId}] [L1D MISS] write hit WSHR (req): ${l1dWriteHitWshr}\n")
    printf(p"[PROGRAM ${programId}] [L1D MISC] replacements (lines): ${l1dReplacement}\n")
    printf(p"[PROGRAM ${programId}] [L1D MISC] dirty writebacks (req): ${l1dDirtyWriteback}\n")
    printf(p"[PROGRAM ${programId}] [L1D MISC] MSHR full (cycles)  : ${l1dMshrFullCycles} (${percentOf(l1dMshrFullCycles, pmuActiveCycles)}%)\n")
    printf(p"[PROGRAM ${programId}] [L1D MISC] RTAB replays (req)  : ${l1dRtabReplays}\n")

    // ========== 3.1 LSU coalescing ==========
    printf(p"[PROGRAM ${programId}] [LSU COAL] coalescable insts (inst): ${lsuCoalescableInsts}\n")
    printf(p"[PROGRAM ${programId}] [LSU COAL] atomic insts (inst) : ${lsuAtomicInsts}\n")
    printf(p"[PROGRAM ${programId}] [LSU COAL] active threads (thd): ${lsuActiveThreads}\n")
    printf(p"[PROGRAM ${programId}] [LSU COAL] emitted segments (seg): ${lsuSegments}\n")
    printf(p"[PROGRAM ${programId}] [LSU COAL] segment threads (thd): ${lsuSegmentThreads}\n")
    printf(p"[PROGRAM ${programId}] [LSU COAL] avg seg/inst         : ${Mux(lsuCoalescableInsts === 0.U, 0.U, (lsuSegments * 100.U) / lsuCoalescableInsts)} (x0.01)\n")
    printf(p"[PROGRAM ${programId}] [LSU COAL] avg thd/seg          : ${Mux(lsuSegments === 0.U, 0.U, (lsuSegmentThreads * 100.U) / lsuSegments)} (x0.01)\n")
    printf(p"[PROGRAM ${programId}] [LSU COAL] efficiency           : ${Mux(lsuSegments === 0.U, 0.U, (lsuSegmentThreads * 100.U) / (lsuSegments * num_thread.U))}%\n")

    // ========== 3.2 L1 backpressure ==========
    printf(p"[PROGRAM ${programId}] [L1D BP] total blocked (cycles): ${l1dBackpressureCycles} (${percentOf(l1dBackpressureCycles, pmuActiveCycles)}%)\n")
    printf(p"[PROGRAM ${programId}] [L1D BP] blockCoreReq (cycles): ${l1dBpBlockedCoreReq} (${percentOf(l1dBpBlockedCoreReq, l1dBackpressureCycles)}%)\n")
    printf(p"[PROGRAM ${programId}] [L1D BP] rtab full (cycles)   : ${l1dBpRtabFull} (${percentOf(l1dBpRtabFull, l1dBackpressureCycles)}%)\n")
    printf(p"[PROGRAM ${programId}] [L1D BP] rtab almost full (cycles): ${l1dBpRtabAlmostFull} (${percentOf(l1dBpRtabAlmostFull, l1dBackpressureCycles)}%)\n")
    printf(p"[PROGRAM ${programId}] [L1D BP] replay occupied (cycles): ${l1dBpReplay} (${percentOf(l1dBpReplay, l1dBackpressureCycles)}%)\n")
    printf(p"[PROGRAM ${programId}] [L1D BP] st0 tag probe busy (cycles): ${l1dBpSt0TagProbeBusy} (${percentOf(l1dBpSt0TagProbeBusy, l1dBackpressureCycles)}%)\n")
    printf(p"[PROGRAM ${programId}] [L1D BP] st0 refill hazard (cycles): ${l1dBpSt0RefillHazard} (${percentOf(l1dBpSt0RefillHazard, l1dBackpressureCycles)}%)\n")
    printf(p"[PROGRAM ${programId}] [L1D BP] st0 pending miss (cycles): ${l1dBpSt0PendingMissHazard} (${percentOf(l1dBpSt0PendingMissHazard, l1dBackpressureCycles)}%)\n")
    printf(p"[PROGRAM ${programId}] [L1D BP] st0 pipe busy (cycles): ${l1dBpSt0PipeBusy} (${percentOf(l1dBpSt0PipeBusy, l1dBackpressureCycles)}%)\n")
    printf(p"[PROGRAM ${programId}] [L1D BP] st1 hit busy (cycles): ${l1dBpSt1HitBusy} (${percentOf(l1dBpSt1HitBusy, l1dBackpressureCycles)}%)\n")
    printf(p"[PROGRAM ${programId}] [L1D BP] st1 miss mshr (cycles): ${l1dBpSt1MissMshrBusy} (${percentOf(l1dBpSt1MissMshrBusy, l1dBackpressureCycles)}%)\n")
    printf(p"[PROGRAM ${programId}] [L1D BP] st1 miss mem (cycles): ${l1dBpSt1MissMemBusy} (${percentOf(l1dBpSt1MissMemBusy, l1dBackpressureCycles)}%)\n")
    printf(p"[PROGRAM ${programId}] [L1D BP] st1 miss rsp (cycles): ${l1dBpSt1MissRspBusy} (${percentOf(l1dBpSt1MissRspBusy, l1dBackpressureCycles)}%)\n")
    printf(p"[PROGRAM ${programId}] [L1D BP] st1 release hazard (cycles): ${l1dBpSt1ReleaseHazard} (${percentOf(l1dBpSt1ReleaseHazard, l1dBackpressureCycles)}%)\n")
    printf(p"[PROGRAM ${programId}] [L1D BP] other (cycles)       : ${l1dBpOther} (${percentOf(l1dBpOther, l1dBackpressureCycles)}%)\n")

    // ========== 4. 算力性能 ==========
    if (PMU_INST_CLASS) {
      printf(p"[PROGRAM ${programId}] [COMPUTE] total ops (ops)      : ${pmuComputeOps}\n")
      printf(p"[PROGRAM ${programId}] [COMPUTE] throughput (ops/cycle): ${Mux(pmuActiveCycles === 0.U, 0.U, (pmuComputeOps * 100.U) / pmuActiveCycles)} (x0.01)\n")
      printf(p"[PROGRAM ${programId}] [COMPUTE] FPU ops (ops)        : ${pmuFpuOps}\n")
      printf(p"[PROGRAM ${programId}] [COMPUTE] vALU ops (ops)       : ${pmuValuOps}\n")
      printf(p"[PROGRAM ${programId}] [COMPUTE] sALU ops (ops)       : ${pmuSaluOps}\n")
      printf(p"[PROGRAM ${programId}] [COMPUTE] MUL ops (ops)        : ${pmuMulOps}\n")
      printf(p"[PROGRAM ${programId}] [COMPUTE] SFU ops (ops)        : ${pmuSfuOps}\n")
    }

    // ========== 5. 指令分类 ==========
    if (PMU_INST_CLASS) {
      printf(p"[PROGRAM ${programId}] [INST] total class issued (inst): ${pmuComputeIssued + pmuMemIssued + pmuCtrlIssued}\n")
      printf(p"[PROGRAM ${programId}] [INST] compute issued (inst) : ${pmuComputeIssued}\n")
      printf(p"[PROGRAM ${programId}] [INST]   - sALU (inst)       : ${pmuComputeSaluIssued}\n")
      printf(p"[PROGRAM ${programId}] [INST]   - vALU (inst)       : ${pmuComputeValuIssued}\n")
      printf(p"[PROGRAM ${programId}] [INST]   - FPU (inst)        : ${pmuComputeFpuIssued}\n")
      printf(p"[PROGRAM ${programId}] [INST]   - MUL (inst)        : ${pmuComputeMulIssued}\n")
      printf(p"[PROGRAM ${programId}] [INST]   - SFU (inst)        : ${pmuComputeSfuIssued}\n")
      printf(p"[PROGRAM ${programId}] [INST]   - TensorCore (inst) : ${pmuComputeTensorCoreIssued}\n")
      printf(p"[PROGRAM ${programId}] [INST] mem issued (inst)     : ${pmuMemIssued}\n")
      printf(p"[PROGRAM ${programId}] [INST]   - load (inst)       : ${pmuMemLoadIssued}\n")
      printf(p"[PROGRAM ${programId}] [INST]   - store (inst)      : ${pmuMemStoreIssued}\n")
      printf(p"[PROGRAM ${programId}] [INST]   - atomic (inst)     : ${pmuMemAtomicIssued}\n")
      printf(p"[PROGRAM ${programId}] [INST] ctrl issued (inst)    : ${pmuCtrlIssued}\n")
      printf(p"[PROGRAM ${programId}] [INST]   - branch (inst)     : ${pmuCtrlBranchIssued}\n")
      printf(p"[PROGRAM ${programId}] [INST]   - barrier (inst)    : ${pmuCtrlBarrierIssued}\n")
      printf(p"[PROGRAM ${programId}] [INST]   - csr (inst)        : ${pmuCtrlCsrIssued}\n")
      printf(p"[PROGRAM ${programId}] [INST]   - simt stack (inst) : ${pmuCtrlSimtStackIssued}\n")
      printf(p"[PROGRAM ${programId}] [INST]   - fence (inst)      : ${pmuCtrlFenceIssued}\n")
    }
  }
  when((perfDumpPulse || io.perfDumpSummary) && summaryProgramWindows =/= 0.U){
    printf(p"\n[TESTCASE TOTAL] [PMU] accumulated summary across ${summaryProgramWindows} program windows\n")

    // ========== 1. 总体概览 ==========
    if (PMU_PIPELINE) {
      printf(p"[TESTCASE TOTAL] [OVERVIEW] active cycles (cycles): ${summaryActiveCycles}\n")
      printf(p"[TESTCASE TOTAL] [OVERVIEW] scalar issued (inst) : ${summaryScalarIssued}\n")
      printf(p"[TESTCASE TOTAL] [OVERVIEW] vector issued (inst) : ${summaryVectorIssued}\n")
      printf(p"[TESTCASE TOTAL] [OVERVIEW] total issued (inst)  : ${summaryTotalIssued}\n")
      printf(p"[TESTCASE TOTAL] [OVERVIEW] thread issued slots (thd-slots): ${summaryThreadIssuedSlots}\n")
      printf(p"[TESTCASE TOTAL] [OVERVIEW] issued thd-slot util : ${Mux(summaryActiveCycles === 0.U, 0.U, (summaryThreadIssuedSlots * 100.U) / (summaryActiveCycles * num_thread.U))}%\n")
      printf(p"[TESTCASE TOTAL] [OVERVIEW] IPC (inst/cycle)     : ${Mux(summaryActiveCycles === 0.U, 0.U, (summaryTotalIssued * 100.U) / summaryActiveCycles)} (x0.01)\n")
    }

    // ========== 2. Stall分析 ==========
    if (PMU_PIPELINE) {
      printf(p"[TESTCASE TOTAL] [STALL] exec hazard X (cycles): ${summaryExecHazardX} (${percentOf(summaryExecHazardX, summaryActiveCycles)}%)\n")
      printf(p"[TESTCASE TOTAL] [STALL] exec hazard V (cycles): ${summaryExecHazardV} (${percentOf(summaryExecHazardV, summaryActiveCycles)}%)\n")
      printf(p"[TESTCASE TOTAL] [STALL] data dependency (cycles): ${summaryDataDepStall} (${percentOf(summaryDataDepStall, summaryActiveCycles)}%)\n")
      printf(p"[TESTCASE TOTAL] [STALL] barrier stall (cycles): ${summaryBarrierStall} (${percentOf(summaryBarrierStall, summaryActiveCycles)}%)\n")
      printf(p"[TESTCASE TOTAL] [STALL] control flush (events): ${summaryCtrlFlushCnt}\n")
      printf(p"[TESTCASE TOTAL] [STALL] frontend stall (cycles): ${summaryFrontendStall} (${percentOf(summaryFrontendStall, summaryActiveCycles)}%)\n")
      printf(p"[TESTCASE TOTAL] [STALL] lsu backpressure (cycles): ${summaryLsuBackpressure} (${percentOf(summaryLsuBackpressure, summaryActiveCycles)}%)\n")
      printf(p"[TESTCASE TOTAL] [STALL] ibuffer full (cycles): ${summaryIbufferFullCycles} (${percentOf(summaryIbufferFullCycles, summaryActiveCycles)}%)\n")
    }

    // ========== 3. Memory诊断 ==========
    printf(p"[TESTCASE TOTAL] [L1D BW] read bytes (B)        : ${summaryL1dReadBytes}\n")
    printf(p"[TESTCASE TOTAL] [L1D BW] write bytes (B)       : ${summaryL1dWriteBytes}\n")
    printf(p"[TESTCASE TOTAL] [L1D BW] total bytes (B)       : ${summaryL1dReadBytes + summaryL1dWriteBytes}\n")
    printf(p"[TESTCASE TOTAL] [L1D BW] read bandwidth (B/cycle): ${Mux(summaryActiveCycles === 0.U, 0.U, (summaryL1dReadBytes * 100.U) / summaryActiveCycles)} (x0.01)\n")
    printf(p"[TESTCASE TOTAL] [L1D BW] write bandwidth (B/cycle): ${Mux(summaryActiveCycles === 0.U, 0.U, (summaryL1dWriteBytes * 100.U) / summaryActiveCycles)} (x0.01)\n")
    printf(p"[TESTCASE TOTAL] [L1D BW] total bandwidth (B/cycle): ${Mux(summaryActiveCycles === 0.U, 0.U, ((summaryL1dReadBytes + summaryL1dWriteBytes) * 100.U) / summaryActiveCycles)} (x0.01)\n")
    printf(p"[TESTCASE TOTAL] [L1D TX] read transactions (req): ${summaryL1dReadTransactions}\n")
    printf(p"[TESTCASE TOTAL] [L1D TX] write transactions (req): ${summaryL1dWriteTransactions}\n")
    printf(p"[TESTCASE TOTAL] [L1D TX] total requests (req) : ${summaryL1dReq}\n")
    printf(p"[TESTCASE TOTAL] [L1D HIT] hit rate             : ${percentOf(summaryL1dHit, summaryL1dReq)}% (${summaryL1dHit}/${summaryL1dReq})\n")
    printf(p"[TESTCASE TOTAL] [L1D MSHR] utilization         : ${percentOf(summaryL1dMshrBusyCycles, summaryActiveCycles)}% (${summaryL1dMshrBusyCycles}/${summaryActiveCycles})\n")

    // ========== 4. L1 miss细分 ==========
    printf(p"[TESTCASE TOTAL] [L1D MISS] read alloc MSHR (req): ${summaryL1dReadAllocMshr}\n")
    printf(p"[TESTCASE TOTAL] [L1D MISS] read hit MSHR (req): ${summaryL1dReadHitMshr}\n")
    printf(p"[TESTCASE TOTAL] [L1D MISS] read entry full (req): ${summaryL1dReadMshrEntryFull}\n")
    printf(p"[TESTCASE TOTAL] [L1D MISS] read subentry full (req): ${summaryL1dReadMshrSubentryFull}\n")
    printf(p"[TESTCASE TOTAL] [L1D MISS] write alloc MSHR (req): ${summaryL1dWriteAllocMshr}\n")
    printf(p"[TESTCASE TOTAL] [L1D MISS] write hit WSHR (req): ${summaryL1dWriteHitWshr}\n")
    printf(p"[TESTCASE TOTAL] [L1D MISC] replacements (lines): ${summaryL1dReplacement}\n")
    printf(p"[TESTCASE TOTAL] [L1D MISC] dirty writebacks (req): ${summaryL1dDirtyWriteback}\n")
    printf(p"[TESTCASE TOTAL] [L1D MISC] MSHR full (cycles)  : ${summaryL1dMshrFullCycles} (${percentOf(summaryL1dMshrFullCycles, summaryActiveCycles)}%)\n")
    printf(p"[TESTCASE TOTAL] [L1D MISC] RTAB replays (req)  : ${summaryL1dRtabReplays}\n")

    printf(p"[TESTCASE TOTAL] [LSU COAL] coalescable insts (inst): ${summaryLsuCoalescableInsts}\n")
    printf(p"[TESTCASE TOTAL] [LSU COAL] atomic insts (inst) : ${summaryLsuAtomicInsts}\n")
    printf(p"[TESTCASE TOTAL] [LSU COAL] active threads (thd): ${summaryLsuActiveThreads}\n")
    printf(p"[TESTCASE TOTAL] [LSU COAL] emitted segments (seg): ${summaryLsuSegments}\n")
    printf(p"[TESTCASE TOTAL] [LSU COAL] segment threads (thd): ${summaryLsuSegmentThreads}\n")
    printf(p"[TESTCASE TOTAL] [LSU COAL] avg seg/inst         : ${Mux(summaryLsuCoalescableInsts === 0.U, 0.U, (summaryLsuSegments * 100.U) / summaryLsuCoalescableInsts)} (x0.01)\n")
    printf(p"[TESTCASE TOTAL] [LSU COAL] avg thd/seg          : ${Mux(summaryLsuSegments === 0.U, 0.U, (summaryLsuSegmentThreads * 100.U) / summaryLsuSegments)} (x0.01)\n")
    printf(p"[TESTCASE TOTAL] [LSU COAL] efficiency           : ${Mux(summaryLsuSegments === 0.U, 0.U, (summaryLsuSegmentThreads * 100.U) / (summaryLsuSegments * num_thread.U))}%\n")

    printf(p"[TESTCASE TOTAL] [L1D BP] total blocked (cycles): ${summaryL1dBackpressureCycles} (${percentOf(summaryL1dBackpressureCycles, summaryActiveCycles)}%)\n")
    printf(p"[TESTCASE TOTAL] [L1D BP] blockCoreReq (cycles): ${summaryL1dBpBlockedCoreReq} (${percentOf(summaryL1dBpBlockedCoreReq, summaryL1dBackpressureCycles)}%)\n")
    printf(p"[TESTCASE TOTAL] [L1D BP] rtab full (cycles)   : ${summaryL1dBpRtabFull} (${percentOf(summaryL1dBpRtabFull, summaryL1dBackpressureCycles)}%)\n")
    printf(p"[TESTCASE TOTAL] [L1D BP] rtab almost full (cycles): ${summaryL1dBpRtabAlmostFull} (${percentOf(summaryL1dBpRtabAlmostFull, summaryL1dBackpressureCycles)}%)\n")
    printf(p"[TESTCASE TOTAL] [L1D BP] replay occupied (cycles): ${summaryL1dBpReplay} (${percentOf(summaryL1dBpReplay, summaryL1dBackpressureCycles)}%)\n")
    printf(p"[TESTCASE TOTAL] [L1D BP] st0 tag probe busy (cycles): ${summaryL1dBpSt0TagProbeBusy} (${percentOf(summaryL1dBpSt0TagProbeBusy, summaryL1dBackpressureCycles)}%)\n")
    printf(p"[TESTCASE TOTAL] [L1D BP] st0 refill hazard (cycles): ${summaryL1dBpSt0RefillHazard} (${percentOf(summaryL1dBpSt0RefillHazard, summaryL1dBackpressureCycles)}%)\n")
    printf(p"[TESTCASE TOTAL] [L1D BP] st0 pending miss (cycles): ${summaryL1dBpSt0PendingMissHazard} (${percentOf(summaryL1dBpSt0PendingMissHazard, summaryL1dBackpressureCycles)}%)\n")
    printf(p"[TESTCASE TOTAL] [L1D BP] st0 pipe busy (cycles): ${summaryL1dBpSt0PipeBusy} (${percentOf(summaryL1dBpSt0PipeBusy, summaryL1dBackpressureCycles)}%)\n")
    printf(p"[TESTCASE TOTAL] [L1D BP] st1 hit busy (cycles): ${summaryL1dBpSt1HitBusy} (${percentOf(summaryL1dBpSt1HitBusy, summaryL1dBackpressureCycles)}%)\n")
    printf(p"[TESTCASE TOTAL] [L1D BP] st1 miss mshr (cycles): ${summaryL1dBpSt1MissMshrBusy} (${percentOf(summaryL1dBpSt1MissMshrBusy, summaryL1dBackpressureCycles)}%)\n")
    printf(p"[TESTCASE TOTAL] [L1D BP] st1 miss mem (cycles): ${summaryL1dBpSt1MissMemBusy} (${percentOf(summaryL1dBpSt1MissMemBusy, summaryL1dBackpressureCycles)}%)\n")
    printf(p"[TESTCASE TOTAL] [L1D BP] st1 miss rsp (cycles): ${summaryL1dBpSt1MissRspBusy} (${percentOf(summaryL1dBpSt1MissRspBusy, summaryL1dBackpressureCycles)}%)\n")
    printf(p"[TESTCASE TOTAL] [L1D BP] st1 release hazard (cycles): ${summaryL1dBpSt1ReleaseHazard} (${percentOf(summaryL1dBpSt1ReleaseHazard, summaryL1dBackpressureCycles)}%)\n")
    printf(p"[TESTCASE TOTAL] [L1D BP] other (cycles)       : ${summaryL1dBpOther} (${percentOf(summaryL1dBpOther, summaryL1dBackpressureCycles)}%)\n")

    // ========== 4. 算力性能 ==========
    if (PMU_INST_CLASS) {
      printf(p"[TESTCASE TOTAL] [COMPUTE] total ops (ops)      : ${summaryComputeOps}\n")
      printf(p"[TESTCASE TOTAL] [COMPUTE] throughput (ops/cycle): ${Mux(summaryActiveCycles === 0.U, 0.U, (summaryComputeOps * 100.U) / summaryActiveCycles)} (x0.01)\n")
      printf(p"[TESTCASE TOTAL] [COMPUTE] FPU ops (ops)        : ${summaryFpuOps}\n")
      printf(p"[TESTCASE TOTAL] [COMPUTE] vALU ops (ops)       : ${summaryValuOps}\n")
      printf(p"[TESTCASE TOTAL] [COMPUTE] sALU ops (ops)       : ${summarySaluOps}\n")
      printf(p"[TESTCASE TOTAL] [COMPUTE] MUL ops (ops)        : ${summaryMulOps}\n")
      printf(p"[TESTCASE TOTAL] [COMPUTE] SFU ops (ops)        : ${summarySfuOps}\n")
    }

    // ========== 5. 指令分类 ==========
    if (PMU_INST_CLASS) {
      printf(p"[TESTCASE TOTAL] [INST] total class issued (inst): ${summaryTotalClassIssued}\n")
      printf(p"[TESTCASE TOTAL] [INST] compute issued (inst) : ${summaryComputeIssued}\n")
      printf(p"[TESTCASE TOTAL] [INST]   - sALU (inst)       : ${summaryComputeSaluIssued}\n")
      printf(p"[TESTCASE TOTAL] [INST]   - vALU (inst)       : ${summaryComputeValuIssued}\n")
      printf(p"[TESTCASE TOTAL] [INST]   - FPU (inst)        : ${summaryComputeFpuIssued}\n")
      printf(p"[TESTCASE TOTAL] [INST]   - MUL (inst)        : ${summaryComputeMulIssued}\n")
      printf(p"[TESTCASE TOTAL] [INST]   - SFU (inst)        : ${summaryComputeSfuIssued}\n")
      printf(p"[TESTCASE TOTAL] [INST]   - TensorCore (inst) : ${summaryComputeTensorCoreIssued}\n")
      printf(p"[TESTCASE TOTAL] [INST] mem issued (inst)     : ${summaryMemIssued}\n")
      printf(p"[TESTCASE TOTAL] [INST]   - load (inst)       : ${summaryMemLoadIssued}\n")
      printf(p"[TESTCASE TOTAL] [INST]   - store (inst)      : ${summaryMemStoreIssued}\n")
      printf(p"[TESTCASE TOTAL] [INST]   - atomic (inst)     : ${summaryMemAtomicIssued}\n")
      printf(p"[TESTCASE TOTAL] [INST] ctrl issued (inst)    : ${summaryCtrlIssued}\n")
      printf(p"[TESTCASE TOTAL] [INST]   - branch (inst)     : ${summaryCtrlBranchIssued}\n")
      printf(p"[TESTCASE TOTAL] [INST]   - barrier (inst)    : ${summaryCtrlBarrierIssued}\n")
      printf(p"[TESTCASE TOTAL] [INST]   - csr (inst)        : ${summaryCtrlCsrIssued}\n")
      printf(p"[TESTCASE TOTAL] [INST]   - simt stack (inst) : ${summaryCtrlSimtStackIssued}\n")
      printf(p"[TESTCASE TOTAL] [INST]   - fence (inst)      : ${summaryCtrlFenceIssued}\n")
    }
  }

  for(i <- 0 until NL2Cache){
    val port = l2cache(i).in_a
    val cache_id: UInt = port.bits.source(l1cache_sourceBits)
    val sm_id: UInt = if (NSmInCluster == 1) {
      0.U
    } else {
      port.bits.source(l1cache_sourceBits + log2Up(NSmInCluster), l1cache_sourceBits + 1)
    }
    when(port.fire){
      printf(p"[L1C] #${io.cycle_cnt} SM ${sm_id} CACHE ${cache_id} ADDR ${Hexadecimal(port.bits.address)}\n")
    }
  }
}

@instantiable
class SM_wrapper(FakeCache: Boolean = false, SV: Option[mmu.SVParam] = None) extends Module{
  val param = (new MyConfig).toInstance
  class MMU_RVGParam(implicit val p: Parameters) extends HasRVGParameters
  @public val sm_id = IO(Input(UInt(8.W)))
  @public val io = IO(new Bundle{
    val CTAreq=Flipped(Decoupled(new CTAreqData))
    val CTArsp=(Decoupled(new CTArspData))
    val memRsp = Flipped(DecoupledIO(new L1CacheMemRsp()(param)))
    val memReq = DecoupledIO(new L1CacheMemReq)
    val perfEnable = Input(Bool())
    val perfReset = Input(Bool())
    val dcache_perf = Output(new DCachePerfCounters)
    val pipeline_perf = if(PMU_PIPELINE) Some(Output(new PipelinePerfCounters)) else None
    val inst_class_perf = if(PMU_INST_CLASS) Some(Output(new InstClassPerfCounters)) else None
    val lsu_perf = Output(new LsuPerfCounters)
    val inst = if (SINGLE_INST) Some(Flipped(DecoupledIO(UInt(32.W)))) else None
    val inst_cnt = if(INST_CNT) Some(Output(UInt(32.W))) else if(INST_CNT_2) Some(Output(Vec(2, UInt(32.W)))) else None
    val l2tlbReq = if(MMU_ENABLED) Some(Vec(num_cache_in_sm, DecoupledIO(new Bundle{
      val asid = UInt(SV.getOrElse(mmu.SV32).asidLen.W)
      val vpn = UInt(SV.getOrElse(mmu.SV32).vpnLen.W)
    }))) else None
    val l2tlbRsp = if(MMU_ENABLED) Some(Flipped(Vec(num_cache_in_sm, DecoupledIO(new Bundle{
      val ppn = UInt(SV.getOrElse(mmu.SV32).ppnLen.W)
      val flags = UInt(8.W)
    })))) else None
    val icache_invalidate = Input(Bool())
    //val inst_cnt = if(INST_CNT) Some(Output(UInt(32.W))) else None
    val inst_cnt2 = if(INST_CNT_2) Some(Output(Vec(2, UInt(32.W)))) else None
  })
  val cta2warp=Module(new CTA2warp)
  cta2warp.io.CTAreq<>io.CTAreq
  cta2warp.io.CTArsp<>io.CTArsp
  val pipe=Module(new pipe())
  pipe.sm_id := sm_id
  pipe.io.perfEnable := io.perfEnable
  pipe.io.perfReset := io.perfReset
  pipe.io.pc_reset:=true.B
  io.inst_cnt.foreach(_ := pipe.io.inst_cnt.getOrElse(0.U))
  io.inst_cnt2.foreach( _ := pipe.io.inst_cnt2.getOrElse(0.U))
  io.pipeline_perf.foreach(_ := pipe.io.perf_pipeline.getOrElse(0.U.asTypeOf(new PipelinePerfCounters)))
  io.inst_class_perf.foreach(_ := pipe.io.perf_inst_class.getOrElse(0.U.asTypeOf(new InstClassPerfCounters)))
  io.lsu_perf := pipe.io.perf_lsu
  val cnt=Counter(10)
  when(cnt.value<5.U){cnt.inc()}
  when(cnt.value===5.U){pipe.io.pc_reset:=false.B}
  pipe.io.warpReq<>cta2warp.io.warpReq
  pipe.io.warpRsp<>cta2warp.io.warpRsp
  pipe.io.wg_id_tag:=cta2warp.io.wg_id_tag
  cta2warp.io.wg_id_lookup:=pipe.io.wg_id_lookup
  val l1Cache2L2Arb = Module(new L1Cache2L2Arbiter()(param))
  io.memReq <> l1Cache2L2Arb.io.memReqOut
  l1Cache2L2Arb.io.memRspIn <> io.memRsp

  val icache = Module(new InstructionCache(SV)(param))
  icache.io.invalidate := io.icache_invalidate
  // **** icache memRsp ****
  icache.io.memRsp.valid := l1Cache2L2Arb.io.memRspVecOut(0).valid
  icache.io.memRsp.bits.d_addr := l1Cache2L2Arb.io.memRspVecOut(0).bits.d_addr
  icache.io.memRsp.bits.d_data := l1Cache2L2Arb.io.memRspVecOut(0).bits.d_data
  icache.io.memRsp.bits.d_source := l1Cache2L2Arb.io.memRspVecOut(0).bits.d_source
  l1Cache2L2Arb.io.memRspVecOut(0).ready := icache.io.memRsp.ready
  // ***********************
  // **** icache memReq ****
  l1Cache2L2Arb.io.memReqVecIn.get(0).valid := icache.io.memReq.valid
  l1Cache2L2Arb.io.memReqVecIn.get(0).bits.a_opcode := 4.U(3.W)
  //TODO changed to TLAOp_Get when L1param system established
  l1Cache2L2Arb.io.memReqVecIn.get(0).bits.a_addr.get := icache.io.memReq.bits.a_addr.get
  l1Cache2L2Arb.io.memReqVecIn.get(0).bits.a_source := icache.io.memReq.bits.a_source
  l1Cache2L2Arb.io.memReqVecIn.get(0).bits.a_data := 0.U.asTypeOf(Vec(dcache_BlockWords, UInt(xLen.W)))
  l1Cache2L2Arb.io.memReqVecIn.get(0).bits.a_mask.foreach{_ := true.B}
  l1Cache2L2Arb.io.memReqVecIn.get(0).bits.a_param := DontCare
  l1Cache2L2Arb.io.memReqVecIn.get(0).bits.spike_info.foreach { left => left := icache.io.memReq.bits.spike_info.getOrElse(0.U.asTypeOf(new cache_spike_info(mmu.SV32))) }
  icache.io.memReq.ready := l1Cache2L2Arb.io.memReqVecIn.get(0).ready
  // ***********************
  // **** icache coreReq ****
  pipe.io.icache_req.ready:=icache.io.coreReq.ready
  icache.io.coreReq.valid:=pipe.io.icache_req.valid
  icache.io.coreReq.bits.addr:=pipe.io.icache_req.bits.addr
  icache.io.coreReq.bits.warpid:=pipe.io.icache_req.bits.warpid
  icache.io.coreReq.bits.mask:=pipe.io.icache_req.bits.mask
  if(MMU_ENABLED){
    icache.io.coreReq.bits.asid.get := pipe.io.icache_req.bits.asid.get
  }

  icache.io.coreReq.bits.spike_info.foreach( _ := DontCare )
  // ***********************
  // **** icache coreRsp ****
  pipe.io.icache_rsp.valid:=icache.io.coreRsp.valid
  pipe.io.icache_rsp.bits.warpid:=icache.io.coreRsp.bits.warpid
  pipe.io.icache_rsp.bits.data:=icache.io.coreRsp.bits.data
  pipe.io.icache_rsp.bits.addr:=icache.io.coreRsp.bits.addr
  pipe.io.icache_rsp.bits.status:=icache.io.coreRsp.bits.status
  pipe.io.icache_rsp.bits.mask:=icache.io.coreRsp.bits.mask
  icache.io.coreRsp.ready:=pipe.io.icache_rsp.ready
  // ***********************
  icache.io.externalFlushPipe.bits.warpid :=pipe.io.externalFlushPipe.bits
  icache.io.externalFlushPipe.valid :=pipe.io.externalFlushPipe.valid

  val dcache = Module(new DataCachev2(SV)(param))
  // **** dcache memRsp ****
  dcache.io.memRsp.valid := l1Cache2L2Arb.io.memRspVecOut(1).valid
  dcache.io.memRsp.bits.d_source := l1Cache2L2Arb.io.memRspVecOut(1).bits.d_source
  dcache.io.memRsp.bits.d_addr := l1Cache2L2Arb.io.memRspVecOut(1).bits.d_addr
  dcache.io.memRsp.bits.d_data := l1Cache2L2Arb.io.memRspVecOut(1).bits.d_data
  dcache.io.memRsp.bits.d_opcode := l1Cache2L2Arb.io.memRspVecOut(1).bits.d_opcode
  dcache.io.memRsp.bits.d_param := l1Cache2L2Arb.io.memRspVecOut(1).bits.d_param
  l1Cache2L2Arb.io.memRspVecOut(1).ready := dcache.io.memRsp.ready
  // ***********************
  // **** dcache memReq ****
  l1Cache2L2Arb.io.memReqVecIn.get(1) <> dcache.io.memReq.get
  // **** dcache coreReq ****
  dcache.io.coreReq <> pipe.io.dcache_req
  // **** dcache coreRsp ****
  pipe.io.dcache_rsp.valid:=dcache.io.coreRsp.valid
  pipe.io.dcache_rsp.bits.instrId:=dcache.io.coreRsp.bits.instrId
  pipe.io.dcache_rsp.bits.data:=dcache.io.coreRsp.bits.data
  pipe.io.dcache_rsp.bits.activeMask:=dcache.io.coreRsp.bits.activeMask
  //pipe.io.dcache_rsp.bits.isWrite:=dcache.io.coreRsp.bits.isWrite
  dcache.io.coreRsp.ready:=pipe.io.dcache_rsp.ready
  dcache.io.perfEnable := io.perfEnable
  dcache.io.perfReset := io.perfReset
  io.dcache_perf := dcache.io.perf

  assert(num_cache_in_sm == 2, "Now only support 2 L1 Caches(one L1I and one L1D) in a single SM")
if(MMU_ENABLED) {
  val l1tlb: Seq[mmu.L1TlbIO] = SV match {
    case Some(sv) => Seq.fill(num_cache_in_sm)(Module(new L1TLB(sv, l1tlb_ways, Debug = true)))
    case None => Seq.fill(num_cache_in_sm)(Module(new L1TlbAutoReflect(mmu.SV32)))
  }
  // l1tlb <-> l2tlb
  SV match {
    case Some(sv) => {
     // l1tlb(0).io.l2_req <> io.l2tlbReq.get(0)
     // l1tlb(1).io.l2_req <> io.l2tlbReq.get(1)
     // l1tlb(0).io.l2_rsp <> io.l2tlbRsp.get(0)
     // l1tlb(1).io.l2_rsp <> io.l2tlbRsp.get(1)
      (l1tlb zip (io.l2tlbReq.get zip io.l2tlbRsp.get)).foreach { case (l1, (l2req, l2rsp)) =>
        l2req <> l1.io.l2_req
        l1.io.l2_rsp <> l2rsp
        // TODO: FIX
        l1.io.invalidate := 0.U.asTypeOf(l1.io.invalidate)
      }
    }
    case None => {
      l1tlb.foreach { l1 =>
        l1.io.l2_req <> DontCare
        l1.io.l2_rsp <> DontCare
        l1.io.invalidate := 0.U.asTypeOf(l1.io.invalidate)
      }
      io.l2tlbReq.get.foreach { l2 =>
        l2 <> DontCare
      }
      io.l2tlbRsp.get.foreach { l2 =>
        l2 <> DontCare
      }
    }
  }
  // l1i/l1d <-> l1itlb/l1dtlb
  SV match {
    case Some(sv) => {
      l1tlb(0).io.in <> icache.io.TLBReq.get
      icache.io.TLBRsp.get <> l1tlb(0).io.out
      l1tlb(1).io.in <> dcache.io.TLBReq.get
      dcache.io.TLBRsp.get <> l1tlb(1).io.out
    }
    case None => {

    }
  }
}


  val sharedmem = Module(new SharedMemory()(param))
  sharedmem.io.coreReq.bits.data:=pipe.io.shared_req.bits.data
  sharedmem.io.coreReq.bits.instrId:=pipe.io.shared_req.bits.instrId
  sharedmem.io.coreReq.bits.isWrite:=pipe.io.shared_req.bits.isWrite
  sharedmem.io.coreReq.bits.setIdx:=pipe.io.shared_req.bits.setIdx
  sharedmem.io.coreReq.bits.perLaneAddr:=pipe.io.shared_req.bits.perLaneAddr
  sharedmem.io.coreReq.valid:=pipe.io.shared_req.valid
  pipe.io.shared_req.ready:=sharedmem.io.coreReq.ready

  sharedmem.io.coreRsp.ready:=pipe.io.shared_rsp.ready
  pipe.io.shared_rsp.valid:=sharedmem.io.coreRsp.valid
  pipe.io.shared_rsp.bits.data:=sharedmem.io.coreRsp.bits.data
  pipe.io.shared_rsp.bits.instrId:=sharedmem.io.coreRsp.bits.instrId
  pipe.io.shared_rsp.bits.activeMask:=sharedmem.io.coreRsp.bits.activeMask
  // pipe.io.shared_rsp.bits.isWrite:=sharedmem.io.coreRsp.bits.isWrite
  
  if(GVM_ENABLED){
    val WF_ID_WIDTH = log2Ceil(num_warp_in_a_block)
    val gvm_cta2warp = Module(new GvmDutCta2Warp)
    // CTA 调度器向 SM 分配 warp 时，获取软硬件 warp 对应关系、寄存器起始地址、wg_slot id in warp_scheduler
    gvm_cta2warp.io.clock := clock
    gvm_cta2warp.io.reset := reset.asBool
    gvm_cta2warp.io.warp_req_fire := cta2warp.io.warpReq.fire
    gvm_cta2warp.io.software_wg_id := cta2warp.io.warpReq.bits.CTAdata.dispatch2cu_wg_id.pad(32)
    gvm_cta2warp.io.software_warp_id := cta2warp.io.warpReq.bits.CTAdata.dispatch2cu_wf_tag_dispatch(WF_ID_WIDTH-1,0).pad(32)
    gvm_cta2warp.io.sm_id := sm_id
    gvm_cta2warp.io.hardware_warp_id := cta2warp.io.warpReq.bits.wid.pad(32)
    gvm_cta2warp.io.sgpr_base := cta2warp.io.warpReq.bits.CTAdata.dispatch2cu_sgpr_base_dispatch.pad(32)
    gvm_cta2warp.io.vgpr_base := cta2warp.io.warpReq.bits.CTAdata.dispatch2cu_vgpr_base_dispatch.pad(32)
    gvm_cta2warp.io.wg_slot_id_in_warp_sche := cta2warp.io.warpReq.bits.CTAdata.dispatch2cu_wf_tag_dispatch(TAG_WIDTH-1, WF_ID_WIDTH)
    gvm_cta2warp.io.lds_base := Cat(
      LDS_BASE.U(32.W)(31, LDS_ID_WIDTH + 1),
      cta2warp.io.warpReq.bits.CTAdata.dispatch2cu_lds_base_dispatch
    )
    gvm_cta2warp.io.rtl_num_thread := cta2warp.io.warpReq.bits.CTAdata.dispatch2cu_wf_size_dispatch.pad(32)
  }
}


class SM2clusterArbiterIO(L2param: InclusiveCacheParameters_lite)(implicit p: Parameters) extends RVGBundle{
  val memReqVecIn = (Vec(NSmInCluster, Flipped(DecoupledIO(new L1CacheMemReqArb()))))
  val memReqOut = Decoupled(new TLBundleA_lite(L2param))
  val memRspIn = Flipped(Decoupled(new TLBundleD_lite_plus(L2param)))
  val memRspVecOut = Vec(NSmInCluster, DecoupledIO(new L1CacheMemRsp()))
}

class SM2clusterArbiter(L2param: InclusiveCacheParameters_lite)(implicit p: Parameters) extends RVGModule {
  val io = IO(new SM2clusterArbiterIO(L2param)(p))

  // **** memReq ****
  val memReqArb = Module(new Arbiter(new TLBundleA_lite(L2param),NSmInCluster))
  val memReqBuf = Module(new Queue(new TLBundleA_lite(L2param),2))
  //memReqArb.io.in <> io.memReqVecIn
  for(i <- 0 until NSmInCluster) {
    memReqArb.io.in(i).bits.opcode := io.memReqVecIn(i).bits.a_opcode
    if (NSmInCluster == 1) {
      memReqArb.io.in(i).bits.source := io.memReqVecIn(i).bits.a_source
    }
    else {
      memReqArb.io.in(i).bits.source := Cat(i.asUInt,io.memReqVecIn(i).bits.a_source)
    }
    memReqArb.io.in(i).bits.address := io.memReqVecIn(i).bits.a_addr.get
    memReqArb.io.in(i).bits.mask := (io.memReqVecIn(i).bits.a_mask).asUInt
    memReqArb.io.in(i).bits.data := io.memReqVecIn(i).bits.a_data.asUInt
    memReqArb.io.in(i).bits.size := 0.U//log2Up(BlockWords*BytesOfWord).U
    memReqArb.io.in(i).bits.spike_info.foreach { left => left := io.memReqVecIn(i).bits.spike_info.getOrElse(0.U.asTypeOf(new cache_spike_info(mmu.SV32))) }
    memReqArb.io.in(i).valid := io.memReqVecIn(i).valid
    io.memReqVecIn(i).ready:=memReqArb.io.in(i).ready
    memReqArb.io.in(i).bits.param := io.memReqVecIn(i).bits.a_param
  }
  memReqBuf.io.enq <> memReqArb.io.out
  io.memReqOut <> memReqBuf.io.deq
  // ****************

  // **** memRsp ****
  for(i <- 0 until NSmInCluster) {
    io.memRspVecOut(i).bits.d_data:=io.memRspIn.bits.data.asTypeOf(Vec(dcache_BlockWords,UInt(32.W)))
    io.memRspVecOut(i).bits.d_source:=io.memRspIn.bits.source
    io.memRspVecOut(i).bits.d_addr:=io.memRspIn.bits.address
    io.memRspVecOut(i).bits.d_opcode:= io.memRspIn.bits.opcode
    io.memRspVecOut(i).bits.d_param:= io.memRspIn.bits.param
    if(NSmInCluster == 1){
      io.memRspVecOut(i).valid := io.memRspIn.valid
    } else if(NSmInCluster == 2){
      io.memRspVecOut(i).valid := io.memRspIn.bits.source(log2Up(NSmInCluster)+log2Ceil(NCacheInSM)+l1cache_sourceBits-1)===i.asUInt && io.memRspIn.valid
    }
   // io.memRspVecOut(i).valid :=
    else {
      io.memRspVecOut(i).valid := io.memRspIn.bits.source(log2Up(NSmInCluster) + log2Ceil(NCacheInSM) + l1cache_sourceBits- 1, l1cache_sourceBits + log2Ceil(NCacheInSM)) === i.asUInt && io.memRspIn.valid
    }
  }
  if(NSmInCluster == 1){
    io.memRspIn.ready := io.memRspVecOut(0).ready
  } else if(NSmInCluster == 2){
    io.memRspIn.ready := Mux1H(UIntToOH(io.memRspIn.bits.source(log2Up(NSmInCluster) + log2Ceil(NCacheInSM) + l1cache_sourceBits - 1)),
      Reverse(Cat(io.memRspVecOut.map(_.ready))))
  } else {
    io.memRspIn.ready := Mux1H(UIntToOH(io.memRspIn.bits.source(log2Up(NSmInCluster) + log2Ceil(NCacheInSM) + l1cache_sourceBits - 1, l1cache_sourceBits + log2Up(NCacheInSM))),
      Reverse(Cat(io.memRspVecOut.map(_.ready)))) //TODO check order in test
  }
  // ****************
}
class l2DistributeIO(l2param: InclusiveCacheParameters_lite)(implicit p: Parameters) extends RVGBundle{
  val memReqIn = Flipped(Decoupled(new TLBundleA_lite(l2param)))
  val memReqVecOut = Vec(NL2Cache, Decoupled(new TLBundleA_lite(l2param)))
  val memRspVecIn = Flipped(Vec(NL2Cache, Decoupled(new TLBundleD_lite_plus(l2param))))
  val memRspOut = Decoupled(new TLBundleD_lite_plus(l2param))
}

class l2Distribute(l2param: InclusiveCacheParameters_lite)(implicit  p: Parameters) extends RVGModule{
  val io = IO(new l2DistributeIO(l2param)(p))

  val memRspArb = Module(new Arbiter(new TLBundleD_lite_plus(l2param),NL2Cache))
  for(i <- 0 until NL2Cache){
    io.memReqVecOut(i).bits := io.memReqIn.bits
    io.memReqVecOut(i).valid := io.memReqIn.valid && (i.asUInt === l2param.parseAddress(io.memReqIn.bits.address)._2)
    memRspArb.io.in(i) <> io.memRspVecIn(i)
  }
  io.memReqIn.ready := Mux1H(UIntToOH(l2param.parseAddress(io.memReqIn.bits.address)._2), Reverse(Cat(io.memReqVecOut.map(_.ready))))
  io.memRspOut <> memRspArb.io.out
}
class cluster2L2ArbiterIO(L2paramIn: InclusiveCacheParameters_lite,L2paramOut: InclusiveCacheParameters_lite)(implicit p: Parameters) extends RVGBundle{
  val memReqVecIn = Flipped(Vec(NCluster, Decoupled(new TLBundleA_lite(L2paramIn))))
  val memReqOut = Decoupled(new TLBundleA_lite(L2paramOut))
  val memRspIn = Flipped(Decoupled(new TLBundleD_lite_plus(L2paramOut)))
  val memRspVecOut = Vec(NCluster, Decoupled(new TLBundleD_lite_plus(L2paramIn)))
}

class cluster2L2Arbiter(L2paramIn: InclusiveCacheParameters_lite, L2paramOut: InclusiveCacheParameters_lite)(implicit p: Parameters) extends RVGModule {
  val io = IO(new cluster2L2ArbiterIO(L2paramIn, L2paramOut)(p))

  // **** memReq ****
  val memReqArb = Module(new Arbiter(new TLBundleA_lite(L2paramOut),NCluster))
  //memReqArb.io.in <> io.memReqVecIn
  for(i <- 0 until NCluster) {
    memReqArb.io.in(i).bits.opcode := io.memReqVecIn(i).bits.opcode
    if(NCluster == 1){
      memReqArb.io.in(i).bits.source := io.memReqVecIn(i).bits.source
    }
    else {
      memReqArb.io.in(i).bits.source := Cat(i.asUInt,io.memReqVecIn(i).bits.source)
    }
    memReqArb.io.in(i).bits.address := io.memReqVecIn(i).bits.address
    memReqArb.io.in(i).bits.param := io.memReqVecIn(i).bits.param
    memReqArb.io.in(i).bits.mask := (io.memReqVecIn(i).bits.mask).asUInt
    memReqArb.io.in(i).bits.data := io.memReqVecIn(i).bits.data.asUInt
    memReqArb.io.in(i).bits.size := 0.U//log2Up(BlockWords*BytesOfWord).U
    memReqArb.io.in(i).bits.spike_info.foreach { left => left := io.memReqVecIn(i).bits.spike_info.getOrElse(0.U.asTypeOf(new cache_spike_info(mmu.SV32))) }
    memReqArb.io.in(i).valid := io.memReqVecIn(i).valid
    io.memReqVecIn(i).ready:=memReqArb.io.in(i).ready
  }
  io.memReqOut <> memReqArb.io.out
  // ****************

  // **** memRsp ****
  for(i <- 0 until NCluster) {
    io.memRspVecOut(i).bits.size := io.memRspIn.bits.size
    io.memRspVecOut(i).bits.opcode := io.memRspIn.bits.opcode
    io.memRspVecOut(i).bits.param := io.memRspIn.bits.param
    io.memRspVecOut(i).bits.data :=io.memRspIn.bits.data//.asTypeOf(Vec(dcache_BlockWords,UInt(32.W)))
    io.memRspVecOut(i).bits.source:=io.memRspIn.bits.source(log2Ceil(NSmInCluster)+log2Ceil(NCacheInSM)+3+log2Up(dcache_MshrEntry)+log2Up(dcache_NSets)-1,0)
    io.memRspVecOut(i).bits.address:= io.memRspIn.bits.address
    if(NCluster == 1){
      io.memRspVecOut(i).valid := io.memRspIn.valid
    } else {
       io.memRspVecOut(i).valid :=
         io.memRspIn.bits.source(log2Ceil(NCluster) + log2Ceil(NSmInCluster) + log2Ceil(NCacheInSM) + 3+log2Up(dcache_MshrEntry)+log2Up(dcache_NSets) - 1, log2Ceil(NSmInCluster) + 3+log2Up(dcache_MshrEntry)+log2Up(dcache_NSets)+ log2Up(NCacheInSM)) === i.asUInt && io.memRspIn.valid
    }
  }
  if(NCluster == 1){
    io.memRspIn.ready := io.memRspVecOut(0).ready
  } else {
    io.memRspIn.ready := Mux1H(UIntToOH(io.memRspIn.bits.source(log2Ceil(NCluster) + log2Ceil(NSmInCluster) + log2Ceil(NCacheInSM) + l1cache_sourceBits - 1, log2Ceil(NSmInCluster) + l1cache_sourceBits + log2Up(NCacheInSM))),
      Reverse(Cat(io.memRspVecOut.map(_.ready)))) //TODO check order in test
  }
  // ****************
}
class TestCase(val name: String, inst: String, data: String, warp: Int, thread: Int, start_pc: Int, cycles: Int){
  //val props = ("./txt/" + name + "/" + inst, "./txt/" + name + "/" + data, warp, thread, start_pc))
  class Props{
    val inst_filepath = "./ventus/txt/" + name + "/" + inst
    val data_filepath = "./ventus/txt/" + name + "/" + data
    val num_warp = TestCase.this.warp
    val num_thread = TestCase.this.thread
    val start_pc = TestCase.this.start_pc
    val cycles = TestCase.this.cycles
  }
  val props = new Props
}
class CPUtest(C: TestCase#Props) extends Module{
  val io=IO(new Bundle{
    val host2cta=Decoupled(new host2CTA_data)
    val cta2host=Flipped(Decoupled(new CTA2host_data))
  })
  val num_of_block = 1.U
  io.host2cta.valid:=false.B
  io.host2cta.bits.host_wg_id:=0.U
  io.host2cta.bits.host_num_wf:=C.num_warp.U
  io.host2cta.bits.host_wf_size:=num_thread.asUInt
  io.host2cta.bits.host_start_pc:=0.U // start pc
  io.host2cta.bits.host_vgpr_size_total:= (C.num_warp*32).U
  io.host2cta.bits.host_sgpr_size_total:= (C.num_warp*32).U
  io.host2cta.bits.host_lds_size_total:= 128.U
  io.host2cta.bits.host_gds_size_total:= 128.U
  io.host2cta.bits.host_vgpr_size_per_wf:=32.U
  io.host2cta.bits.host_sgpr_size_per_wf:=32.U
  io.host2cta.bits.host_gds_baseaddr := sharemem_size.U
  io.host2cta.bits.host_pds_baseaddr := sharemem_size.U
  io.host2cta.bits.host_csr_knl:=10.U
  io.host2cta.bits.host_kernel_size_x := 0.U
  io.host2cta.bits.host_kernel_size_y := 0.U
  io.host2cta.bits.host_kernel_size_z := 0.U

  val cnt=Counter(16)
  io.host2cta.bits.host_wg_id:=Cat(cnt.value + 3.U,0.U(CU_ID_WIDTH.W))
  //io.host2cta.bits.host_pds_baseaddr:=cnt.value << 10
  io.host2cta.bits.host_csr_knl:=cnt.value
  io.host2cta.bits.host_wgIdx_x := cnt.value
  io.host2cta.bits.host_wgIdx_y := cnt.value + 1.U
  io.host2cta.bits.host_wgIdx_z := cnt.value + 2.U
  when(cnt.value < num_of_block){
    io.host2cta.valid:=true.B
    when(io.host2cta.ready){cnt.inc()}
  }
  io.cta2host.ready:=true.B
  when(io.cta2host.valid){
    printf(p"finish a wg ${io.cta2host.bits.inflight_wg_buffer_host_wf_done_wg_id}\n")
  }
  //io.cta2host<>DontCare
}
