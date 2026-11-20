#!/usr/bin/env python3
"""
给 jpackage 生成的 MSI 做一点后处理：让用户在安装界面选中的目录是「上级目录」，
安装程序会自动在其中创建 FileHub 子文件夹（类似 QQ 选 D: 就装到 D:\\QQ）。

做法：
  1. jpackage 用 --temp 保留生成的 WiX 源码；
  2. 在 bundle.wxf 里把 INSTALLDIR(FileHub) 挂到一个新目录 INSTALLPARENT 下面，
     INSTALLPARENT 默认与 LocalAppDataFolder 同路径（DefaultDir="."），
     所以默认安装位置仍然是 %LOCALAPPDATA%\\FileHub，只有用户改路径时才会
     自动追加 FileHub；
  3. 把安装界面的路径框（WIXUI_INSTALLDIR）改成编辑 INSTALLPARENT；
  4. 给对应语言的 WXL 加三行文案，说明会自动创建子文件夹；
  5. 用原来的 candle/light 参数重新链接 MSI。

只在 Windows 上运行（依赖 WiX 3.x 和 Python 3）。
"""
from __future__ import annotations

import argparse
import ctypes
import re
import subprocess
import sys
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except AttributeError:  # pragma: no cover - Python < 3.7
    pass


UI_STRINGS = {
    "zh_CN": {
        "InstallDirDlgFolderLabel": "安装到（自动创建 {app} 子文件夹）：",
        "InstallDirDlgDescription": (
            "选择 {app} 的上级文件夹，安装程序会自动在其中创建 {app} 子文件夹。"
            "例如选择 D:\\ 将安装到 D:\\{app}。"
        ),
        "BrowseDlgDescription": (
            "选择 {app} 的上级文件夹，安装程序会自动在其中创建 {app} 子文件夹。"
        ),
    },
    "en": {
        "InstallDirDlgFolderLabel": "Install into (a {app} subfolder will be created):",
        "InstallDirDlgDescription": (
            "Select the parent folder for {app}. Setup will automatically create "
            "the {app} subfolder inside it. For example, selecting D:\\ installs "
            "to D:\\{app}."
        ),
        "BrowseDlgDescription": (
            "Select the parent folder for {app}. Setup will automatically create "
            "the {app} subfolder inside it."
        ),
    },
    "ja": {
        "InstallDirDlgFolderLabel": "インストール先（{app} サブフォルダーを自動作成）：",
        "InstallDirDlgDescription": (
            "{app} の親フォルダーを選択してください。セットアップは選択した"
            "フォルダー内に {app} サブフォルダーを自動作成します。"
            "例: D:\\ を選ぶと D:\\{app} にインストールされます。"
        ),
        "BrowseDlgDescription": (
            "{app} の親フォルダーを選択してください。セットアップは選択した"
            "フォルダー内に {app} サブフォルダーを自動作成します。"
        ),
    },
}

# Windows LANGID -> (WXL 文件名后缀, light -cultures)
LANGID_MAP = {
    0x0804: ("zh_CN", "zh-CN"),  # 简体中文
    0x0409: ("en", "en-US"),     # 英文
    0x0411: ("ja", "ja-JP"),     # 日文
}


def fail(msg: str) -> None:
    print(f"错误：{msg}", file=sys.stderr)
    sys.exit(1)


def write_text(path: Path, text: str) -> None:
    # newline="" 保持和 jpackage 生成文件一致的换行，避免混入 CRLF
    with open(path, "w", encoding="utf-8", newline="") as fh:
        fh.write(text)


def detect_locale() -> tuple[str, str]:
    try:
        langid = ctypes.windll.kernel32.GetUserDefaultUILanguage()
    except AttributeError:
        fail("当前不是 Windows，无法生成 MSI")
    if langid in LANGID_MAP:
        return LANGID_MAP[langid]

    primary = langid & 0x3FF
    if primary == 0x04:   # 其它中文（如繁体），jpackage 只有简体 WXL
        return "zh_CN", "zh-CN"
    if primary == 0x11:
        return "ja", "ja-JP"
    return "en", "en-US"


def patch_bundle(path: Path, app: str) -> None:
    text = path.read_text(encoding="utf-8")
    old = (
        '      <Directory Id="LocalAppDataFolder">\n'
        f'        <Directory Id="INSTALLDIR" Name="{app}"></Directory>\n'
        '      </Directory>'
    )
    new = (
        '      <Directory Id="LocalAppDataFolder">\n'
        '        <Directory Id="INSTALLPARENT" Name=".">\n'
        f'          <Directory Id="INSTALLDIR" Name="{app}"></Directory>\n'
        '        </Directory>\n'
        '      </Directory>'
    )
    if text.count(old) != 1:
        fail(
            "bundle.wxf 里没有找到预期的 INSTALLDIR 目录结构（jpackage 版本可能变了），"
            "请检查 config/bundle.wxf"
        )
    write_text(path, text.replace(old, new))


