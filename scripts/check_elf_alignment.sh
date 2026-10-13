#!/bin/sh
# 16 KB page-size gate for the built APK.
#
# Android 15+ ships devices whose kernel page size is 16 KB, and Android 17
# makes them common. bionic refuses to load a shared object whose smallest
# PT_LOAD p_align is below the system page size:
#
#   dlopen failed: ".../libFoo.so" program alignment (4096) cannot be
#   smaller than system page size (16384)
#
# twoyi loads libtwoyi.so from Renderer's static initializer, and libtwoyi.so
# DT_NEEDs the prebuilt libOpenglRender.so. One unaligned library therefore
# does not degrade the app, it kills it: UnsatisfiedLinkError at class-init,
# black screen, no container.
#
# The platform's 16 KB "backcompat" mode cannot rescue us. It only engages
# when *both* of these hold:
#   1. the app ships .so files with a 4 KB LOAD segment alignment, and
#   2. the APK stores those .so uncompressed and 4 KB zip-aligned.
# This app sets packaging.jniLibs.useLegacyPackaging=true, because the ROM's
# init execv's nativeLibraryDir/libloader.so and Installer execs libadb.so,
# so the libraries must be extracted to disk and are stored *compressed* in
# the APK. Condition 2 never holds. Alignment has to be fixed at link time.
#
# Remediation for the offending libraries: rebuild them with NDK r28+ (16 KB
# alignment is the default there) or, on NDK r26/r27, link with
#     -Wl,-z,max-page-size=16384
# and add APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true when using ndk-build.
#
# Usage: check_elf_alignment.sh <apk> [apk...]
# Exits non-zero if any 64-bit .so is under 16 KB aligned.

set -eu

TARGET_ALIGN=16384

command -v readelf >/dev/null 2>&1 || {
    echo "::error::readelf (binutils) is required by this check" >&2
    exit 2
}
command -v unzip >/dev/null 2>&1 || {
    echo "::error::unzip is required by this check" >&2
    exit 2
}

if [ $# -lt 1 ]; then
    echo "usage: $0 <apk> [apk...]" >&2
    exit 2
fi

# Smallest p_align across the PT_LOAD segments, or empty when the file has none.
min_load_align() {
    readelf -lW "$1" 2>/dev/null | awk '$1 == "LOAD" { print $NF }' |
    while read -r align; do
        # POSIX printf understands a 0x prefix for %d; mawk/gawk do not
        # agree on strtonum, so convert here rather than in awk.
        printf '%d\n' "$align" 2>/dev/null
    done | sort -n | head -1
}

# Bytes of a PT_LOAD segment that the kernel's RELRO mprotect() wrongly freezes.
#
# The kernel maps and protects memory in whole pages, so a RELRO whose end is
# not page-aligned has its protection rounded up to the next page boundary.
# That is only fatal when the segment RELRO protects really does have live
# data out there - bytes the dynamic linker or the app still expects to be
# writable, which now fault. Google's worked example is RELRO ending at E1000
# with the segment running on to E4000.
#
# A merely non-page-aligned RELRO end is normal and harmless, so this counts
# the actually-shadowed bytes instead of testing the remainder. Prints 0 when
# healthy.
relro_shadowed_bytes() {
    _so="$1"
    _page=$TARGET_ALIGN

    _rel="$(readelf -lW "$_so" 2>/dev/null | awk '$1 == "GNU_RELRO" { print $3, $6 }')"
    [ -n "$_rel" ] || { echo 0; return; }

    set -- $_rel
    _rlo=$(printf '%d' "$1")
    _rhi=$((_rlo + $(printf '%d' "$2")))
    # Where the kernel's mprotect actually stops.
    _rend=$(((_rhi + _page - 1) / _page * _page))

    # POSIX printf handles the 0x prefix that readelf emits; mawk and gawk do
    # not agree on strtonum, so every hex value is converted here, not in awk.
    while read -r _lo _hi; do
        [ -n "$_lo" ] || continue
        _lo=$(printf '%d' "$_lo")
        _hi=$((_lo + $(printf '%d' "$_hi")))
        # RELRO has to sit inside this segment for it to be the one protected.
        if [ "$_lo" -le "$_rlo" ] && [ "$_rhi" -le "$_hi" ]; then
            if [ "$_hi" -gt "$_rhi" ] && [ "$_rend" -gt "$_rhi" ]; then
                _s=$_rend
                [ "$_hi" -lt "$_s" ] && _s=$_hi
                echo $((_s - _rhi))
            else
                echo 0
            fi
            return
        fi
    done <<EOF
$(readelf -lW "$_so" 2>/dev/null | awk '$1 == "LOAD" { print $3, $6 }')
EOF
    echo 0
}

violations=0
relro_issues=0
checked=0

for apk in "$@"; do
    if [ ! -f "$apk" ]; then
        echo "::error::no such APK: $apk" >&2
        exit 2
    fi

    tmp="$(mktemp -d)"

    # 64-bit ABIs are the only ones the 16 KB requirement applies to.
    unzip -qq -o "$apk" 'lib/arm64*' 'lib/x86_64*' -d "$tmp" 2>/dev/null || true

    found="$(find "$tmp" -name '*.so' -type f | sort)"
    if [ -z "$found" ]; then
        echo "no 64-bit native libraries in $(basename "$apk")"
        rm -rf "$tmp"
        continue
    fi

    echo "== $(basename "$apk") =="
    for so in $found; do
        checked=$((checked + 1))
        rel="${so#"$tmp"/}"
        align="$(min_load_align "$so")"

        if [ -z "$align" ]; then
            echo "  $rel  no PT_LOAD segment (not an ELF?)"
            violations=$((violations + 1))
            continue
        fi

        if [ "$align" -lt "$TARGET_ALIGN" ]; then
            echo "  $rel  UNALIGNED  (${align} bytes, need ${TARGET_ALIGN})  <-- blocks 16 KB devices"
            violations=$((violations + 1))
        else
            echo "  $rel  ok  ($align bytes)"
        fi

        shadow="$(relro_shadowed_bytes "$so")"
        if [ "$shadow" -gt 0 ]; then
            echo "    RELRO  ${shadow} shadowed bytes: the end is not page-aligned so mprotect() rounds up and freezes live data -> startup SIGSEGV"
            relro_issues=$((relro_issues + 1))
        fi
    done

    rm -rf "$tmp"
done

echo
if [ "$relro_issues" -gt 0 ]; then
    echo "::error::${relro_issues} of ${checked} 64-bit native libraries have a RELRO region that is not 16 KB page-aligned. mprotect() rounds the end up to the next page and freezes live data, so those libraries SIGSEGV at startup on a 16 KB page-size device (Android 15+; standard on Android 17). Rebuild them with NDK r28+ or link with -Wl,-z,max-page-size=16384."
fi
if [ "$violations" -gt 0 ]; then
    echo "::error::${violations} of ${checked} 64-bit native libraries are not 16 KB aligned. bionic rejects them at dlopen() on a 16 KB page-size device (Android 15+; standard on Android 17), so the APK cannot even start there. Rebuild the offending prebuilts with NDK r28+ or link them with -Wl,-z,max-page-size=16384."
fi
if [ "$violations" -gt 0 ] || [ "$relro_issues" -gt 0 ]; then
    exit 1
fi

echo "all ${checked} 64-bit native libraries are 16 KB aligned"
