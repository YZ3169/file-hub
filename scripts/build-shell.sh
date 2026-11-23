#!/usr/bin/env bash
# 给 jpackage 的 app-image 套一层 WebView2 独立窗口外壳。
#
# 产出布局（安装目录 / 绿色版解压目录都一样）：
#   FileHub.exe         WebView2 外壳，快捷方式指向它
#   FileHubBackend.exe  jpackage 原启动器（改名后读 app/FileHubBackend.cfg）
#   app/                后端 jar 与两份 cfg
#   runtime/            自带 JRE
#   Microsoft.Web.WebView2.*.dll / WebView2Loader.dll
#
# 用法：
#   bash scripts/build-shell.sh [app-image 目录]
#
# 前置：Windows 自带的 .NET Framework 4.x csc.exe，以及 curl / PowerShell。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

IMAGE="${1:-$ROOT/build/desktop/dist/FileHub}"
if [ ! -f "$IMAGE/FileHub.exe" ]; then
    echo "找不到 app-image 启动器：$IMAGE/FileHub.exe" >&2
    exit 1
fi

WEBVIEW2_VERSION="1.0.4314.35"
WORK="$ROOT/build/shell"
CACHE="$WORK/cache"
PKG="$WORK/pkg"
NUPKG="$CACHE/microsoft.web.webview2.$WEBVIEW2_VERSION.nupkg"
mkdir -p "$CACHE" "$WORK"

echo "==> 准备 WebView2 SDK $WEBVIEW2_VERSION"
if [ ! -f "$NUPKG" ]; then
    curl -fL --retry 3 -o "$NUPKG" \
        "https://www.nuget.org/api/v2/package/Microsoft.Web.WebView2/$WEBVIEW2_VERSION"
fi
if [ ! -f "$PKG/lib/net462/Microsoft.Web.WebView2.WinForms.dll" ]; then
    rm -rf "$PKG"
    ZIP="$CACHE/webview2.zip"
    cp "$NUPKG" "$ZIP"
    powershell -NoProfile -Command \
        "Expand-Archive -LiteralPath '$(cygpath -w "$ZIP")' -DestinationPath '$(cygpath -w "$PKG")' -Force"
fi

CSC="/c/Windows/Microsoft.NET/Framework64/v4.0.30319/csc.exe"
if [ ! -x "$CSC" ] && [ -n "${WINDIR:-}" ]; then
    CSC="$(cygpath -u "$WINDIR")/Microsoft.NET/Framework64/v4.0.30319/csc.exe"
fi
if [ ! -x "$CSC" ]; then
    echo "找不到 .NET Framework 4.x 的 csc.exe（Windows 自带）。" >&2
    exit 1
fi

echo "==> 使用 FileHub 应用图标"
ICON="$ROOT/desktop-shell/FileHub.ico"
if [ ! -s "$ICON" ]; then
    echo "找不到应用图标：$ICON" >&2
    exit 1
fi

echo "==> 编译 WebView2 外壳"
RSP="$WORK/shell.rsp"
cat > "$RSP" <<EOF
-nologo
-target:winexe
-platform:x64
-codepage:65001
-out:$(cygpath -w "$WORK/FileHubShell.exe")
-win32manifest:$(cygpath -w "$ROOT/desktop-shell/app.manifest")
-win32icon:$(cygpath -w "$ICON")
-optimize+
-r:System.dll
-r:System.Core.dll
-r:System.Drawing.dll
-r:System.Windows.Forms.dll
-r:$(cygpath -w "$PKG/lib/net462/Microsoft.Web.WebView2.Core.dll")
-r:$(cygpath -w "$PKG/lib/net462/Microsoft.Web.WebView2.WinForms.dll")
$(cygpath -w "$ROOT/desktop-shell/FileHubShell.cs")
EOF
"$CSC" "@$(cygpath -w "$RSP")"

echo "==> 替换 app-image 启动器"
if [ ! -f "$IMAGE/FileHubBackend.exe" ]; then
    mv "$IMAGE/FileHub.exe" "$IMAGE/FileHubBackend.exe"
fi
# 后端启动器按自己的文件名找 cfg；app/FileHub.cfg 保留一份给 jpackage 校验安装包结构
cp "$IMAGE/app/FileHub.cfg" "$IMAGE/app/FileHubBackend.cfg"
cp "$WORK/FileHubShell.exe" "$IMAGE/FileHub.exe"
cp "$ROOT/desktop-shell/FileHubShell.exe.config" "$IMAGE/FileHub.exe.config"
cp "$PKG/lib/net462/Microsoft.Web.WebView2.Core.dll" "$IMAGE/"
cp "$PKG/lib/net462/Microsoft.Web.WebView2.WinForms.dll" "$IMAGE/"
cp "$PKG/runtimes/win-x64/native/WebView2Loader.dll" "$IMAGE/"

echo "    外壳：$IMAGE/FileHub.exe"
echo "    后端：$IMAGE/FileHubBackend.exe"
