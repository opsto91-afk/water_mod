#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

namespace zaphre {

// Inspect an AArch64 ELF64 shared library and return JSON metadata
// (validity, fileSize, minAlign, buildId, DT_NEEDED list, PT_LOAD segments, top exports).
std::string inspectElfJson(const std::string& path);

// Stage libZaphkiel.so (even if saved as .so.txt) into a read-only executable-ready path.
// If newPrefix is non-empty, it must have the exact same byte length as oldPrefix (61 bytes)
// and replaces every occurrence inside the staged copy.
bool prepareZaphkielImage(
    const std::string& srcPath,
    const std::string& dstPath,
    const std::string& oldPrefix,
    const std::string& newPrefix,
    int& replacedCount,
    std::string& errorOut
);

// Search printable ASCII strings inside an ELF64 file and report VA + file offset.
std::string searchElfStrings(
    const std::string& path,
    const std::string& query,
    bool caseSensitive,
    int maxHits
);

// Search C++ Itanium RTTI typeinfo strings (printable mangled class names) inside .rodata.
std::string searchElfRtti(
    const std::string& path,
    const std::string& query,
    int maxHits
);

// Search dynamic symbol table (.dynsym) entries by substring.
std::string searchElfDynsym(
    const std::string& path,
    const std::string& query,
    int maxHits
);

// Scan executable PT_LOAD segments for an IDA-style byte pattern ("AA ?? BB CC").
std::string scanElfSignature(
    const std::string& path,
    const std::string& idaPattern,
    int maxHits
);

// Read bytes / ARM64 32-bit instructions at a given ELF virtual address.
std::string readElfVa(
    const std::string& path,
    uint64_t va,
    size_t byteCount
);

// Read INIT_ARRAY[0] file virtual address from an ELF64 binary (0 if absent).
uint64_t findFirstInitArrayVa(const std::string& path);

}  // namespace zaphre
