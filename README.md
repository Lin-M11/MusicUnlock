# MusicUnlock 多平台加密音乐格式转换工具

基于 **Kotlin + Compose Multiplatform** 的桌面工具,将网易云/QQ音乐/酷狗/酷我等平台的加密音乐格式转换为标准音频格式(mp3/flac/ogg/m4a 等),带现代桌面 GUI 与命令行批量转换。

> **仅供学习与技术交流,请勿用于商业用途,请在合法范围内使用。**

## 技术栈

- Kotlin 2.1 + Compose Multiplatform 1.7(桌面)
- Material3 主题(深/浅双模式,语义色板)
- Gson(JSON 解析)、jaudiotagger(音频标签/封面写回)
- Gradle + jpackage 原生打包(macOS .dmg/.pkg、Windows .msi/.exe、Linux .deb/.rpm)

## 支持格式

| 平台 | 扩展名 |
|---|---|
| 网易云音乐 | `ncm` |
| QQ音乐 | `qmc0` `qmc2` `qmc3` `qmc4` `qmc6` `qmc8` `qmcflac` `qmcogg` `tkm` `mflac` `mflac0` `mflac1` `mflac2` `mgg` `mgg0` `mgg1` `mgg2` `mggl` `mmp4` `bkcmp3` `bkcm4a` `bkcflac` `bkcwav` `bkcape` `bkcogg` `bkcwma` |
| 酷狗音乐 | `kgm` `kgma` `vpr` |
| 酷我音乐 | `kwm` |

转换时自动识别解密后音频的真实格式(MP3/FLAC/OGG/M4A/WAV 等),并保留音频内嵌的标签与封面;NCM 还会额外写回容器里的歌名/歌手/专辑/封面。

## 快速开始

需要 JDK 17+:

```bash
./gradlew run              # 启动桌面 GUI
./gradlew run --args="-h"  # 命令行帮助
./gradlew test             # 运行测试(23 个,含 unlock-music 真实样本向量)
```

### 命令行

```
./gradlew run --args="-c ~/Music/网易云 -o ~/Music/转换结果 -d"
```

| 参数 | 说明 |
|---|---|
| `-c, --convert [path] ...` | 转换路径下的所有加密音乐文件(支持文件或文件夹,可多个) |
| `-o, --output [dir]` | 自定义输出目录(默认 `./output`) |
| `-d, --dedup` | 按解密后音频内容去重,优先保留不带 `(N)` 后缀的文件 |
| `-v, --view` | 打开图形界面(不带参数时默认打开) |
| `-h, --help` | 帮助 |

> 注意:去重是对**解密后音频**做 SHA-256,而不是对加密文件做哈希——同名歌曲即使加密文件不同(元数据/歌曲 ID 不同),解密后音频相同也能正确去重。

### 图形界面

- 深/浅双主题,一键切换
- 拖拽文件/文件夹添加,或点击选择
- 自定义输出目录 + 一键打开输出目录
- 每文件状态与整体进度反馈

## 原生打包(需在对应操作系统上执行)

```bash
./gradlew packageDmg       # macOS .dmg / .pkg
./gradlew packageMsi       # Windows .msi / .exe
./gradlew packageDeb       # Linux .deb / .rpm
```

三平台一键打包见 [.github/workflows/build.yml](.github/workflows/build.yml)(GitHub Actions 矩阵)。

## 项目结构

```
src/main/kotlin/musicunlock/
  core/           多格式解密核心(纯 Kotlin)
    NcmDecoder.kt / NcmCipher.kt / NcmMetadata.kt   网易云 NCM
    QmcDecoder.kt / QmcKey.kt / QmcCipher.kt / TeaCipher.kt   QQ音乐 QMC
    KgmDecoder.kt  酷狗 KGM/KGMA/VPR
    KwmDecoder.kt  酷我 KWM
    AudioSniffer.kt / Formats.kt / MusicDecoder.kt
  service/        MusicConverter(转换编排)、TagWriter(标签写回)
  cli/            MainCli(命令行)
  ui/             App.kt(Compose 界面)、Theme.kt(主题)、FileDialogs.kt
```

## 算法来源与致谢

- NCM 解密:基于公开的 .ncm 容器格式规范实现,算法与 [unlock-music](https://github.com/kevinstoy/unlock-music)(MIT) 一致
- QMC 系列(密钥派生、TEA、Static/Map/RC4 流密码):[unlock-music](https://github.com/kevinstoy/unlock-music)(MIT)
- KGM/VPR: [MyKgmWasm](https://github.com/huangbao/MyKgmWasm)(MIT) 与 [unlock-music](https://github.com/kevinstoy/unlock-music)(MIT)
- KWM:[unlock-music](https://github.com/kevinstoy/unlock-music)(MIT)

测试向量来自 unlock-music 项目的 `testdata/`(MIT)。

## 许可证 (License)

本项目采用 [MIT License](LICENSE)。
第三方算法与依赖声明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 免责声明

本项目仅用于学习密码学与文件格式分析,请勿用于任何商业用途或侵犯他人权益。
