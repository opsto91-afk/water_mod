#include "elf_analyzer.hpp"

#include <algorithm>
#include <cctype>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <elf.h>
#include <fcntl.h>
#include <sstream>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

namespace zaphre {
namespace {

struct MappedFile {
    int fd = -1;
    const uint8_t* data = nullptr;
    size_t size = 0;

    bool openRead(const std::string& path, std::string& err) {
        fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd < 0) {
            err = std::string("open failed: ") + std::strerror(errno);
            return false;
        }
        struct stat st {};
        if (::fstat(fd, &st) != 0 || st.st_size <= 0) {
            err = "empty or unreadable file";
            close();
            return false;
        }
        size = static_cast<size_t>(st.st_size);
        void* p = ::mmap(nullptr, size, PROT_READ, MAP_PRIVATE, fd, 0);
        if (p == MAP_FAILED) {
            err = std::string("mmap failed: ") + std::strerror(errno);
            data = nullptr;
            close();
            return false;
        }
        data = static_cast<const uint8_t*>(p);
        ::madvise(p, size, MADV_SEQUENTIAL);
        return true;
    }

    void close() {
        if (data && size > 0) {
            ::munmap(const_cast<uint8_t*>(data), size);
        }
        data = nullptr;
        size = 0;
        if (fd >= 0) {
            ::close(fd);
            fd = -1;
        }
    }

    ~MappedFile() { close(); }
};

struct LoadSeg {
    uint64_t vaddr;
    uint64_t offset;
    uint64_t filesz;
    uint64_t memsz;
    uint64_t align;
    uint32_t flags;
};

std::string jsonEscape(const std::string& s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (unsigned char c : s) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    std::snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out.push_back(static_cast<char>(c));
                }
        }
    }
    return out;
}

std::string hex64(uint64_t v) {
    char buf[24];
    std::snprintf(buf, sizeof(buf), "0x%llx", static_cast<unsigned long long>(v));
    return buf;
}

bool parseElf64Header(const MappedFile& mf, const Elf64_Ehdr*& eh, std::vector<LoadSeg>& segs,
                      const Elf64_Phdr*& dynPhdr, std::vector<const Elf64_Phdr*>& notePhdrs,
                      std::string& err) {
    if (mf.size < sizeof(Elf64_Ehdr)) {
        err = "file smaller than ELF64 header";
        return false;
    }
    eh = reinterpret_cast<const Elf64_Ehdr*>(mf.data);
    if (std::memcmp(eh->e_ident, ELFMAG, SELFMAG) != 0) {
        err = "invalid ELF magic";
        return false;
    }
    if (eh->e_ident[EI_CLASS] != ELFCLASS64 || eh->e_ident[EI_DATA] != ELFDATA2LSB) {
        err = "not a 64-bit little-endian ELF";
        return false;
    }
    if (eh->e_phentsize != sizeof(Elf64_Phdr) || eh->e_phnum == 0 ||
        eh->e_phoff + static_cast<uint64_t>(eh->e_phnum) * sizeof(Elf64_Phdr) > mf.size) {
        err = "invalid program header table";
        return false;
    }
    dynPhdr = nullptr;
    for (uint16_t i = 0; i < eh->e_phnum; ++i) {
        const auto* ph = reinterpret_cast<const Elf64_Phdr*>(mf.data + eh->e_phoff + i * sizeof(Elf64_Phdr));
        if (ph->p_type == PT_LOAD) {
            segs.push_back({ph->p_vaddr, ph->p_offset, ph->p_filesz, ph->p_memsz, ph->p_align, ph->p_flags});
        } else if (ph->p_type == PT_DYNAMIC) {
            dynPhdr = ph;
        } else if (ph->p_type == PT_NOTE) {
            notePhdrs.push_back(ph);
        }
    }
    return true;
}

bool vaToFileOffset(const std::vector<LoadSeg>& segs, size_t fileSize, uint64_t va, uint64_t& outOff) {
    for (const auto& s : segs) {
        if (va >= s.vaddr && va < s.vaddr + s.filesz) {
            uint64_t off = s.offset + (va - s.vaddr);
            if (off < fileSize) {
                outOff = off;
                return true;
            }
        }
    }
    return false;
}

bool fileOffsetToVa(const std::vector<LoadSeg>& segs, uint64_t off, uint64_t& outVa) {
    for (const auto& s : segs) {
        if (off >= s.offset && off < s.offset + s.filesz) {
            outVa = s.vaddr + (off - s.offset);
            return true;
        }
    }
    return false;
}

std::string toLower(std::string s) {
    for (char& c : s) {
        c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    }
    return s;
}

