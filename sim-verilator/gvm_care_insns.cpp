#include "gvm.hpp"
#include <cstdint>
#include <vector>

#define XREG_INSNS \
  {0x0000007f, 0x00000037, "LUI"                 },\
  {0x0000007f, 0x00000017, "AUIPC"               },\
  {0x0000007f, 0x0000006f, "JAL"                 },\
  {0x0000707f, 0x00000067, "JALR"                },\
  {0x0000707f, 0x00000013, "ADDI"                },\
  {0x0000707f, 0x00002013, "SLTI"                },\
  {0x0000707f, 0x00003013, "SLTIU"               },\
  {0x0000707f, 0x00004013, "XORI"                },\
  {0xfe00707f, 0x00001013, "SLLI"                },\
  {0xfe00707f, 0x00005013, "SRLI"                },\
  {0xfe00707f, 0x40005013, "SRAI"                },\
  {0xfe00707f, 0x00000033, "ADD"                 },\
  {0xfe00707f, 0x40000033, "SUB"                 },\
  {0xfe00707f, 0x00001033, "SLL"                 },\
  {0xfe00707f, 0x00002033, "SLT"                 },\
  {0xfe00707f, 0x00003033, "SLTU"                },\
  {0xfe00707f, 0x00004033, "XOR"                 },\
  {0xfe00707f, 0x00005033, "SRL"                 },\
  {0xfe00707f, 0x40005033, "SRA"                 },\
  {0xfe00707f, 0x00006033, "OR"                  },\
  {0xfe00707f, 0x00007033, "AND"                 },\
  {0xfe00707f, 0x02000033, "MUL"                 },\
  {0x8000707f, 0x00007057, "VSETVLI"             },\
  {0x0000707f, 0x00002073, "CSRRS"               },\
  {0x0000707f, 0x00006073, "CSRRSI"              },\
  {0x0000707f, 0x00001073, "CSRRW"               },\
  {0x0000707f, 0x00005073, "CSRRWI"              },\
  {0x0000707f, 0x00003073, "CSRRC"               },\
  {0x0000707f, 0x00007073, "CSRRCI"              },\
  {0x0000707f, 0x00002003, "LW"                  },\
  {0x0000707f, 0x0000305b, "SETRPC"              },

  #define VREG_INSNS \
  {0x0000007f, 0x0000000b, "VADD12.VI"          },\
  {0xfc0ff07f, 0x0800600b, "VFEXP"              },\
  {0xfc00707f, 0x0c00400b, "VFTTA.VV"           },\
  {0x0000707f, 0x0000207b, "VLW12.V"            },\
  {0x0000707f, 0x0000107b, "VLH12.V"            },\
  {0x0000707f, 0x0000007b, "VLB12.V"            },\
  {0x0000707f, 0x0000507b, "VLHU12.V"           },\
  {0x0000707f, 0x0000407b, "VLBU12.V"           },\
  {0x0000707f, 0x00002076, "VLW_GLOBAL.V"       },\
  {0x0000707f, 0x00001076, "VLH_GLOBAL.V"       },\
  {0x0000707f, 0x00000076, "VLB_GLOBAL.V"       },\
  {0x0000707f, 0x00005076, "VLHU_GLOBAL.V"      },\
  {0x0000707f, 0x00004076, "VLBU_GLOBAL.V"      },\
  {0x0000707f, 0x0000207a, "VLW_LOCAL.V"        },\
  {0x0000707f, 0x0000107a, "VLH_LOCAL.V"        },\
  {0x0000707f, 0x0000007a, "VLB_LOCAL.V"        },\
  {0x0000707f, 0x0000507a, "VLHU_LOCAL.V"       },\
  {0x0000707f, 0x0000407a, "VLBU_LOCAL.V"       },\
  {0x0000707f, 0x0000207e, "VLW_PRIVATE.V"      },\
  {0x0000707f, 0x0000107e, "VLH_PRIVATE.V"      },\
  {0x0000707f, 0x0000007e, "VLB_PRIVATE.V"      },\
  {0x0000707f, 0x0000507e, "VLHU_PRIVATE.V"     },\
  {0x0000707f, 0x0000407e, "VLBU_PRIVATE.V"     },\
  {0x0000707f, 0x0000202b, "VLW12D.V"           },\
  {0x0000707f, 0x0000102b, "VLH12D.V"           },\
  {0x0000707f, 0x0000002b, "VLB12D.V"           },\
  {0x0000707f, 0x0000502b, "VLHU12D.V"          },\
  {0x0000707f, 0x0000402b, "VLBU12D.V"          },\
  {0xfc00707f, 0x00000057, "VADD.VV"            },\
  {0xfc00707f, 0x00001057, "VFADD.VV"           },\
  {0xfc00707f, 0x00003057, "VADD.VI"            },\
  {0xfc00707f, 0x00004057, "VADD.VX"            },\
  {0xfc00707f, 0x00005057, "VFADD.VF"           },\
  {0xfc00707f, 0x08000057, "VSUB.VV"            },\
  {0xfc00707f, 0x08001057, "VFSUB.VV"           },\
  {0xfc00707f, 0x08004057, "VSUB.VX"            },\
  {0xfc00707f, 0x08005057, "VFSUB.VF"           },\
  {0xfc00707f, 0x0c003057, "VRSUB.VI"           },\
  {0xfc00707f, 0x0c004057, "VRSUB.VX"           },\
  {0xfc00707f, 0x10000057, "VMINU.VV"           },\
  {0xfc00707f, 0x10001057, "VFMIN.VV"           },\
  {0xfc00707f, 0x10004057, "VMINU.VX"           },\
  {0xfc00707f, 0x10005057, "VFMIN.VF"           },\
  {0xfc00707f, 0x14000057, "VMIN.VV"            },\
  {0xfc00707f, 0x14004057, "VMIN.VX"            },\
  {0xfc00707f, 0x18000057, "VMAXU.VV"           },\
  {0xfc00707f, 0x18001057, "VFMAX.VV"           },\
  {0xfc00707f, 0x18004057, "VMAXU.VX"           },\
  {0xfc00707f, 0x18005057, "VFMAX.VF"           },\
  {0xfc00707f, 0x1c000057, "VMAX.VV"            },\
  {0xfc00707f, 0x1c004057, "VMAX.VX"            },\
  {0xfc00707f, 0x20001057, "VFSGNJ.VV"          },\
  {0xfc00707f, 0x20005057, "VFSGNJ.VF"          },\
  {0xfc00707f, 0x24000057, "VAND.VV"            },\
  {0xfc00707f, 0x24001057, "VFSGNJN.VV"         },\
  {0xfc00707f, 0x24003057, "VAND.VI"            },\
  {0xfc00707f, 0x24004057, "VAND.VX"            },\
  {0xfc00707f, 0x24005057, "VFSGNJN.VF"         },\
  {0xfc00707f, 0x28000057, "VOR.VV"             },\
  {0xfc00707f, 0x28001057, "VFSGNJX.VV"         },\
  {0xfc00707f, 0x28003057, "VOR.VI"             },\
  {0xfc00707f, 0x28004057, "VOR.VX"             },\
  {0xfc00707f, 0x28005057, "VFSGNJX.VF"         },\
  {0xfc00707f, 0x2c000057, "VXOR.VV"            },\
  {0xfc00707f, 0x2c003057, "VXOR.VI"            },\
  {0xfc00707f, 0x2c004057, "VXOR.VX"            },\
  {0xfff0707f, 0x42006057, "VMV.S.X"            },\
  {0xfc0ff07f, 0x48001057, "VFCVT.XU.F.V"       },\
  {0xfc0ff07f, 0x48011057, "VFCVT.X.F.V"        },\
  {0xfc0ff07f, 0x48021057, "VFCVT.F.XU.V"       },\
  {0xfc0ff07f, 0x48031057, "VFCVT.F.X.V"        },\
  {0xfc0ff07f, 0x48061057, "VFCVT.RTZ.XU.F.V"   },\
  {0xfc0ff07f, 0x48071057, "VFCVT.RTZ.X.F.V"    },\
  {0xfc0ff07f, 0x4c081057, "VFCLASS.V"          },\
  {0xfe00707f, 0x5c000057, "VMERGE.VVM"         },\
  {0xfe00707f, 0x5c003057, "VMERGE.VIM"         },\
  {0xfe00707f, 0x5c004057, "VMERGE.VXM"         },\
  {0xfe00707f, 0x5c005057, "VFMERGE.VFM"        },\
  {0xfff0707f, 0x5d000057, "VMV.V.V"            },\
  {0xfff0707f, 0x5d003057, "VMV.V.I"            },\
  {0xfff0707f, 0x5d004057, "VMV.V.X"            },\
  {0xfff0707f, 0x5d005057, "VFMV.V.F"           },\
  {0xfc00707f, 0x60000057, "VMSEQ.VV"           },\
  {0xfc00707f, 0x60001057, "VMFEQ.VV"           },\
  {0xfc00707f, 0x60002057, "VMANDNOT.MM"        },\
  {0xfc00707f, 0x60003057, "VMSEQ.VI"           },\
  {0xfc00707f, 0x60004057, "VMSEQ.VX"           },\
  {0xfc00707f, 0x60005057, "VMFEQ.VF"           },\
  {0xfc00707f, 0x64000057, "VMSNE.VV"           },\
  {0xfc00707f, 0x64001057, "VMFLE.VV"           },\
  {0xfc00707f, 0x64002057, "VMAND.MM"           },\
  {0xfc00707f, 0x64003057, "VMSNE.VI"           },\
  {0xfc00707f, 0x64004057, "VMSNE.VX"           },\
  {0xfc00707f, 0x64005057, "VMFLE.VF"           },\
  {0xfc00707f, 0x68000057, "VMSLTU.VV"          },\
  {0xfc00707f, 0x68002057, "VMOR.MM"            },\
  {0xfc00707f, 0x68004057, "VMSLTU.VX"          },\
  {0xfc00707f, 0x6c000057, "VMSLT.VV"           },\
  {0xfc00707f, 0x6c001057, "VMFLT.VV"           },\
  {0xfc00707f, 0x6c002057, "VMXOR.MM"           },\
  {0xfc00707f, 0x6c004057, "VMSLT.VX"           },\
  {0xfc00707f, 0x6c005057, "VMFLT.VF"           },\
  {0xfc00707f, 0x70000057, "VMSLEU.VV"          },\
  {0xfc00707f, 0x70001057, "VMFNE.VV"           },\
  {0xfc00707f, 0x70002057, "VMORNOT.MM"         },\
  {0xfc00707f, 0x70003057, "VMSLEU.VI"          },\
  {0xfc00707f, 0x70004057, "VMSLEU.VX"          },\
  {0xfc00707f, 0x70005057, "VMFNE.VF"           },\
  {0xfc00707f, 0x74000057, "VMSLE.VV"           },\
  {0xfc00707f, 0x74002057, "VMNAND.MM"          },\
  {0xfc00707f, 0x74003057, "VMSLE.VI"           },\
  {0xfc00707f, 0x74004057, "VMSLE.VX"           },\
  {0xfc00707f, 0x74005057, "VMFGT.VF"           },\
  {0xfc00707f, 0x78002057, "VMNOR.MM"           },\
  {0xfc00707f, 0x78003057, "VMSGTU.VI"          },\
  {0xfc00707f, 0x78004057, "VMSGTU.VX"          },\
  {0xfc00707f, 0x7c002057, "VMXNOR.MM"          },\
  {0xfc00707f, 0x7c003057, "VMSGT.VI"           },\
  {0xfc00707f, 0x7c004057, "VMSGT.VX"           },\
  {0xfc00707f, 0x7c005057, "VMFGE.VF"           },\
  {0xfc00707f, 0x90001057, "VFMUL.VV"           },\
  {0xfc00707f, 0x90005057, "VFMUL.VF"           },\
  {0xfc00707f, 0x94000057, "VSLL.VV"            },\
  {0xfc00707f, 0x94003057, "VSLL.VI"            },\
  {0xfc00707f, 0x94004057, "VSLL.VX"            },\
  {0xfc00707f, 0x9c005057, "VFRSUB.VF"          },\
  {0xfc00707f, 0xa0000057, "VSRL.VV"            },\
  {0xfc00707f, 0xa0001057, "VFMADD.VV"          },\
  {0xfc00707f, 0xa0003057, "VSRL.VI"            },\
  {0xfc00707f, 0xa0004057, "VSRL.VX"            },\
  {0xfc00707f, 0xa0005057, "VFMADD.VF"          },\
  {0xfc00707f, 0xa4000057, "VSRA.VV"            },\
  {0xfc00707f, 0xa4001057, "VFNMADD.VV"         },\
  {0xfc00707f, 0xa4003057, "VSRA.VI"            },\
  {0xfc00707f, 0xa4004057, "VSRA.VX"            },\
  {0xfc00707f, 0xa4005057, "VFNMADD.VF"         },\
  {0xfc00707f, 0xa8001057, "VFMSUB.VV"          },\
  {0xfc00707f, 0xa8005057, "VFMSUB.VF"          },\
  {0xfc00707f, 0xac001057, "VFNMSUB.VV"         },\
  {0xfc00707f, 0xac005057, "VFNMSUB.VF"         },\
  {0xfc00707f, 0xb0001057, "VFMACC.VV"          },\
  {0xfc00707f, 0xb0005057, "VFMACC.VF"          },\
  {0xfc00707f, 0xb4001057, "VFNMACC.VV"         },\
  {0xfc00707f, 0xb4005057, "VFNMACC.VF"         },\
  {0xfc00707f, 0xb8001057, "VFMSAC.VV"          },\
  {0xfc00707f, 0xb8005057, "VFMSAC.VF"          },\
  {0xfc00707f, 0xbc001057, "VFNMSAC.VV"         },\
  {0xfc00707f, 0xbc005057, "VFNMSAC.VF"         },\
  {0xfdf0707f, 0x00006007, "VLE32.V"            },\
  {0xfc00707f, 0x0c006007, "VLOXEI32.V"         },

