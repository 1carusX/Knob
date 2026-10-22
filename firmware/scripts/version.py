# PlatformIO 构建前脚本：生成固件版本号。
#
# 版本号格式：<VERSION 文件里的手写版本>+<最后一次改动 firmware/ 的提交>[.dirty.<改动指纹>]
#   例：0.1.0+4d778a4、0.1.0+4d778a4.dirty.1a2b3c4
#
# - 提交号只看 firmware/ 目录（git log -- .），只改插件/壳/文档的提交
#   不会让固件版本变，插件打包时也就不会因此给旋钮触发一次没必要的 OTA。
# - firmware/ 下有没提交的改动时加 .dirty.<指纹>，指纹是这些改动内容
#   （git diff + 未跟踪文件）的哈希：改了代码就一定变，同一份改动
#   重复构建则不变——不用构建时间，免得同样的代码每打一次包都升级一次。
#
# 产物（都在 .pio/build/<env>/ 下，不入库）：
#   generated/fw_version_gen.h  定义 FW_VERSION，内容不变就不重写，
#                               避免每次构建都让包含它的源文件重编
#   fw_version.txt              同一个版本号的纯文本，给插件打包任务读

import hashlib
import os
import subprocess

Import("env")  # noqa: F821 — PlatformIO/SCons 注入

project_dir = env.subst("$PROJECT_DIR")  # noqa: F821
build_dir = env.subst("$BUILD_DIR")  # noqa: F821


def git(*args):
    return subprocess.run(
        ["git", *args], cwd=project_dir, capture_output=True, check=True
    ).stdout


def compute_version():
    with open(os.path.join(project_dir, "VERSION"), encoding="utf-8") as f:
        base = f.read().strip()
    try:
        commit = git("log", "-1", "--format=%H", "--", ".").decode().strip()[:7] or "nocommit"
        status = git("status", "--porcelain", "--", ".").decode()
    except (OSError, subprocess.CalledProcessError):
        return f"{base}+nogit"
    if not status.strip():
        return f"{base}+{commit}"

    h = hashlib.sha1(git("diff", "HEAD", "--binary", "--", "."))
    untracked = git("ls-files", "--others", "--exclude-standard", "-z", "--", ".").split(b"\0")
    for path in sorted(p for p in untracked if p):
        h.update(path)
        with open(os.path.join(project_dir, path.decode()), "rb") as f:
            h.update(f.read())
    return f"{base}+{commit}.dirty.{h.hexdigest()[:7]}"


def write_if_changed(path, content):
    try:
        with open(path, encoding="utf-8") as f:
            if f.read() == content:
                return
    except FileNotFoundError:
        pass
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)


version = compute_version()
gen_dir = os.path.join(build_dir, "generated")
write_if_changed(
    os.path.join(gen_dir, "fw_version_gen.h"),
    "// 由 scripts/version.py 生成，不要手改\n"
    "#pragma once\n"
    f'#define FW_VERSION "{version}"\n',
)
write_if_changed(os.path.join(build_dir, "fw_version.txt"), version + "\n")
env.Append(CPPPATH=[gen_dir])  # noqa: F821
print(f"Firmware version: {version}")