bool containsInsensitive(const char* hay, size_t hayLen, const std::string& needleLower) {
    if (needleLower.empty()) return true;
    if (hayLen < needleLower.size()) return false;
    const char first = needleLower[0];
    const size_t maxStart = hayLen - needleLower.size();
    for (size_t i = 0; i <= maxStart; ++i) {
        if (static_cast<char>(std::tolower(static_cast<unsigned char>(hay[i]))) == first) {
            bool ok = true;
            for (size_t j = 1; j < needleLower.size(); ++j) {
                if (static_cast<char>(std::tolower(static_cast<unsigned char>(hay[i + j]))) != needleLower[j]) {
                    ok = false;
                    break;
                }
            }
            if (ok) return true;
        }
    }
    return false;
}

// Compute symbol count from SYSV DT_HASH or DT_GNU_HASH
size_t estimateDynsymCount(const MappedFile& mf, const std::vector<LoadSeg>& segs,
                           uint64_t hashVa, uint64_t gnuHashVa, uint64_t symtabOff, uint64_t strtabOff) {
    uint64_t off = 0;
    if (hashVa != 0 && vaToFileOffset(segs, mf.size, hashVa, off) && off + 8 <= mf.size) {
        uint32_t nchain = 0;
        std::memcpy(&nchain, mf.data + off + 4, 4);
        if (nchain > 0 && nchain < 5000000) return nchain;
    }
    if (gnuHashVa != 0 && vaToFileOffset(segs, mf.size, gnuHashVa, off) && off + 16 <= mf.size) {
        uint32_t nbuckets = 0, symoffset = 0, bloomSize = 0;
        std::memcpy(&nbuckets, mf.data + off, 4);
        std::memcpy(&symoffset, mf.data + off + 4, 4);
        std::memcpy(&bloomSize, mf.data + off + 8, 4);
        uint64_t bucketsOff = off + 16 + static_cast<uint64_t>(bloomSize) * 8;
        if (nbuckets > 0 && nbuckets < 2000000 && bucketsOff + static_cast<uint64_t>(nbuckets) * 4 <= mf.size) {
            uint32_t maxSym = symoffset;
            for (uint32_t i = 0; i < nbuckets; ++i) {
                uint32_t b = 0;
                std::memcpy(&b, mf.data + bucketsOff + i * 4, 4);
                if (b > maxSym) maxSym = b;
            }
            if (maxSym >= symoffset) {
                uint64_t chainOff = bucketsOff + static_cast<uint64_t>(nbuckets) * 4 +
                                    static_cast<uint64_t>(maxSym - symoffset) * 4;
                while (chainOff + 4 <= mf.size && maxSym < 5000000) {
                    uint32_t h = 0;
                    std::memcpy(&h, mf.data + chainOff, 4);
                    ++maxSym;
                    if (h & 1u) break;
                    chainOff += 4;
                }
                return maxSym;
            }
        }
    }
    if (strtabOff > symtabOff) {
        size_t guess = static_cast<size_t>((strtabOff - symtabOff) / sizeof(Elf64_Sym));
        if (guess > 0 && guess < 5000000) return guess;
    }
    return 0;
}

// Parse simple Itanium RTTI typeinfo name (e.g. "11LiquidBlock" or "N9Minecraft6ServerE")
bool demangleRttiTypeName(const char* s, size_t len, std::string& outDemangled) {
    if (len < 2 || len > 256) return false;
    size_t pos = 0;
    bool nested = false;
    if (s[pos] == 'N') {
        nested = true;
        ++pos;
    } else if (!std::isdigit(static_cast<unsigned char>(s[pos]))) {
        return false;
    }

    int parts = 0;
    std::string result;
    while (pos < len) {
        if (nested && s[pos] == 'E') {
            ++pos;
            if (pos == len && parts >= 2) {
                outDemangled = result;
                return true;
            }
            return false;
        }
        if (!std::isdigit(static_cast<unsigned char>(s[pos]))) return false;
        size_t n = 0;
        int digits = 0;
        while (pos < len && std::isdigit(static_cast<unsigned char>(s[pos]))) {
            n = n * 10 + static_cast<size_t>(s[pos] - '0');
            ++pos;
            if (++digits > 3 || n > 120) return false;
        }
        if (n == 0 || pos + n > len) return false;
        // First char of identifier must be letter or '_'
        unsigned char first = static_cast<unsigned char>(s[pos]);
        if (!(std::isalpha(first) || first == '_')) return false;
        bool hasAlpha = false;
        for (size_t k = 0; k < n; ++k) {
            unsigned char c = static_cast<unsigned char>(s[pos + k]);
            if (!(std::isalnum(c) || c == '_')) return false;
            if (std::isalpha(c)) hasAlpha = true;
        }
        if (!hasAlpha) return false;
        if (!result.empty()) result += "::";
        result.append(s + pos, n);
        pos += n;
        ++parts;
        if (!nested) {
            if (pos == len && n >= 3) {
                outDemangled = result;
                return true;
            }
            return false;
        }
    }
    return false;
}

