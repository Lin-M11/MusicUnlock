# Windows 单文件打包

Windows 发布产物为单个 `MusicUnlock-<version>-windows.exe`：内含应用本体与自带 JRE，
双击后由 7-Zip SFX 解压到系统临时目录并启动 `MusicUnlock.exe`，退出后自动清理临时文件，
因此分发时无需携带任何外部 dll / 资源目录。

## 组成

- `7zsd_All_x64.sfx`：7-Zip SFX 安装器模块（x64，来自 OlegScherbakov/7zSFX，LGPL），
  支持将 7z 数据段解压到临时目录、运行指定程序并在结束后清理。
- `sfx-config.txt`：SFX 配置（解压进度、启动 `MusicUnlock.exe`）。

CI 中 `build.yml` 会依次执行：`createDistributable` 生成应用目录 → 7-Zip 打成 `.7z`
→ 拼接 `sfx + config + .7z` 得到单文件 exe。
