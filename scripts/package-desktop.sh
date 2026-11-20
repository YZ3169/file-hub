#!/usr/bin/env bash
# 构建 Windows 桌面版：
#   1) 前端产物打进 Spring Boot jar
#   2) jlink 生成精简 JRE
#   3) jpackage 生成免安装目录 build/desktop/dist/FileHub
#   4) 套上 WebView2 独立窗口外壳（scripts/build-shell.sh）
#   5) 如果找到 WiX 3.x，从改好的 app-image 生成 MSI
#      （按用户安装，不需要管理员权限，快捷方式指向外壳）
#
# 用法（Git Bash）：
#   bash scripts/package-desktop.sh
#
# 前置：
#   - JDK 17（JAVA_HOME；本机默认 D:/safe/jdk/jdk17）
#   - Node/npm、Maven
#   - Python 3（可选，只有生成 MSI 时用；用来做安装目录后处理）
#   - WiX Toolset 3.14（可选；没有就只出 app-image）
#     本机可把官方 wix314-binaries.zip 解压到 D:/Tools/wix314，
#     或用 WIX_BIN=/path/to/wix/bin 指定。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

APP_NAME="FileHub"

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/jpackage" ]; then
    if [ -x "/d/safe/jdk/jdk17/bin/jpackage" ]; then
        JAVA_HOME="/d/safe/jdk/jdk17"
    else
        echo "找不到 JDK 17 的 jpackage：请设置 JAVA_HOME" >&2
        exit 1
    fi
fi
export PATH="$JAVA_HOME/bin:$PATH"

# 版本跟 pom.xml 保持一致；发布 Tag 时由 CI 校验 tag 与它是否对应
APP_VERSION="$(mvn -B -q help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null | tr -d '\r' | tail -n1)"
APP_VERSION="${APP_VERSION:-1.0.0}"
if ! printf '%s' "$APP_VERSION" | grep -Eq '^[0-9]+(\.[0-9]+)*$'; then
    echo "无法从 pom.xml 读取合法版本号：$APP_VERSION" >&2
    exit 1
fi
# 固定 UUID：以后发布新版本时 MSI 才能走「升级」而不是并存两份
UPGRADE_UUID="6f1c2b9e-3a7d-4c5e-9b21-8d4f0a6c7e13"
OUT="$ROOT/build/desktop"
JAR="file-hub-${APP_VERSION}.jar"

echo "==> 1/5 构建前端"
(cd web && npm run build)

echo "==> 2/5 打包 Spring Boot jar"
mvn -B -q clean package -DskipTests

echo "==> 3/5 生成运行时与 app-image"
rm -rf "$OUT"
mkdir -p "$OUT/input" "$OUT/dist"
cp "target/$JAR" "$OUT/input/$JAR"

jlink \
    --add-modules ALL-MODULE-PATH \
    --strip-debug --no-header-files --no-man-pages --compress=2 \
    --output "$OUT/runtime"

jpackage \
    --type app-image \
    --name "$APP_NAME" \
    --app-version "$APP_VERSION" \
    --vendor "file-hub" \
    --description "分片上传 / 秒传 / 断点续传的本地文件服务" \
    --input "$OUT/input" \
    --main-jar "$JAR" \
    --main-class org.springframework.boot.loader.launch.JarLauncher \
    --runtime-image "$OUT/runtime" \
    --java-options "-Dfilehub.desktop=true" \
    --java-options "-Dfile.encoding=UTF-8" \
    --dest "$OUT/dist"

echo "==> 4/5 套上 WebView2 独立窗口外壳"
bash "$ROOT/scripts/build-shell.sh" "$OUT/dist/$APP_NAME"
echo "    免安装版：$OUT/dist/$APP_NAME/$APP_NAME.exe"

echo "==> 5/5 生成 MSI"
WIX_BIN="${WIX_BIN:-}"
if [ -z "$WIX_BIN" ]; then
    for candidate in "/c/Program Files (x86)/WiX Toolset v3.14/bin" "/d/Tools/wix314"; do
        if [ -x "$candidate/candle.exe" ]; then
            WIX_BIN="$candidate"
            break
        fi
    done
fi

if [ -z "$WIX_BIN" ]; then
    echo "未找到 WiX Toolset 3.x，跳过 MSI（免安装目录仍可运行）。" >&2
    echo "安装方式见 README，或设置 WIX_BIN 指向 candle.exe 所在目录。" >&2
    exit 0
fi
export PATH="$WIX_BIN:$PATH"

PYTHON_BIN="${PYTHON_BIN:-python}"
if ! command -v "$PYTHON_BIN" >/dev/null 2>&1; then
    echo "生成自动子文件夹版 MSI 需要 Python 3（找不到 $PYTHON_BIN）" >&2
    exit 1
fi

win_path() {
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -w "$1"
    else
        printf '%s' "$1"
    fi
}

MSI_WORK="$OUT/wix"
MSI_FILE="$OUT/dist/$APP_NAME-$APP_VERSION.msi"

# --temp 让 jpackage 保留生成的 WiX 源码，下面补一步：
# 选择 D:\ 时自动装到 D:\FileHub，而不是直接铺到 D:\ 根目录。
jpackage \
    --type msi \
    --name "$APP_NAME" \
    --app-version "$APP_VERSION" \
    --vendor "file-hub" \
    --description "分片上传 / 秒传 / 断点续传的本地文件服务" \
    --app-image "$OUT/dist/$APP_NAME" \
    --win-per-user-install \
    --win-shortcut \
    --win-menu \
    --win-menu-group "$APP_NAME" \
    --win-dir-chooser \
    --win-upgrade-uuid "$UPGRADE_UUID" \
    --temp "$MSI_WORK" \
    --dest "$OUT/dist"

echo "==> 5.1/5 让安装程序在所选目录下自动创建 $APP_NAME 子文件夹"
"$PYTHON_BIN" "$ROOT/scripts/patch-msi-install-parent.py" \
    --work "$(win_path "$MSI_WORK")" \
    --msi "$(win_path "$MSI_FILE")" \
    --wix-bin "$(win_path "$WIX_BIN")" \
    --app-name "$APP_NAME"

echo
echo "完成："
ls -lh "$OUT/dist"/*.msi "$OUT/dist/$APP_NAME/$APP_NAME.exe"