std::string extractBuildId(const MappedFile& mf, const std::vector<const Elf64_Phdr*>& notes) {
    for (const auto* ph : notes) {
        if (ph->p_offset + ph->p_filesz > mf.size || ph->p_filesz > 4096) continue;
        size_t p = static_cast<size_t>(ph->p_offset);
        size_t end = p + static_cast<size_t>(ph->p_filesz);
        while (p + 12 <= end) {
            uint32_t namesz = 0, descsz = 0, type = 0;
            std::memcpy(&namesz, mf.data + p, 4);
            std::memcpy(&descsz, mf.data + p + 4, 4);
            std::memcpy(&type, mf.data + p + 8, 4);
            p += 12;
            size_t nameAligned = (namesz + 3u) & ~3u;
            size_t descAligned = (descsz + 3u) & ~3u;
            if (p + nameAligned + descAligned > end) break;
            if (type == 3 /* NT_GNU_BUILD_ID */ && namesz == 4 &&
                std::memcmp(mf.data + p, "GNU\0", 4) == 0 && descsz > 0 && descsz <= 64) {
                const uint8_t* desc = mf.data + p + nameAligned;
                std::string hex;
                for (uint32_t i = 0; i < descsz; ++i) {
                    char b[4];
                    std::snprintf(b, sizeof(b), "%02x", desc[i]);
                    hex += b;
                }
                return hex;
            }
            p += nameAligned + descAligned;
        }
    }
    return "";
}

std::string annotateArm64(uint64_t va, uint32_t insn) {
    if (insn == 0xd65f03c0u) return "ret";
    if (insn == 0xd503201fu) return "nop";
    if ((insn & 0xfc000000u) == 0x94000000u || (insn & 0xfc000000u) == 0x14000000u) {
        int32_t imm = static_cast<int32_t>(insn & 0x03ffffffu);
        if (imm & (1 << 25)) imm -= (1 << 26);
        uint64_t dst = va + static_cast<int64_t>(imm) * 4;
        return ((insn & 0x80000000u) ? "bl " : "b ") + hex64(dst);
    }
    if ((insn & 0x9f000000u) == 0x90000000u) {
        int32_t immlo = static_cast<int32_t>((insn >> 29) & 3u);
        int32_t immhi = static_cast<int32_t>((insn >> 5) & 0x7ffffu);
        int32_t imm = (immhi << 2) | immlo;
        if (imm & (1 << 20)) imm -= (1 << 21);
        uint64_t page = (va & ~0xfffULL) + static_cast<int64_t>(imm) * 4096;
        return "adrp x" + std::to_string(insn & 31u) + ", " + hex64(page);
    }
    if ((insn & 0xff800000u) == 0x91000000u) {
        uint32_t imm12 = (insn >> 10) & 0xfffu;
        if ((insn >> 22) & 1u) imm12 <<= 12;
        return "add x" + std::to_string(insn & 31u) + ", x" + std::to_string((insn >> 5) & 31u) +
               ", #" + hex64(imm12);
    }
    if ((insn & 0xffe00000u) == 0x52800000u) {
        uint32_t imm16 = (insn >> 5) & 0xffffu;
        return "mov w" + std::to_string(insn & 31u) + ", #" + std::to_string(imm16);
    }
    if ((insn & 0xfffffc1fu) == 0xd63f0000u) {
        return "blr x" + std::to_string((insn >> 5) & 31u);
    }
    return "";
}

}  // namespace

