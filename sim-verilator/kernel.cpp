#include "kernel.hpp"
#include <algorithm>
#include <cassert>
#include <cstdint>
#include <fstream>
#include <iostream>
#include <memory>
#include <spdlog/logger.h>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

uint64_t divRoundUp(uint64_t value, uint64_t divisor) {
    if (divisor == 0) {
        throw std::runtime_error("invalid zero divisor for L1 partition calculation");
    }
    return value / divisor + (value % divisor != 0);
}

bool getRtlParam(const char* name, uint64_t& out) {
    uint32_t value = 0;
    if (ventus_rtlsim_get_parameter(name, &value) != 0 || value == 0) {
        return false;
    }
    out = value;
    return true;
}

uint64_t getRtlParamOrDefault(const char* name, uint64_t fallback) {
    uint64_t value = 0;
    return getRtlParam(name, value) ? value : fallback;
}

uint64_t chooseL1dBankCount(uint64_t maxL1dBanks, uint64_t minL1dBanks,
                            uint64_t l1dBankGranularity, uint64_t l1dMaxSets) {
    if (l1dBankGranularity == 0 || l1dMaxSets == 0) {
        return 0;
    }
    uint64_t best = 0;
    static constexpr uint64_t kLegalL1dBankCounts[] = {64, 128, 256, 512, 1024};
    for (uint64_t candidate : kLegalL1dBankCounts) {
        if (candidate < minL1dBanks || candidate > maxL1dBanks) {
            continue;
        }
        if (candidate % l1dBankGranularity != 0) {
            continue;
        }
        if (candidate / l1dBankGranularity > l1dMaxSets) {
            continue;
        }
        best = std::max(best, candidate);
    }
    return best;
}

void assignL1PartitionMetadata(metadata_t& metadata) {
    const uint64_t bankBytes = getRtlParamOrDefault("unified_l1_partition_bank_bytes", 128);
    uint64_t localMemSize = 128 * 1024;
    getRtlParam("sharemem_size", localMemSize);
    const uint64_t totalBanks =
        getRtlParamOrDefault("unified_l1_partition_banks", localMemSize / bankBytes + 64);
    const uint64_t minL1dBanks = getRtlParamOrDefault("unified_l1_min_l1d_banks", 64);
    const uint64_t l1dBankGranularity =
        getRtlParamOrDefault("unified_l1_l1d_bank_granularity", 2);
    const uint64_t l1dMaxSets = getRtlParamOrDefault("dcache_NSets_max", 512);
    const uint64_t maxWgSlotPerSm = getRtlParamOrDefault("num_block", 8);
    const uint64_t totalWarpsPerSm = getRtlParamOrDefault("num_warp", 8);
    const uint64_t totalSgpr =
        getRtlParamOrDefault("num_sgpr", totalWarpsPerSm * 256);
    const uint64_t totalVgpr =
        getRtlParamOrDefault("num_vgpr", totalWarpsPerSm * 128);

    metadata.ldsBankCount = divRoundUp(metadata.ldsSize, bankBytes);
    if (totalBanks <= minL1dBanks) {
        throw std::runtime_error("invalid unified L1 partition capacity");
    }
    if (metadata.ldsBankCount > totalBanks - minL1dBanks) {
        throw std::runtime_error("kernel LDS exceeds unified L1 SMEM capacity");
    }
    if (metadata.smemBankCountPerSm != 0) {
        return;
    }

    uint64_t residentWgPerSm = maxWgSlotPerSm;
    if (metadata.wg_size != 0) {
        residentWgPerSm = std::min(residentWgPerSm, totalWarpsPerSm / metadata.wg_size);
    }

    const uint64_t sgprPerWg = metadata.wg_size * metadata.sgprUsage;
    const uint64_t vgprPerWg = metadata.wg_size * metadata.vgprUsage;
    if (sgprPerWg != 0) {
        residentWgPerSm = std::min(residentWgPerSm, totalSgpr / sgprPerWg);
    }
    if (vgprPerWg != 0) {
        residentWgPerSm = std::min(residentWgPerSm, totalVgpr / vgprPerWg);
    }

    while (residentWgPerSm > 0) {
        const uint64_t requiredSmemBanks =
            divRoundUp(residentWgPerSm * metadata.ldsSize, bankBytes);
        if (requiredSmemBanks <= totalBanks) {
            const uint64_t l1dBanks =
                chooseL1dBankCount(totalBanks - requiredSmemBanks, minL1dBanks,
                                   l1dBankGranularity, l1dMaxSets);
            if (l1dBanks != 0) {
                metadata.smemBankCountPerSm = totalBanks - l1dBanks;
                return;
            }
        }
        residentWgPerSm--;
    }

    throw std::runtime_error("failed to choose unified L1 partition for kernel metadata");
}

} // namespace

