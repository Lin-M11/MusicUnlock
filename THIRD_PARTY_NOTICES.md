# 第三方组件与许可声明 (Third-Party Notices)

## 算法来源(移植代码)

| 组件 | 来源 | 许可证 |
|---|---|---|
| QMC 系列(密钥派生/TEA/Static/Map/RC4) | [unlock-music](https://github.com/kevinstoy/unlock-music)(MengYX) | MIT |
| KWM 解密 | [unlock-music](https://github.com/kevinstoy/unlock-music)(MengYX) | MIT |
| KGM/VPR 解密 | [MyKgmWasm](https://github.com/huangbao/MyKgmWasm)(huangbao) + unlock-music | MIT |
| NCM 解密 | 公开格式规范 + unlock-music 算法参考 | MIT |
| TEA 算法 | golang.org/x/crypto/tea(经 unlock-music tea.ts 移植) | BSD-3-Clause |

### MIT License (unlock-music / MyKgmWasm)

```
MIT License

Copyright (c) 2019-2023 MengYX

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## 运行时依赖

| 库 | 版本 | 许可证 |
|---|---|---|
| Compose Multiplatform | 1.7.3 | Apache-2.0 |
| Kotlin | 2.1.0 | Apache-2.0 |
| Gson | 2.13.1 | Apache-2.0 |
| jaudiotagger | 3.0.1 | LGPL-2.1 |
| kotlinx-coroutines | 1.9.0 | Apache-2.0 |

## 测试数据

`src/test/resources/testdata/` 下的测试向量来自 unlock-music 项目 `testdata/` 目录(MIT)。

## 免责声明

本工具仅用于学习密码学与文件格式分析。加密音乐文件的版权归其权利人所有,
请仅在拥有合法授权的前提下使用本工具。请勿用于商业用途或侵犯他人权益。