std::string inspectElfJson(const std::string& path) {
    MappedFile mf;
    std::string err;
    if (!mf.openRead(path, err)) {
        return "{\"valid\":false,\"error\":\"" + jsonEscape(err) + "\"}";
    }
    const Elf64_Ehdr* eh = nullptr;
    std::vector<LoadSeg> segs;
    const Elf64_Phdr* dynPhdr = nullptr;
    std::vector<const Elf64_Phdr*> notes;
    if (!parseElf64Header(mf, eh, segs, dynPhdr, notes, err)) {
        return "{\"valid\":false,\"error\":\"" + jsonEscape(err) + "\"}";
    }

    uint64_t minAlign = 0;
    for (const auto& s : segs) {
        if (s.align > 0 && (minAlign == 0 || s.align < minAlign)) minAlign = s.align;
    }

    std::vector<std::string> needed;
    std::vector<std::string> exports;
    std::string soname;
    bool truncated = false;
    for (const auto& s : segs) {
        if (s.offset + s.filesz > mf.size) {
            truncated = true;
            break;
        }
    }

    if (dynPhdr && dynPhdr->p_offset + dynPhdr->p_filesz <= mf.size) {
        uint64_t strtabVa = 0, strsz = 0, symtabVa = 0, hashVa = 0, gnuHashVa = 0, sonameOff = 0;
        bool hasSoname = false;
        std::vector<uint64_t> neededOffs;
        size_t count = static_cast<size_t>(dynPhdr->p_filesz / sizeof(Elf64_Dyn));
        const auto* dyns = reinterpret_cast<const Elf64_Dyn*>(mf.data + dynPhdr->p_offset);
        for (size_t i = 0; i < count; ++i) {
            if (dyns[i].d_tag == DT_NULL) break;
            switch (dyns[i].d_tag) {
                case DT_STRTAB: strtabVa = dyns[i].d_un.d_ptr; break;
                case DT_STRSZ: strsz = dyns[i].d_un.d_val; break;
                case DT_SYMTAB: symtabVa = dyns[i].d_un.d_ptr; break;
                case DT_HASH: hashVa = dyns[i].d_un.d_ptr; break;
                case DT_GNU_HASH: gnuHashVa = dyns[i].d_un.d_ptr; break;
                case DT_NEEDED: neededOffs.push_back(dyns[i].d_un.d_val); break;
                case DT_SONAME: sonameOff = dyns[i].d_un.d_val; hasSoname = true; break;
                default: break;
            }
        }
        uint64_t strFileOff = 0;
        if (strtabVa != 0 && vaToFileOffset(segs, mf.size, strtabVa, strFileOff) && strFileOff < mf.size) {
            size_t avail = std::min<size_t>(static_cast<size_t>(strsz), mf.size - static_cast<size_t>(strFileOff));
            const char* strBase = reinterpret_cast<const char*>(mf.data + strFileOff);
            auto safeStr = [&](uint64_t o) -> std::string {
                if (o >= avail) return "";
                size_t maxLen = avail - static_cast<size_t>(o);
                size_t l = ::strnlen(strBase + o, maxLen);
                return std::string(strBase + o, l);
            };
            if (hasSoname) soname = safeStr(sonameOff);
            for (uint64_t no : neededOffs) {
                std::string n = safeStr(no);
                if (!n.empty()) needed.push_back(n);
            }
            uint64_t symFileOff = 0;
            if (symtabVa != 0 && vaToFileOffset(segs, mf.size, symtabVa, symFileOff)) {
                size_t nsyms = estimateDynsymCount(mf, segs, hashVa, gnuHashVa, symFileOff, strFileOff);
                for (size_t i = 1; i < nsyms && exports.size() < 40; ++i) {
                    uint64_t eo = symFileOff + i * sizeof(Elf64_Sym);
                    if (eo + sizeof(Elf64_Sym) > mf.size) break;
                    const auto* sym = reinterpret_cast<const Elf64_Sym*>(mf.data + eo);
                    if (sym->st_shndx != SHN_UNDEF && sym->st_name != 0 && sym->st_value != 0) {
                        std::string nm = safeStr(sym->st_name);
                        if (!nm.empty()) exports.push_back(nm + "@" + hex64(sym->st_value));
                    }
                }
            }
        }
    }

    std::string buildId = extractBuildId(mf, notes);
    std::ostringstream os;
    os << "{\"valid\":true"
       << ",\"isArm64\":" << (eh->e_machine == EM_AARCH64 ? "true" : "false")
       << ",\"machine\":" << eh->e_machine
       << ",\"fileSize\":" << mf.size
       << ",\"minPageAlign\":" << minAlign
       << ",\"page16kReady\":" << (minAlign >= 16384 ? "true" : "false")
       << ",\"truncated\":" << (truncated ? "true" : "false")
       << ",\"soname\":\"" << jsonEscape(soname) << "\""
       << ",\"buildId\":\"" << jsonEscape(buildId) << "\""
       << ",\"initArray0\":\"" << hex64(findFirstInitArrayVa(path)) << "\""
       << ",\"needed\":[";
    for (size_t i = 0; i < needed.size(); ++i) {
        if (i) os << ",";
        os << "\"" << jsonEscape(needed[i]) << "\"";
    }
    os << "],\"exports\":[";
    for (size_t i = 0; i < exports.size(); ++i) {
        if (i) os << ",";
        os << "\"" << jsonEscape(exports[i]) << "\"";
    }
    os << "],\"segments\":[";
    for (size_t i = 0; i < segs.size(); ++i) {
        if (i) os << ",";
        std::string fl;
        if (segs[i].flags & PF_R) fl += 'R';
        if (segs[i].flags & PF_W) fl += 'W';
        if (segs[i].flags & PF_X) fl += 'X';
        os << "{\"vaddr\":\"" << hex64(segs[i].vaddr)
           << "\",\"offset\":\"" << hex64(segs[i].offset)
           << "\",\"filesz\":\"" << hex64(segs[i].filesz)
           << "\",\"align\":" << segs[i].align
           << ",\"flags\":\"" << fl << "\"}";
    }
    os << "]}";
    return os.str();
}

