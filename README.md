# OneTrojan

一个尽量简单、只有一个开关的 Android Trojan VPN 客户端。

不做分流，不维护复杂规则，也不支持一长串协议：打开开关后，设备的 IPv4 流量全部通过 Trojan；连接不可用时保持阻断，不静默回落到直连。

> 当前仍是开发预览版，建议先在备用设备上使用。现阶段只提供 Android 11 及以上、`arm64-v8a` 架构的构建。

## 下载

- [下载最新版 APK（v0.4.2-dev）](https://github.com/popyuiggg/OneTrojan/releases/download/v0.4.2-dev/OneTrojan-0.4.2-utls-dev.apk)
- [查看 Releases](https://github.com/popyuiggg/OneTrojan/releases)

APK 的 SHA-256：

```text
c1ac9796a7266a78e2982f33afae42b637aba0ba422469a73b4d92c2477b68ee
```

## 目前支持

- Android 11+（最低 API 30）。
- ARM64（`arm64-v8a`）。
- Trojan 协议，TCP 与 UDP 转发。
- 一个主开关，以及 Android 快速设置磁贴。
- 主界面和配置界面固定为竖屏。
- 全局 IPv4 VPN，不提供按应用或按域名分流。
- 在 Android VPN 地址族边界阻断 IPv6，防止从物理网络绕过。
- 强制使用 VPN 内的虚拟 DNS；DNS 请求转换为 Trojan 内的 DNS-over-TCP。
- 对 AAAA 查询返回空结果，使应用直接使用 IPv4。
- Chrome/uTLS ClientHello，适配要求 `tlsProfile: chrome` 的服务端。
- TLS 证书与 SNI 校验；没有“跳过证书验证”模式。
- 手动填写配置或从剪贴板文本导入 JSON。
- 使用 Android Keystore 加密保存密码和服务器配置。

## 不支持

- VLESS、VMess、Shadowsocks 等其他代理协议。
- 路由规则、绕过列表、局域网直连或按应用代理。
- 订阅链接和在线配置下载。
- 明文 DNS、IPv6 代理或不安全 TLS。
- 32 位 ARM、x86 和 x86_64 设备。

导入 JSON 时，`data` 等网络地址字段会被忽略，应用不会访问其中的 URL。

## 使用方法

1. 从 Releases 下载并安装 APK。
2. 打开 **配置**，手动填写参数，或者粘贴 JSON 文本进行导入。
3. 检查导入结果并单独点击保存。
4. 返回主页打开开关，并同意 Android 的 VPN 连接请求。
5. 当界面和常驻通知显示已保护后，流量才会开始转发。

首次授权后，可以在系统的快速设置编辑页面中加入 **Trojan VPN** 磁贴，以后直接从通知栏启停。

## 配置

### JSON 文本

配置页面支持常见的 Trojan JSON 字段：

```json
{
  "type": "Trojan",
  "host": "example.com",
  "ip": "203.0.113.10",
  "port": "443",
  "peer": "example.com",
  "password": "your-password",
  "dns": "1.1.1.1",
  "alpn": "h2,http/1.1",
  "tlsProfile": "chrome"
}
```

其中：

- `host`：Trojan 服务器域名。
- `ip`：服务器的数字 IPv4 地址，用于在 VPN 建立前连接服务器，避免启动阶段发生 DNS 泄漏。
- `peer`：TLS SNI；未填写时通常与 `host` 相同。
- `dns`：通过 Trojan 查询的数字 IPv4 DNS 服务器。
- `tlsProfile`：当前支持系统 TLS；填写 `chrome` 时启用 Chrome/uTLS 指纹。

如果导入的 JSON 没有 `ip`，请手动填写，或在配置页面明确点击 DNS 解析按钮。解析不会在后台自动进行。

### Trojan URI

也可以使用包含数字引导 IP 的严格 Trojan URI：

```text
trojan://your-password@example.com:443?ip=203.0.113.10&sni=example.com&dns=1.1.1.1
```

## 断网保护

OneTrojan 会先建立 Android VPN 接口，再验证 Trojan TCP、TCP-DNS 和 UDP-DNS。只有验证通过并启动 TUN 转发引擎后，状态才会变为“已保护”。

如果配置缺失、Trojan 不可达或转发引擎启动失败，VPN 接口会继续保持，但捕获到的流量将被丢弃，不会自动直连。

## 从源码构建

准备 Android Studio、Android SDK、NDK `28.2.13676358` 和 JDK 17，然后运行：

```shell
./gradlew test lint assembleDebug
```

生成的 APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

项目最低 API 为 30，使用 API 37 编译。`hev-socks5-tunnel` 的固定版本源码位于 `third_party/`；Chrome/uTLS 桥的 Go 源码位于 `utlsbridge/`，对应 ARM64 AAR 已放在 `app/libs/`。

第三方组件、固定版本和许可证信息见 [`third_party/NOTICE.md`](third_party/NOTICE.md)。

## 已验证设备

- Sony Xperia 5（J9210）
- Android 11
- Google Play 多分片 APK 下载与安装

欢迎通过 Issues 提交可复现的问题。报告网络故障时，请不要公开上传 Trojan 密码、真实节点配置或包含敏感信息的完整日志。
