param(
    [ValidateSet("All", "Windows", "Linux", "MacOS")]
    [string]$Target = "All"
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

$Sources = @(
    (Join-Path $ScriptDir "src/glide_spake2.cc"),
    (Join-Path $ScriptDir "boringssl/crypto/curve25519/spake25519.cc"),
    (Join-Path $ScriptDir "boringssl/crypto/curve25519/curve25519.cc")
)

$IncludePublic = Join-Path $ScriptDir "include"
$IncludeBssl = Join-Path $ScriptDir "boringssl/include"
$IncludeCrypto = Join-Path $ScriptDir "boringssl/crypto"

if ($Target -in @("All", "Windows") -and $IsWindows) {
    $OutDir = Join-Path $ScriptDir "runtimes/win-x64/native"
    New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
    $OutDll = Join-Path $OutDir "glide_spake2.dll"

    $VcVarsCandidates = @(
        "C:\Program Files\Microsoft Visual Studio\18\Community\VC\Auxiliary\Build\vcvars64.bat",
        "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat",
        "C:\Program Files\Microsoft Visual Studio\2022\Enterprise\VC\Auxiliary\Build\vcvars64.bat",
        "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
    )
    $VcVars = $VcVarsCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $VcVars) {
        throw "Could not find vcvars64.bat to compile glide_spake2.dll."
    }

    $TempObjDir = Join-Path ([System.IO.Path]::GetTempPath()) "glide_spake2_win_build"
    New-Item -ItemType Directory -Path $TempObjDir -Force | Out-Null

    $CmdLine = "call `"$VcVars`" >nul && cl.exe /nologo /O2 /EHsc /std:c++17 /MD /LD /DNDEBUG /DGLIDE_SPAKE2_BUILD_SHARED /DOPENSSL_NO_ASM /I`"$IncludePublic`" /I`"$IncludeBssl`" /I`"$IncludeCrypto`" `"$($Sources[0])`" `"$($Sources[1])`" `"$($Sources[2])`" /Fo`"$TempObjDir\\`" /Fe`"$OutDll`" bcrypt.lib"
    cmd.exe /c $CmdLine
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $OutDll)) {
        throw "Windows glide_spake2.dll build failed."
    }
    Copy-Item (Join-Path $ScriptDir "LICENSE") (Join-Path $OutDir "LICENSE.BoringSSL.txt") -Force
    Write-Host "Built Windows native library: $OutDll ($((Get-Item $OutDll).Length) bytes)" -ForegroundColor Green
}

if ($Target -in @("All", "Linux")) {
    $OutDir = Join-Path $ScriptDir "runtimes/linux-x64/native"
    New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
    $OutSo = Join-Path $OutDir "libglide_spake2.so"

    if (-not $IsWindows) {
        & g++ -O2 -std=c++17 -fPIC -shared -fvisibility=hidden -DNDEBUG -DGLIDE_SPAKE2_BUILD_SHARED -DOPENSSL_NO_ASM `
            -I"$IncludePublic" -I"$IncludeBssl" -I"$IncludeCrypto" `
            $Sources[0] $Sources[1] $Sources[2] -o "$OutSo"
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path $OutSo)) {
            throw "Linux libglide_spake2.so build failed."
        }
    } else {
        $LlvmBinCandidates = @(
            "C:\Program Files (x86)\Android\AndroidNDK\android-ndk-r27c\toolchains\llvm\prebuilt\windows-x86_64\bin",
            "C:\Users\GDJ\AppData\Local\Android\Sdk\ndk\26.1.10909125\toolchains\llvm\prebuilt\windows-x86_64\bin"
        )
        $LlvmBin = $LlvmBinCandidates | Where-Object { Test-Path (Join-Path $_ "clang++.exe") } | Select-Object -First 1
        if (-not $LlvmBin) {
            throw "Could not find LLVM clang++ to cross-compile libglide_spake2.so."
        }
        $Clang = Join-Path $LlvmBin "clang++.exe"
        $Lld = Join-Path $LlvmBin "ld.lld.exe"
        $Sysroot = Join-Path (Split-Path -Parent $LlvmBin) "sysroot"
        $ObjDir = Join-Path ([System.IO.Path]::GetTempPath()) "glide_spake2_linux_obj"
        New-Item -ItemType Directory -Path $ObjDir -Force | Out-Null

        $CommonFlags = @(
            "--target=x86_64-unknown-linux-gnu",
            "--sysroot=$Sysroot",
            "-isystem", "$Sysroot/usr/include/c++/v1",
            "-isystem", "$Sysroot/usr/include/x86_64-linux-android",
            "-isystem", "$Sysroot/usr/include",
            "-O2", "-std=c++17", "-fPIC", "-fno-exceptions", "-fno-rtti", "-fvisibility=hidden",
            "-DNDEBUG", "-DGLIDE_SPAKE2_BUILD_SHARED", "-DOPENSSL_NO_ASM",
            "-I$IncludePublic",
            "-I$IncludeBssl",
            "-I$IncludeCrypto"
        )

        & $Clang @CommonFlags -c $Sources[0] -o "$ObjDir/glide_spake2.o"
        if ($LASTEXITCODE -ne 0) { throw "Failed compiling glide_spake2.cc for Linux." }
        & $Clang @CommonFlags -c $Sources[1] -o "$ObjDir/spake25519.o"
        if ($LASTEXITCODE -ne 0) { throw "Failed compiling spake25519.cc for Linux." }
        & $Clang @CommonFlags -c $Sources[2] -o "$ObjDir/curve25519.o"
        if ($LASTEXITCODE -ne 0) { throw "Failed compiling curve25519.cc for Linux." }
        & $Lld -shared --allow-shlib-undefined "$ObjDir/glide_spake2.o" "$ObjDir/spake25519.o" "$ObjDir/curve25519.o" -soname libglide_spake2.so -o "$OutSo"
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path $OutSo)) {
            throw "Failed linking libglide_spake2.so."
        }
    }

    Copy-Item (Join-Path $ScriptDir "LICENSE") (Join-Path $OutDir "LICENSE.BoringSSL.txt") -Force
    Write-Host "Built Linux native library: $OutSo ($((Get-Item $OutSo).Length) bytes)" -ForegroundColor Green
}

if ($Target -in @("All", "MacOS") -and $IsMacOS) {
    $OutDir = Join-Path $ScriptDir "runtimes/osx/native"
    New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
    $OutDylib = Join-Path $OutDir "libglide_spake2.dylib"
    & clang++ -O2 -std=c++17 -fPIC -dynamiclib -fvisibility=hidden -DNDEBUG -DGLIDE_SPAKE2_BUILD_SHARED -DOPENSSL_NO_ASM `
        -I"$IncludePublic" -I"$IncludeBssl" -I"$IncludeCrypto" `
        $Sources[0] $Sources[1] $Sources[2] -o "$OutDylib"
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $OutDylib)) {
        throw "macOS libglide_spake2.dylib build failed."
    }
    Copy-Item (Join-Path $ScriptDir "LICENSE") (Join-Path $OutDir "LICENSE.BoringSSL.txt") -Force
    Write-Host "Built macOS native library: $OutDylib ($((Get-Item $OutDylib).Length) bytes)" -ForegroundColor Green
}