uint64_t findFirstInitArrayVa(const std::string& path) {
    MappedFile mf;
    std::string err;
    if (!mf.openRead(path, err)) return 0;
    const Elf64_Ehdr* eh = nullptr;
    std::vector<LoadSeg> segs;
    const Elf64_Phdr* dynPhdr = nullptr;
    std::vector<const Elf64_Phdr*> notes;
    if (!parseElf64Header(mf, eh, segs, dynPhdr, notes, err) || !dynPhdr) return 0;
    if (dynPhdr->p_offset + dynPhdr->p_filesz > mf.size) return 0;

    uint64_t initArrayVa = 0, initArraySz = 0, relaVa = 0, relaSz = 0;
    size_t count = static_cast<size_t>(dynPhdr->p_filesz / sizeof(Elf64_Dyn));
    const auto* dyns = reinterpret_cast<const Elf64_Dyn*>(mf.data + dynPhdr->p_offset);
    for (size_t i = 0; i < count; ++i) {
        if (dyns[i].d_tag == DT_NULL) break;
        switch (dyns[i].d_tag) {
            case DT_INIT_ARRAY: initArrayVa = dyns[i].d_un.d_ptr; break;
            case DT_INIT_ARRAYSZ: initArraySz = dyns[i].d_un.d_val; break;
            case DT_RELA: relaVa = dyns[i].d_un.d_ptr; break;
            case DT_RELASZ: relaSz = dyns[i].d_un.d_val; break;
            default: break;
        }
    }
    if (initArrayVa == 0 || initArraySz < 8) return 0;

    // Check RELA entries first (Android AArch64 uses R_AARCH64_RELATIVE = 1027)
    uint64_t relaOff = 0;
    if (relaVa != 0 && relaSz >= sizeof(Elf64_Rela) && vaToFileOffset(segs, mf.size, relaVa, relaOff)) {
        size_t n = static_cast<size_t>(relaSz / sizeof(Elf64_Rela));
        for (size_t i = 0; i < n; ++i) {
            uint64_t eo = relaOff + i * sizeof(Elf64_Rela);
            if (eo + sizeof(Elf64_Rela) > mf.size) break;
            const auto* r = reinterpret_cast<const Elf64_Rela*>(mf.data + eo);
            if (r->r_offset == initArrayVa && ELF64_R_TYPE(r->r_info) == R_AARCH64_RELATIVE) {
                return static_cast<uint64_t>(r->r_addend);
            }
        }
    }
    uint64_t iaOff = 0;
    if (vaToFileOffset(segs, mf.size, initArrayVa, iaOff) && iaOff + 8 <= mf.size) {
        uint64_t val = 0;
        std::memcpy(&val, mf.data + iaOff, 8);
        return val;
    }
    return 0;
}

