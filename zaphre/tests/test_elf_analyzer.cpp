#include "../app/src/main/cpp/elf_analyzer.hpp"

#include <cassert>
#include <cstdio>
#include <cstring>
#include <iostream>
#include <sys/stat.h>
#include <unistd.h>

int main(int argc, char** argv) {
    // Test 1: Validate against a real AArch64 ELF if passed on CLI
    if (argc >= 2) {
        std::string elfPath = argv[1];
        std::string info = zaphre::inspectElfJson(elfPath);
        std::cout << "[inspect] " << info.substr(0, 320) << "...\n";
        assert(info.find("\"valid\":true") != std::string::npos);
        assert(info.find("\"isArm64\":true") != std::string::npos);

        uint64_t ia0 = zaphre::findFirstInitArrayVa(elfPath);
        std::cout << "[initArray0] 0x" << std::hex << ia0 << std::dec << "\n";

        std::string syms = zaphre::searchElfDynsym(elfPath, "zaphkiel", 10);
        std::cout << "[dynsym] " << syms;

        std::string strs = zaphre::searchElfStrings(elfPath, "run() complete", true, 10);
        std::cout << "[strings] " << strs;

        // Test 61-byte prefix staging + read-only guard
        const std::string kOld = "/storage/emulated/0/Android/media/org.levimc.launcher/kurumi/";
        const std::string kNew = "/storage/emulated/0/Android/media/org.levimc.zaphre/zaphkiel/";
        assert(kOld.size() == 61 && kNew.size() == 61);
        std::string dst = "/tmp/test_staged_zaphkiel.so";
        int replaced = 0;
        std::string err;
        bool ok = zaphre::prepareZaphkielImage(elfPath, dst, kOld, kNew, replaced, err);
        assert(ok && err.empty());
        std::cout << "[stage] replaced=" << replaced << "\n";

        struct stat st {};
        assert(::stat(dst.c_str(), &st) == 0);
        assert((st.st_mode & 0222) == 0);  // strictly read-only for Android 14+

        if (replaced > 0) {
            std::string checkNew = zaphre::searchElfStrings(dst, "org.levimc.zaphre/zaphkiel/", true, 20);
            std::string checkOld = zaphre::searchElfStrings(dst, "org.levimc.launcher/kurumi/", true, 20);
            assert(checkNew.find("org.levimc.zaphre/zaphkiel/") != std::string::npos);
            assert(checkOld.find("Matches returned: 0") != std::string::npos);
        }
        ::chmod(dst.c_str(), 0600);
        ::unlink(dst.c_str());

        // Test IDA signature scan on zaphkiel_init prologue if present
        if (syms.find("0x6d0e8") != std::string::npos) {
            std::string vaDump = zaphre::readElfVa(elfPath, 0x6d0e8, 36);
            std::cout << "[readVa]\n" << vaDump;
            std::string sigRes = zaphre::scanElfSignature(
                elfPath, "FF 83 00 D1 FD 7B 01 A9 FD 43 00 91 1F 20 03 D5 A2 47 FF 10", 10);
            std::cout << "[sigScan] " << sigRes;
            assert(sigRes.find("[UNIQUE]") != std::string::npos);
        }
    }

    // Test 2: Reject non-existent file gracefully
    std::string bad = zaphre::inspectElfJson("/tmp/definitely_missing_elf_12345.so");
    assert(bad.find("\"valid\":false") != std::string::npos);

    // Test 3: Optional MC ELF chunk check if provided as argv[2]
    if (argc >= 3) {
        std::string mcInfo = zaphre::inspectElfJson(argv[2]);
        std::cout << "[mcInspect] " << mcInfo.substr(0, 260) << "...\n";
        assert(mcInfo.find("\"valid\":true") != std::string::npos);
        assert(mcInfo.find("\"page16kReady\":true") != std::string::npos);
        std::string mcRtti = zaphre::searchElfRtti(argv[2], "Block", 5);
        std::cout << "[mcRtti] " << mcRtti;
    }

    std::cout << "ALL C++ ELF ANALYZER TESTS PASSED!\n";
    return 0;
}