def patch_ui(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    old = '<Property Id="WIXUI_INSTALLDIR" Value="INSTALLDIR"></Property>'
    new = '<Property Id="WIXUI_INSTALLDIR" Value="INSTALLPARENT"></Property>'
    if text.count(old) != 1:
        fail("ui.wxf 里没有找到 WIXUI_INSTALLDIR=INSTALLDIR")
    write_text(path, text.replace(old, new))


def patch_wxl(path: Path, strings: dict[str, str]) -> None:
    text = path.read_text(encoding="utf-8")
    for key, value in strings.items():
        # 同名条目先删掉，避免 light 报重复定义
        text = re.sub(
            rf'[ \t]*<String Id="{re.escape(key)}">.*?</String>\r?\n?',
            "",
            text,
            flags=re.S,
        )
        entry = f'  <String Id="{key}">{value}</String>\n'
        if "</WixLocalization>" not in text:
            fail(f"{path.name} 不是合法的 WXL 文件")
        text = text.replace("</WixLocalization>", entry + "</WixLocalization>")
    write_text(path, text)


def run(args: list[str]) -> None:
    print("    " + " ".join(args))
    subprocess.run(args, check=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work", required=True, help="jpackage --temp 目录（Windows 路径）")
    parser.add_argument("--msi", required=True, help="要改写的 MSI（Windows 路径）")
    parser.add_argument("--wix-bin", required=True, help="candle.exe/light.exe 所在目录")
    parser.add_argument("--app-name", default="FileHub")
    args = parser.parse_args()

    work = Path(args.work)
    msi = Path(args.msi)
    wix_bin = Path(args.wix_bin)
    config = work / "config"
    wixobj = work / "wixobj"

    for p in (config / "bundle.wxf", config / "ui.wxf", msi, wix_bin / "candle.exe", wix_bin / "light.exe"):
        if not p.exists():
            fail(f"找不到 {p}（jpackage --temp 是否传对了？WiX 路径是否正确？）")

    lang_suffix, culture = detect_locale()
    wxl = config / f"MsiInstallerStrings_{lang_suffix}.wxl"
    if not wxl.exists():
        lang_suffix, culture = "en", "en-US"
        wxl = config / "MsiInstallerStrings_en.wxl"
    if not wxl.exists():
        fail("config 目录里没有可用的 MsiInstallerStrings_*.wxl")

    print(f"==> 安装界面语言：{culture}（{wxl.name}）")
    print("==> 改写 WiX 目录结构：INSTALLDIR 挂到 INSTALLPARENT 下")
    patch_bundle(config / "bundle.wxf", args.app_name)
    patch_ui(config / "ui.wxf")

    strings = {}
    for suffix, template in UI_STRINGS.items():
        path = config / f"MsiInstallerStrings_{suffix}.wxl"
        if path.exists():
            patch_wxl(path, {k: v.format(app=args.app_name) for k, v in template.items()})
            strings[suffix] = True
    print(f"==> 已补充界面文案：{', '.join(strings) or '无'}")

    candle = str(wix_bin / "candle.exe")
    light = str(wix_bin / "light.exe")
    print("==> 重新编译 WiX 片段")
    run([candle, "-nologo", str(config / "bundle.wxf"), "-ext", "WixUtilExtension",
         "-arch", "x64", "-out", str(wixobj / "bundle.wixobj")])
    run([candle, "-nologo", str(config / "ui.wxf"), "-ext", "WixUtilExtension",
         "-arch", "x64", "-out", str(wixobj / "ui.wixobj")])

    print("==> 重新链接 MSI（关闭 ICE64：INSTALLPARENT 只是 LocalAppData 的别名，不单独成目录）")
    run([
        light, "-nologo", "-spdb",
        "-ext", "WixUtilExtension",
        "-out", str(msi),
        "-ext", "WixUIExtension",
        "-b", str(config),
        "-sice:ICE27", "-sice:ICE91", "-sice:ICE64",
        "-loc", str(wxl),
        f"-cultures:{culture}",
        str(wixobj / "main.wixobj"),
        str(wixobj / "bundle.wixobj"),
        str(wixobj / "ui.wixobj"),
        str(wixobj / "InstallDirNotEmptyDlg.wixobj"),
    ])
    print(f"==> 完成：{msi}（选择 D:\\ 将安装到 D:\\{args.app_name}）")


if __name__ == "__main__":
    main()
