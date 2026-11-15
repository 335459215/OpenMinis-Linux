// FastCDC 风格 gear hash 分块器，给 minis-cas 做 C 加速
#include <stdint.h>
#include <stddef.h>
#include <stdlib.h>
#include <time.h>

static uint64_t GEAR[256];
static int inited = 0;

static void init_gear(void) {
    // 固定种子，保证跨设备/跨版本分块结果一致
    uint64_t s = 0x4D696E6973556C74ULL;
    for (int i = 0; i < 256; i++) {
        s ^= s >> 12; s ^= s << 25; s ^= s >> 27;
        GEAR[i] = s * 2685821657736338717ULL;
    }
    inited = 1;
}

// 返回切点数量；cuts[] 存每个块的长度（不是偏移），调用方按顺序消费
size_t cas_cdc(const unsigned char *data, size_t len, size_t min_sz,
               int mask_bits, size_t max_sz, size_t *cuts, size_t max_cuts) {
    if (!inited) init_gear();
    const uint64_t mask = (1ULL << mask_bits) - 1ULL;
    size_t n = 0, start = 0;
    uint64_t h = 0;
    for (size_t i = 0; i < len; i++) {
        h = (h << 1) + GEAR[data[i]];
        size_t clen = i - start + 1;
        if ((clen >= min_sz && (h & mask) == 0) || clen >= max_sz) {
            if (n >= max_cuts) return n;
            cuts[n++] = clen;
            start = i + 1;
            h = 0;
        }
    }
    if (start < len && n < max_cuts) cuts[n++] = len - start;
    return n;
}