static void increment_x_then_y_then_z(dim3_t& i, const dim3_t& bound) {
    i.x++;
    if (i.x >= bound.x) {
        i.x = 0;
        i.y++;
        if (i.y >= bound.y) {
            i.y = 0;
            if (i.z < bound.z)
                i.z++;
        }
    }
}

int Kernel::charToHex(char c) const {
    if (c >= '0' && c <= '9')
        return c - '0';
    else if (c >= 'A' && c <= 'F')
        return c - 'A' + 10;
    else if (c >= 'a' && c <= 'f')
        return c - 'a' + 10;
    else
        return -1; // Invalid character
}
bool Kernel::isHexCharacter(char c) const {
    return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f');
}
bool Kernel::no_more_wg_to_dispatch() const {
    return (m_next_wg.x >= m_grid_dim.x || m_next_wg.y >= m_grid_dim.y || m_next_wg.z >= m_grid_dim.z);
}
uint32_t Kernel::get_next_wg_idx_in_kernel() const {
    return m_next_wg.x + m_grid_dim.x * m_next_wg.y + m_grid_dim.x * m_grid_dim.y * m_next_wg.z;
}
uint32_t Kernel::get_next_wgid() const { return m_wgid_base + get_next_wg_idx_in_kernel(); }
void Kernel::wg_dispatched() {
    assert(is_dispatching());
    uint32_t idx = get_next_wg_idx_in_kernel();
    if (m_wg_status[idx] != WG_STATUS_WAITING) {
        logger->critical("Kernel {} WG {} is dispatched twice", m_kernel_name, idx);
    }
    assert(m_wg_status[idx] == WG_STATUS_WAITING);
    m_wg_status[idx] = WG_STATUS_RUNNING;
    increment_x_then_y_then_z(m_next_wg, m_grid_dim);
}

Kernel::Kernel(
    const std::string& kernel_name, const std::filesystem::path metadata_file, const std::filesystem::path data_file
)
    : m_datafile(data_file)
    , m_kernel_id(-1) // kernel_id will be assigned when kernel get activated
    , m_kernel_name(kernel_name)
    , m_load_data_callback(nullptr)
    , m_finish_callback(nullptr) {
    // Get metadata of this kernel
    initMetaData(metadata_file);
    // Init thread-block status record vector
    m_wg_status.resize(m_metadata.kernel_size[0] * m_metadata.kernel_size[1] * m_metadata.kernel_size[2]);
    std::fill(m_wg_status.begin(), m_wg_status.end(), WG_STATUS_WAITING);
    m_is_activated = false;
}

Kernel::Kernel(
    const metadata_t* metadata, std::function<void(const metadata_t*)> data_load_callback,
    std::function<void(const metadata_t*)> finish_callback, std::shared_ptr<spdlog::logger> logger_
)
    : m_kernel_name(metadata && metadata->name ? metadata->name : "unknown_kernel")
    , m_load_data_callback(data_load_callback)
    , m_finish_callback(finish_callback)
    , logger(logger_) {
    assert(metadata);
    assert(logger_);
    // copy metadata
    m_metadata = *metadata;
    // Init other members from metadata
    m_kernel_id = m_metadata.kernel_id;
    m_grid_dim.x = m_metadata.kernel_size[0];
    m_grid_dim.y = m_metadata.kernel_size[1];
    m_grid_dim.z = m_metadata.kernel_size[2];
    // Init thread-block status record vector
    m_wg_status.resize(m_metadata.kernel_size[0] * m_metadata.kernel_size[1] * m_metadata.kernel_size[2]);
    std::fill(m_wg_status.begin(), m_wg_status.end(), WG_STATUS_WAITING);
    m_is_activated = false;
}