bool prepareZaphkielImage(const std::string& srcPath, const std::string& dstPath,
                          const std::string& oldPrefix, const std::string& newPrefix,
                          int& replacedCount, std::string& errorOut) {
    replacedCount = 0;
    MappedFile mf;
    if (!mf.openRead(srcPath, errorOut)) return false;

    const Elf64_Ehdr* eh = nullptr;
    std::vector<LoadSeg> segs;
    const Elf64_Phdr* dynPhdr = nullptr;
    std::vector<const Elf64_Phdr*> notes;
    if (!parseElf64Header(mf, eh, segs, dynPhdr, notes, errorOut)) return false;
    if (eh->e_machine != EM_AARCH64) {
        errorOut = "ELF is not arm64-v8a (EM_AARCH64)";
        return false;
    }

    std::vector<uint8_t> buf(mf.data, mf.data + mf.size);
    mf.close();

    if (!newPrefix.empty()) {
        if (oldPrefix.empty() || oldPrefix.size() != newPrefix.size()) {
            errorOut = "workspace redirect prefix must match exact 61-byte length";
            return false;
        }
        const size_t plen = oldPrefix.size();
        for (size_t i = 0; i + plen <= buf.size(); ) {
            if (std::memcmp(buf.data() + i, oldPrefix.data(), plen) == 0) {
                std::memcpy(buf.data() + i, newPrefix.data(), plen);
                ++replacedCount;
                i += plen;
            } else {
                ++i;
            }
        }
    }

    std::string tmpPath = dstPath + ".staging";
    ::unlink(tmpPath.c_str());
    int outFd = ::open(tmpPath.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (outFd < 0) {
        errorOut = std::string("failed to create staging file: ") + std::strerror(errno);
        return false;
    }
    size_t written = 0;
    while (written < buf.size()) {
        ssize_t n = ::write(outFd, buf.data() + written, buf.size() - written);
        if (n <= 0) {
            errorOut = std::string("write failed: ") + std::strerror(errno);
            ::close(outFd);
            ::unlink(tmpPath.c_str());
            return false;
        }
        written += static_cast<size_t>(n);
    }
    ::fsync(outFd);
    // Android 14+ (API 34+) requires dynamically loaded native libraries to be read-only.
    ::fchmod(outFd, 0444);
    ::close(outFd);

    // Unlink existing read-only target before atomic rename.
    ::chmod(dstPath.c_str(), 0600);
    ::unlink(dstPath.c_str());
    if (::rename(tmpPath.c_str(), dstPath.c_str()) != 0) {
        errorOut = std::string("rename failed: ") + std::strerror(errno);
        ::unlink(tmpPath.c_str());
        return false;
    }
    ::chmod(dstPath.c_str(), 0444);
    return true;
}

std::string searchElfStrings(const std::string& path, const std::string& query,
                             bool caseSensitive, int maxHits) {
    if (query.empty()) return "ERROR: query must not be empty";
    if (maxHits <= 0) maxHits = 50;
    if (maxHits > 500) maxHits = 500;

    MappedFile mf;
    std::string err;
    if (!mf.openRead(path, err)) return "ERROR: " + err;

    const Elf64_Ehdr* eh = nullptr;
    std::vector<LoadSeg> segs;
    const Elf64_Phdr* dynPhdr = nullptr;
    std::vector<const Elf64_Phdr*> notes;
    if (!parseElf64Header(mf, eh, segs, dynPhdr, notes, err)) return "ERROR: " + err;

    std::string qLower = toLower(query);
    std::ostringstream out;
    int hits = 0;
    size_t i = 0;
    while (i < mf.size && hits < maxHits) {
        if (mf.data[i] >= 0x20 && mf.data[i] < 0x7f) {
            size_t start = i;
            while (i < mf.size && mf.data[i] >= 0x20 && mf.data[i] < 0x7f) ++i;
            size_t len = i - start;
            if (len >= 4 && len >= query.size()) {
                const char* s = reinterpret_cast<const char*>(mf.data + start);
                bool matched = false;
                if (caseSensitive) {
                    matched = (::memmem(s, len, query.data(), query.size()) != nullptr);
                } else {
                    matched = containsInsensitive(s, len, qLower);
                }
                if (matched) {
                    uint64_t va = 0;
                    bool hasVa = fileOffsetToVa(segs, start, va);
                    size_t showLen = std::min<size_t>(len, 220);
                    out << (hasVa ? hex64(va) : "unmapped")
                        << " (off " << hex64(start) << "): \""
                        << std::string(s, showLen)
                        << (len > showLen ? "..." : "") << "\"\n";
                    ++hits;
                }
            }
        } else {
            ++i;
        }
    }
    return "Matches returned: " + std::to_string(hits) +
           (hits >= maxHits ? " (truncated at limit)\n" : "\n") + out.str();
}

std::string searchElfRtti(const std::string& path, const std::string& query, int maxHits) {
    if (maxHits <= 0) maxHits = 80;
    if (maxHits > 500) maxHits = 500;

    MappedFile mf;
    std::string err;
    if (!mf.openRead(path, err)) return "ERROR: " + err;

    const Elf64_Ehdr* eh = nullptr;
    std::vector<LoadSeg> segs;
    const Elf64_Phdr* dynPhdr = nullptr;
    std::vector<const Elf64_Phdr*> notes;
    if (!parseElf64Header(mf, eh, segs, dynPhdr, notes, err)) return "ERROR: " + err;

    std::string qLower = toLower(query);
    std::ostringstream out;
    int hits = 0;
    size_t i = 0;
    while (i < mf.size && hits < maxHits) {
        // Null-terminated ASCII candidates in .rodata / PT_LOAD
        if (mf.data[i] >= 0x20 && mf.data[i] < 0x7f) {
            size_t start = i;
            while (i < mf.size && mf.data[i] >= 0x20 && mf.data[i] < 0x7f) ++i;
            size_t len = i - start;
            bool nullTerminated = (i < mf.size && mf.data[i] == 0);
            bool prevNull = (start == 0 || mf.data[start - 1] == 0);
            if (nullTerminated && prevNull && len >= 4 && len <= 220) {
                const char* s = reinterpret_cast<const char*>(mf.data + start);
                if (qLower.empty() || containsInsensitive(s, len, qLower)) {
                    std::string demangled;
                    if (demangleRttiTypeName(s, len, demangled)) {
                        uint64_t va = 0;
                        fileOffsetToVa(segs, start, va);
                        out << hex64(va) << "  rtti=\"" << std::string(s, len)
                            << "\"  class=\"" << demangled << "\"\n";
                        ++hits;
                    }
                }
            }
        } else {
            ++i;
        }
    }
    return "RTTI typeinfo matches: " + std::to_string(hits) +
           (hits >= maxHits ? " (truncated at limit)\n" : "\n") + out.str();
}

std::string searchElfDynsym(const std::string& path, const std::string& query, int maxHits) {
    if (maxHits <= 0) maxHits = 80;
    if (maxHits > 500) maxHits = 500;

    MappedFile mf;
    std::string err;
    if (!mf.openRead(path, err)) return "ERROR: " + err;

    const Elf64_Ehdr* eh = nullptr;
    std::vector<LoadSeg> segs;
    const Elf64_Phdr* dynPhdr = nullptr;
    std::vector<const Elf64_Phdr*> notes;
    if (!parseElf64Header(mf, eh, segs, dynPhdr, notes, err) || !dynPhdr) {
        return "ERROR: missing PT_DYNAMIC (" + err + ")";
    }
    if (dynPhdr->p_offset + dynPhdr->p_filesz > mf.size) {
        return "ERROR: PT_DYNAMIC outside file bounds (truncated binary)";
    }

    uint64_t strtabVa = 0, strsz = 0, symtabVa = 0, hashVa = 0, gnuHashVa = 0;
    size_t dcount = static_cast<size_t>(dynPhdr->p_filesz / sizeof(Elf64_Dyn));
    const auto* dyns = reinterpret_cast<const Elf64_Dyn*>(mf.data + dynPhdr->p_offset);
    for (size_t i = 0; i < dcount; ++i) {
        if (dyns[i].d_tag == DT_NULL) break;
        switch (dyns[i].d_tag) {
            case DT_STRTAB: strtabVa = dyns[i].d_un.d_ptr; break;
            case DT_STRSZ: strsz = dyns[i].d_un.d_val; break;
            case DT_SYMTAB: symtabVa = dyns[i].d_un.d_ptr; break;
            case DT_HASH: hashVa = dyns[i].d_un.d_ptr; break;
            case DT_GNU_HASH: gnuHashVa = dyns[i].d_un.d_ptr; break;
            default: break;
        }
    }

    uint64_t strOff = 0, symOff = 0;
    if (!vaToFileOffset(segs, mf.size, strtabVa, strOff) ||
        !vaToFileOffset(segs, mf.size, symtabVa, symOff)) {
        return "ERROR: could not resolve DT_SYMTAB/DT_STRTAB";
    }
    size_t availStr = std::min<size_t>(static_cast<size_t>(strsz), mf.size - static_cast<size_t>(strOff));
    const char* strBase = reinterpret_cast<const char*>(mf.data + strOff);
    size_t nsyms = estimateDynsymCount(mf, segs, hashVa, gnuHashVa, symOff, strOff);
    std::string qLower = toLower(query);

    std::ostringstream out;
    int hits = 0;
    for (size_t i = 1; i < nsyms && hits < maxHits; ++i) {
        uint64_t eo = symOff + i * sizeof(Elf64_Sym);
        if (eo + sizeof(Elf64_Sym) > mf.size) break;
        const auto* sym = reinterpret_cast<const Elf64_Sym*>(mf.data + eo);
        if (sym->st_name == 0 || sym->st_name >= availStr) continue;
        const char* nm = strBase + sym->st_name;
        size_t nlen = ::strnlen(nm, availStr - sym->st_name);
        if (nlen == 0) continue;
        if (qLower.empty() || containsInsensitive(nm, nlen, qLower)) {
            out << (sym->st_shndx == SHN_UNDEF ? "IMPORT   " : hex64(sym->st_value))
                << "  sz=" << sym->st_size
                << "  " << std::string(nm, nlen) << "\n";
            ++hits;
        }
    }
    return "Dynsym scanned: " + std::to_string(nsyms) + ", matches: " + std::to_string(hits) + "\n" + out.str();
}

std::string scanElfSignature(const std::string& path, const std::string& idaPattern, int maxHits) {
    if (maxHits <= 0) maxHits = 32;
    if (maxHits > 256) maxHits = 256;

    std::vector<uint8_t> bytes;
    std::vector<uint8_t> mask;
    std::istringstream iss(idaPattern);
    std::string tok;
    int anchorIdx = -1;
    while (iss >> tok) {
        if (tok == "?" || tok == "??") {
            bytes.push_back(0);
            mask.push_back(0);
        } else {
            if (tok.size() != 2 || !std::isxdigit(static_cast<unsigned char>(tok[0])) ||
                !std::isxdigit(static_cast<unsigned char>(tok[1]))) {
                return "ERROR: invalid pattern token '" + tok + "' (expected hex byte AA or wildcard ?\\?)";
            }
            uint8_t val = static_cast<uint8_t>(std::strtoul(tok.c_str(), nullptr, 16));
            if (anchorIdx < 0 && val != 0x00 && val != 0xff) {
                anchorIdx = static_cast<int>(bytes.size());
            }
            bytes.push_back(val);
            mask.push_back(1);
        }
    }
    if (bytes.empty()) return "ERROR: empty pattern";
    if (anchorIdx < 0) {
        for (size_t i = 0; i < mask.size(); ++i) {
            if (mask[i]) {
                anchorIdx = static_cast<int>(i);
                break;
            }
        }
    }
    if (anchorIdx < 0) return "ERROR: pattern contains only wildcards";

    MappedFile mf;
    std::string err;
    if (!mf.openRead(path, err)) return "ERROR: " + err;

    const Elf64_Ehdr* eh = nullptr;
    std::vector<LoadSeg> segs;
    const Elf64_Phdr* dynPhdr = nullptr;
    std::vector<const Elf64_Phdr*> notes;
    if (!parseElf64Header(mf, eh, segs, dynPhdr, notes, err)) return "ERROR: " + err;

    const size_t plen = bytes.size();
    const uint8_t anchorByte = bytes[anchorIdx];
    size_t totalMatches = 0;
    std::vector<uint64_t> hitVas;

    for (const auto& s : segs) {
        if (!(s.flags & PF_X) || s.offset >= mf.size) continue;
        size_t segLen = static_cast<size_t>(std::min<uint64_t>(s.filesz, mf.size - s.offset));
        if (segLen < plen) continue;
        const uint8_t* base = mf.data + s.offset;
        size_t searchPos = static_cast<size_t>(anchorIdx);
        const size_t searchEnd = segLen - (plen - static_cast<size_t>(anchorIdx)) + 1;
        while (searchPos < searchEnd) {
            const void* found = std::memchr(base + searchPos, anchorByte, searchEnd - searchPos);
            if (!found) break;
            size_t idx = static_cast<size_t>(static_cast<const uint8_t*>(found) - base);
            size_t start = idx - static_cast<size_t>(anchorIdx);
            bool ok = true;
            for (size_t k = 0; k < plen; ++k) {
                if (mask[k] && base[start + k] != bytes[k]) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                ++totalMatches;
                if (static_cast<int>(hitVas.size()) < maxHits) {
                    hitVas.push_back(s.vaddr + start);
                }
            }
            searchPos = idx + 1;
        }
    }

    std::ostringstream out;
    out << "Pattern length: " << plen << " bytes | Total matches: " << totalMatches;
    if (totalMatches == 1) out << " [UNIQUE]\n";
    else if (totalMatches == 0) out << " [NOT FOUND]\n";
    else out << " [NOT UNIQUE]\n";
    for (uint64_t va : hitVas) {
        out << "  match @ " << hex64(va) << "\n";
    }
    return out.str();
}

std::string readElfVa(const std::string& path, uint64_t va, size_t byteCount) {
    if (byteCount == 0) byteCount = 64;
    if (byteCount > 2048) byteCount = 2048;

    MappedFile mf;
    std::string err;
    if (!mf.openRead(path, err)) return "ERROR: " + err;

    const Elf64_Ehdr* eh = nullptr;
    std::vector<LoadSeg> segs;
    const Elf64_Phdr* dynPhdr = nullptr;
    std::vector<const Elf64_Phdr*> notes;
    if (!parseElf64Header(mf, eh, segs, dynPhdr, notes, err)) return "ERROR: " + err;

    uint64_t off = 0;
    if (!vaToFileOffset(segs, mf.size, va, off)) {
        return "ERROR: VA " + hex64(va) + " not mapped in file bounds";
    }
    size_t avail = std::min<size_t>(byteCount, mf.size - static_cast<size_t>(off));
    std::ostringstream out;
    out << "VA " << hex64(va) << " (file offset " << hex64(off) << ", " << avail << " bytes):\n";
    for (size_t i = 0; i + 4 <= avail; i += 4) {
        uint32_t w = 0;
        std::memcpy(&w, mf.data + off + i, 4);
        uint64_t curVa = va + i;
        char line[96];
        std::snprintf(line, sizeof(line), "  0x%09llx:  %02x %02x %02x %02x  (%08x)",
                      static_cast<unsigned long long>(curVa),
                      mf.data[off + i], mf.data[off + i + 1],
                      mf.data[off + i + 2], mf.data[off + i + 3], w);
        out << line;
        std::string hint = annotateArm64(curVa, w);
        if (!hint.empty()) out << "  ; " << hint;
        out << "\n";
    }
    return out.str();
}

}  // namespace zaphre
