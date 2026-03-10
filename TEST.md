# A2A Gateway 测试指南

## 启动服务

### 终端1：启动Mock AgentB（端口9090）

```bash
cd ~/gateway
mvn exec:java -Dexec.mainClass="com.example.mock.MockAgentBServer"
```

### 终端2：启动网关（端口8080）

```bash
cd ~/gateway
mvn exec:java -Dexec.mainClass="com.example.gateway.GatewayServer"
```

## 测试请求

### 测试1：非A2A适配流程（agentB）

```bash
curl -N http://127.0.0.1:8080/a2a/agentB -X POST \
  -d '{"jsonrpc":"2.0","method":"tasks/send","params":{"message":{"role":"user","parts":[{"type":"text","text":"帮我画一只猫"}]}}}' \
  -H 'Content-Type: application/json'
```

预期结果：SSE流式返回，包含：
- 普通文本转A2A格式（tasks/status）
- 图片生成结果（tasks/artifact，包含图片URL）
- 最终完成状态（state: completed）

### 测试2：正常A2A代理流程（其他agentId）

```bash
curl -N http://127.0.0.1:8080/a2a/agentD -X POST \
  -d '{"jsonrpc":"2.0","method":"tasks/send","params":{"message":{"role":"user","parts":[{"type":"text","text":"你好"}]}}}' \
  -H 'Content-Type: application/json'
```

注意：此请求会走MsgRouteHandler，因为mock服务没有A2A端点，会返回连接失败错误，仅用于验证路由逻辑。

## 单独测试Mock服务

```bash
# 测试token接口
curl -s http://127.0.0.1:9090/api/token -X POST \
  -d '{"appId":"test"}' -H 'Content-Type: application/json'

# 测试params接口
curl -s http://127.0.0.1:9090/api/params -X POST \
  -H 'Authorization: Bearer mock-token-abc123' \
  -H 'Content-Type: application/json' -d '{"query":"test"}'

# 测试SSE接口
curl -N http://127.0.0.1:9090/api/sse
```