void Kernel::readHexFile(const std::string& filename, std::vector<uint64_t>& items, int itemSize) const {
    // itemSize为每个数据的比特数，这里为64

    std::ifstream file(filename);

    if (!file) {
        std::cout << "Error opening file: " << filename << std::endl;
        return;
    }

    char c;
    int bits = 0;
    uint64_t value = 0;
    bool leftside = false;

    while (file.get(c)) {
        if (c == '\n') {
            if (bits != 0)
                leftside = true;
            continue;
        }

        if (!isHexCharacter(c)) {
            std::cout << "Invalid character found: " << c << " in " << filename << std::endl;
            continue;
        }

        int hexValue = charToHex(c);
        if (leftside)
            value = value | ((uint64_t)hexValue << (92 - bits));
        else
            value = (value << 4) | hexValue;
        bits += 4;

        if (bits >= itemSize) {
            items.push_back(value);
            value = 0;
            bits = 0;
            leftside = false;
        }
    }

    if (bits > 0) {
        std::cout << "Warning: Incomplete item found at the end of the file!" << std::endl;
    }

    file.close();
}

void Kernel::initMetaData(const std::string& filename) {
    std::vector<uint64_t> metadata;
    readHexFile(filename, metadata, 64);
    assignMetadata(metadata, m_metadata);
    m_metadata.name = m_kernel_name.c_str();
}

void Kernel::assignMetadata(const std::vector<uint64_t>& metadata, metadata_t& mtd) {
    int index = 0;

    mtd.startaddr = metadata[index++];

    mtd.kernel_id = metadata[index++];

    for (int i = 0; i < 3; i++) {
        mtd.kernel_size[i] = metadata[index++];
    }
    m_grid_dim.x = mtd.kernel_size[0];
    m_grid_dim.y = mtd.kernel_size[1];
    m_grid_dim.z = mtd.kernel_size[2];

    mtd.wf_size = metadata[index++];
    mtd.wg_size = metadata[index++];
    mtd.metaDataBaseAddr = metadata[index++];
    mtd.ldsSize = metadata[index++];
    mtd.pdsSize = metadata[index++];
    mtd.sgprUsage = metadata[index++];
    mtd.vgprUsage = metadata[index++];
    mtd.pdsBaseAddr = metadata[index++];
    assignL1PartitionMetadata(mtd);
    mtd.num_buffer = metadata[index++];

    mtd.buffer_base = new uint64_t[mtd.num_buffer];

    for (int i = 0; i < mtd.num_buffer; i++) {
        mtd.buffer_base[i] = metadata[index++];
    }

    mtd.buffer_size = new uint64_t[mtd.num_buffer];
    for (int i = 0; i < mtd.num_buffer; i++) {
        mtd.buffer_size[i] = metadata[index++];
    }

    mtd.buffer_allocsize = new uint64_t[mtd.num_buffer];
    for (int i = 0; i < mtd.num_buffer; i++) {
        mtd.buffer_allocsize[i] = metadata[index++];
    }
}

void Kernel::activate(uint32_t kernel_id, uint32_t wgid_base) {
    m_kernel_id = kernel_id;
    m_wgid_base = wgid_base;
    m_metadata.kernel_id = kernel_id;

    // If it's needed to load data before running kernel, do it
    if (m_load_data_callback)
        m_load_data_callback(&m_metadata);

    for (const auto wg_status : m_wg_status) {
        assert(wg_status == WG_STATUS_WAITING);
    }

    m_is_activated = true;
    logger->trace("kernel{0:>2} {1} activate", get_kid(), get_kname());
}
void Kernel::deactivate() {
    assert(is_activated());
    assert(!is_running());
    m_is_activated = false;
    if (m_finish_callback)
        m_finish_callback(&m_metadata);
}

bool Kernel::is_finished() const {
    for (const auto wg_status : m_wg_status) {
        if (wg_status != WG_STATUS_FINISHED)
            return false;
    }
    return true;
}
bool Kernel::is_running() const {
    if (!is_activated())
        return false;
    for (const auto wg_status : m_wg_status) {
        if (wg_status == WG_STATUS_RUNNING)
            return true;
    }
    return false;
}
bool Kernel::is_dispatching() const { return is_activated() && !no_more_wg_to_dispatch(); }

void Kernel::wg_finish(uint32_t wgid) {
    assert(is_wg_belonging(wgid));
    uint32_t idx = wgid - m_wgid_base;
    assert(m_wg_status[idx] == WG_STATUS_RUNNING);
    m_wg_status[idx] = WG_STATUS_FINISHED;
}

bool Kernel::is_wg_belonging(uint32_t wg_id, uint32_t* wg_idx_in_kernel) const {
    if (!is_running() || is_finished())
        return false;

    if (wg_id >= m_wgid_base && wg_id < m_wgid_base + get_num_wg()) {
        if (wg_idx_in_kernel) {
            *wg_idx_in_kernel = wg_id - m_wgid_base;
        }
        return true;
    } else {
        return false;
    }
}