#define WARP_BARRIER_INSNS \
  {0x0000707f, 0x0000205b, "BARRIER"            }, \
  {0x0000707f, 0x0000305b, "BARRIERSUB"         }, \
  {0xffffffff, 0x0000205b, "JOIN"               }, \
  {0xfc00707f, 0x0000400b, "ENDPRG"             }, 

#define FP32_VREG_INSNS \
  {0xfc00707f, 0x00001057, "VFADD.VV"           }, \
  {0xfc00707f, 0x00005057, "VFADD.VF"           }, \
  {0xfc00707f, 0x08001057, "VFSUB.VV"           }, \
  {0xfc00707f, 0x08005057, "VFSUB.VF"           }, \
  {0xfc00707f, 0x9c005057, "VFRSUB.VF"          }, \
  {0xfc00707f, 0x10001057, "VFMIN.VV"           }, \
  {0xfc00707f, 0x10005057, "VFMIN.VF"           }, \
  {0xfc00707f, 0x18001057, "VFMAX.VV"           }, \
  {0xfc00707f, 0x18005057, "VFMAX.VF"           }, \
  {0xfc00707f, 0x20001057, "VFSGNJ.VV"          }, \
  {0xfc00707f, 0x20005057, "VFSGNJ.VF"          }, \
  {0xfc00707f, 0x24001057, "VFSGNJN.VV"         }, \
  {0xfc00707f, 0x24005057, "VFSGNJN.VF"         }, \
  {0xfc00707f, 0x28001057, "VFSGNJX.VV"         }, \
  {0xfc00707f, 0x28005057, "VFSGNJX.VF"         }, \
  {0xfc0ff07f, 0x48021057, "VFCVT.F.XU.V"       }, \
  {0xfc0ff07f, 0x48031057, "VFCVT.F.X.V"        }, \
  {0xfe00707f, 0x5c005057, "VFMERGE.VFM"        }, \
  {0xfff0707f, 0x5d005057, "VFMV.V.F"           }, \
  {0xfc00707f, 0x90001057, "VFMUL.VV"           }, \
  {0xfc00707f, 0x90005057, "VFMUL.VF"           }, \
  {0xfc00707f, 0xa0001057, "VFMADD.VV"          }, \
  {0xfc00707f, 0xa0005057, "VFMADD.VF"          }, \
  {0xfc00707f, 0xa4001057, "VFNMADD.VV"         }, \
  {0xfc00707f, 0xa4005057, "VFNMADD.VF"         }, \
  {0xfc00707f, 0xa8001057, "VFMSUB.VV"          }, \
  {0xfc00707f, 0xa8005057, "VFMSUB.VF"          }, \
  {0xfc00707f, 0xac001057, "VFNMSUB.VV"         }, \
  {0xfc00707f, 0xac005057, "VFNMSUB.VF"         }, \
  {0xfc00707f, 0xb0001057, "VFMACC.VV"          }, \
  {0xfc00707f, 0xb0005057, "VFMACC.VF"          }, \
  {0xfc00707f, 0xb4001057, "VFNMACC.VV"         }, \
  {0xfc00707f, 0xb4005057, "VFNMACC.VF"         }, \
  {0xfc00707f, 0xb8001057, "VFMSAC.VV"          }, \
  {0xfc00707f, 0xb8005057, "VFMSAC.VF"          }, \
  {0xfc00707f, 0xbc001057, "VFNMSAC.VV"         }, \
  {0xfc00707f, 0xbc005057, "VFNMSAC.VF"         }, \
  {0xfc0ff07f, 0x0800600b, "VFEXP"              }, \
  {0xfc00707f, 0x0c00400b, "VFTTA.VV"           }, \
  {0xfdf0707f, 0x00006007, "VLE32.V"            }, \
  {0xfc00707f, 0x0c006007, "VLOXEI32.V"         },

const std::vector<care_insn_t> gvm_t::retire_care_insns = {
  XREG_INSNS
  // WARP_BARRIER_INSNS
};
const std::vector<care_insn_t> gvm_t::single_insn_cmp_care_insns = {
  // XREG_INSNS
  VREG_INSNS
};
const std::vector<care_insn_t> gvm_t::fp32_vreg_insns = {
  FP32_VREG_INSNS
};
const std::vector<care_insn_t> gvm_t::disasm_table = {
  XREG_INSNS
  VREG_INSNS
  FP32_VREG_INSNS
  WARP_BARRIER_INSNS
};
