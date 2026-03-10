# A2A Gateway

基于Netty的A2A（Agent-to-Agent）协议网关，支持标准A2A协议代理和非A2A协议适配。

## 项目结构

```
src/main/java/com/example/
├── gateway/
│   ├── GatewayServer.java              # 网关入口，Pipeline注册
│   ├── handler/
│   │   ├── AdapterHandler.java         # 非A2A请求拦截与Adapter分发
│   │   └── MsgRouteHandler.java        # 标准A2A SSE代理（已有逻辑，不修改）
│   ├── adapter/
│   │   ├── ProtocolAdapter.java        # 适配器接口
│   │   ├── ResponseWriter.java         # 响应写入器接口（解耦Adapter与传输层）
│   │   ├── ChannelResponseWriter.java  # ResponseWriter的Netty Channel实现
│   │   └── AgentBAdapter.java          # AgentB适配器（参考实现）
│   └── model/
│       └── A2AMessage.java             # A2A消息封装工具类
└── mock/
    └── MockAgentBServer.java           # 模拟下游AgentB服务（仅测试用）
```

## Netty Pipeline

```
HttpServerCodec → HttpObjectAggregator → AdapterHandler → MsgRouteHandler
                                              │
                                         非A2A请求拦截
                                         A2A请求透传
```

## 关键设计

- **AdapterHandler** 在 MsgRouteHandler 前拦截非A2A请求，对已有逻辑零侵入
- **ProtocolAdapter** 接口 + 工厂模式，支持灵活扩展新的协议适配器
- **ResponseWriter** 抽象层，解耦Adapter业务逻辑与Netty传输层，为未来拆微服务铺路
- 每次请求new一个Adapter实例，状态绑定在实例上，无线程安全问题

## 文档

- [AdapterHandler架构设计文档](docs/adapter-architecture-design.md) — 详细论述为什么不遵循Frontend→Backend模式、未来如何迁移为独立微服务

## 快速开始

```bash
# 终端1：启动Mock AgentB（端口9090）
mvn exec:java -Dexec.mainClass="com.example.mock.MockAgentBServer"

# 终端2：启动网关（端口8080）
mvn exec:java -Dexec.mainClass="com.example.gateway.GatewayServer"

# 终端3：测试非A2A适配流程
curl -N http://127.0.0.1:8080/a2a/agentB -X POST \
  -d '{"jsonrpc":"2.0","method":"tasks/send","params":{"message":{"role":"user","parts":[{"type":"text","text":"帮我画一只猫"}]}}}' \
  -H 'Content-Type: application/json'
```
