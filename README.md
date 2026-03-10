# A2A Gateway — 非A2A协议适配层设计与实现

## 一、背景

现有A2A网关已实现标准A2A协议的agent间调用代理（`MsgRouteHandler`），但部分团队的agent未采用A2A协议开发，需要在网关层做协议转换和适配。

**核心需求：在不侵入现有 `MsgRouteHandler` 的前提下，支持非A2A协议agent的接入。**

---

## 二、整体架构

```
                         Netty Pipeline
                    ┌─────────────────────┐
                    │   HttpServerCodec    │
                    │   HttpObjectAggregator│
上游Agent(A2A) ───▶│   AdapterHandler  ◄──┼── 新增：拦截非A2A请求
                    │         │            │
                    │    [A2A请求透传]      │
                    │         ▼            │
                    │   MsgRouteHandler    │  ← 已有：不做任何修改
                    └─────────────────────┘
```

**关键设计：AdapterHandler 放在 MsgRouteHandler 之前，通过判断目标agent类型决定是拦截处理还是透传。对 MsgRouteHandler 零侵入。**

---

## 三、AdapterHandler 设计详解

### 3.1 职责

AdapterHandler 是 Pipeline 中的一个 `ChannelInboundHandlerAdapter`，职责单一：

1. 从请求URL中解析目标 `agentId`（格式：`/a2a/{agentId}`）
2. 判断该agent是否为非A2A协议（查 `NON_A2A_AGENTS` 集合）
3. **非A2A** → 创建对应的 `ProtocolAdapter` 实例，由adapter接管处理，**不再往下传递**
4. **A2A** → 调用 `ctx.fireChannelRead(msg)` 透传给 `MsgRouteHandler`

### 3.2 路由逻辑

```java
@Override
public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    FullHttpRequest request = (FullHttpRequest) msg;
    String agentId = extractAgentId(request.uri());  // 从 /a2a/{agentId} 提取

    if (agentId != null && NON_A2A_AGENTS.contains(agentId)) {
        // 非A2A：new一个adapter处理，不往下传
        ProtocolAdapter adapter = createAdapter(agentId);
        adapter.handle(ctx.channel(), request, agentId);
        return;
    }

    // A2A：透传，MsgRouteHandler不受任何影响
    ctx.fireChannelRead(msg);
}
```

### 3.3 与 MsgRouteHandler 的解耦方式

| 机制 | 说明 |
|------|------|
| Pipeline顺序 | AdapterHandler在前，MsgRouteHandler在后 |
| 拦截 vs 透传 | 非A2A请求被AdapterHandler拦截（不调用`fireChannelRead`），MsgRouteHandler根本收不到 |
| 无共享状态 | 两个Handler之间没有任何共享变量或上下文依赖 |
| 独立响应 | AdapterHandler自己往inChannel写SSE响应，不依赖MsgRouteHandler的任何逻辑 |

**结论：即使删掉AdapterHandler，MsgRouteHandler的行为完全不变。即使删掉MsgRouteHandler，AdapterHandler也能独立工作。**

### 3.4 Pipeline注册

```java
// GatewayServer.java 中
ChannelPipeline p = ch.pipeline();
p.addLast(new HttpServerCodec());
p.addLast(new HttpObjectAggregator(65536));
p.addLast(new AdapterHandler(downstreamHost, downstreamPort));  // 新增
p.addLast(new MsgRouteHandler(downstreamHost, downstreamPort)); // 已有，不动
```

---

## 四、ProtocolAdapter 接口设计

```java
public interface ProtocolAdapter {
    /** 是否支持该agentId的适配 */
    boolean supports(String agentId);

    /**
     * 执行适配逻辑
     * @param inChannel 上游通道，用于写回A2A格式的SSE响应
     * @param request   原始HTTP请求
     * @param agentId   目标agentId
     */
    void handle(Channel inChannel, FullHttpRequest request, String agentId);
}
```

**设计要点：**

- 接口简洁，只有2个方法
- `handle` 接收 `inChannel`，adapter内部负责所有下游调用和协议转换，最终结果写回 `inChannel`
- 每次请求 `new` 一个adapter实例，**状态绑定在实例上**，请求结束自动销毁（GC回收）
- 无需手动管理生命周期，无线程安全问题

### 4.1 如何新增一个Adapter

当需要接入新的非A2A agent时，只需3步：

**第一步：实现 ProtocolAdapter 接口**

```java
public class AgentXAdapter implements ProtocolAdapter {
    // 实例级状态，每次请求独立
    private String token;
    private StringBuilder buffer = new StringBuilder();

    @Override
    public boolean supports(String agentId) {
        return "agentX".equals(agentId);
    }

    @Override
    public void handle(Channel inChannel, FullHttpRequest request, String agentId) {
        // 1. 发SSE响应头给上游
        // 2. 做你的业务逻辑（调API、协议转换等）
        // 3. 把结果封装成A2A格式写回inChannel
        // 4. 关闭SSE流
    }
}
```

**第二步：在 AdapterHandler.createAdapter() 中注册**

```java
private ProtocolAdapter createAdapter(String agentId) {
    if ("agentB".equals(agentId)) {
        return new AgentBAdapter(downstreamHost, downstreamPort);
    }
    if ("agentX".equals(agentId)) {        // 新增
        return new AgentXAdapter(host, port);
    }
    return null;
}
```

**第三步：在 NON_A2A_AGENTS 集合中添加agentId**

```java
private static final Set<String> NON_A2A_AGENTS = Set.of("agentB", "agentX");  // 加上
```

---

## 五、AgentBAdapter 实现详解（参考实现）

AgentBAdapter 是一个具体的适配器实现，展示了一个典型的非A2A适配场景。

### 5.1 调用流程

```
                        AgentBAdapter (每次请求new一个)
                        ┌──────────────────────────────────┐
 上游AgentA ──SSE头──▶ │  1. sendSseHeaders(inChannel)    │
                        │  2. fetchToken()       → token   │ ← HTTP POST /api/token
                        │  3. fetchParams(token) → params  │ ← HTTP POST /api/params
                        │  4. connectSse(inChannel, ...)   │ ← Netty Bootstrap建SSE连接
                        │       │                          │
                        │       ▼ 收到SSE分块              │
                        │  ┌─ 普通文本 → 转A2A → 写inChannel│
                        │  ├─ #picture → 开始拼接          │
                        │  ├─ 拼接中... → 追加到buffer      │
                        │  ├─ #end → 调图片API → 写inChannel│
                        │  └─ [DONE] → completed → 关闭    │
                        └──────────────────────────────────┘
```

### 5.2 四个阶段

#### 阶段一：获取Token

```java
private String fetchToken() throws Exception {
    // 用JDK HttpClient调POST /api/token
    // 返回token字符串
}
```

- 使用 `java.net.http.HttpClient`（JDK自带，无额外依赖）
- 同步调用，因为后续步骤依赖token

#### 阶段二：获取SSE参数

```java
private String fetchParams(String token) throws Exception {
    // 带着token调POST /api/params
    // 返回SSE调用所需的参数
}
```

- 同样用JDK HttpClient
- Header中带上 `Authorization: Bearer {token}`

#### 阶段三：建立SSE连接

```java
private void connectSse(Channel inChannel, String token, String sseParams) {
    Bootstrap bootstrap = new Bootstrap();
    bootstrap.group(new NioEventLoopGroup(1))
            .channel(NioSocketChannel.class)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(new HttpClientCodec());
                    ch.pipeline().addLast(new SseClientHandler(inChannel, group));
                }
            });
    // 连接下游，发送SSE请求
}
```

- 用Netty Bootstrap建立到下游的outChannel
- `SseClientHandler` 是内部类，持有 `inChannel` 的引用，收到数据直接回写

#### 阶段四：SSE分块处理 + #picture拼接

这是核心逻辑，处理流程如下：

```
收到SSE分块
    │
    ├─ 当前是普通模式？
    │   ├─ 文本内容匹配 #picture → 切换到拼接模式
    │   └─ 普通文本 → 封装成A2A tasks/status → 写回inChannel
    │
    └─ 当前是拼接模式？
        ├─ 文本内容匹配 #end → 拼接完成 → 调图片API → 封装成A2A tasks/artifact → 写回inChannel
        └─ 其他文本 → 追加到 pictureBuffer
```

**状态管理：**

```java
// 这些状态绑定在adapter实例上，每次请求独立
private boolean accumulatingPicture = false;   // 是否在拼接模式
private final StringBuilder pictureBuffer = new StringBuilder();  // 拼接缓冲
private final StringBuilder sseLineBuffer = new StringBuilder();  // SSE行缓冲（处理跨chunk）
```

**跨chunk处理：** SSE数据可能在chunk边界被截断（比如收到 `data: {"tex` 和 `t":"hello"}\n\n`），用 `sseLineBuffer` 缓冲，只处理完整行。

### 5.3 A2A消息封装

Adapter 使用 `A2AMessage` 工具类生成标准A2A格式：

```java
// 普通文本 → tasks/status
A2AMessage.taskStatus(taskId, "working", "你好，我是AgentB")
// 输出: {"jsonrpc":"2.0","method":"tasks/status","params":{"id":"xxx","status":{"state":"working","message":...}}}

// 图片结果 → tasks/artifact
A2AMessage.taskArtifact(taskId, "generated_image", imageUrl)
// 输出: {"jsonrpc":"2.0","method":"tasks/artifact","params":{"id":"xxx","artifact":{"name":"generated_image",...}}}
```

### 5.4 线程模型

```
inChannel.eventLoop().execute(() -> {
    fetchToken();    // 阶段1-2在inChannel的EventLoop线程执行
    fetchParams();   // 注意：这里用了同步HTTP调用，会阻塞EventLoop
    connectSse();    // 阶段3创建独立的EventLoopGroup
});
```

> **注意：** demo中阶段1-2的HTTP调用是同步的（阻塞EventLoop），生产环境建议改为异步（使用Netty的HTTP客户端或CompletableFuture）。阶段3的SSE连接使用了独立的EventLoopGroup，不会阻塞。

---

## 六、文件结构

```
src/main/java/com/example/
├── gateway/
│   ├── GatewayServer.java              # 网关入口，Pipeline注册
│   ├── handler/
│   │   ├── AdapterHandler.java         # 【核心】非A2A请求拦截与adapter分发
│   │   └── MsgRouteHandler.java        # 已有的A2A SSE代理（不需修改）
│   ├── adapter/
│   │   ├── ProtocolAdapter.java        # 适配器接口
│   │   └── AgentBAdapter.java          # AgentB适配器（参考实现）
│   └── model/
│       └── A2AMessage.java             # A2A消息封装工具类
└── mock/
    └── MockAgentBServer.java           # 模拟下游AgentB服务（仅测试用）
```

**你需要关注的文件：**

| 文件 | 说明 | 是否需要修改 |
|------|------|-------------|
| `AdapterHandler.java` | 路由分发，新增agent时改这里 | 新增agent时改 |
| `ProtocolAdapter.java` | 接口定义 | 一般不改 |
| `AgentBAdapter.java` | 参考实现，照着写新adapter | 参考 |
| `A2AMessage.java` | A2A消息封装 | 按需扩展 |
| `MsgRouteHandler.java` | 已有逻辑 | **不要动** |
| `GatewayServer.java` | Pipeline注册 | 加AdapterHandler那一行 |

---

## 七、测试

### 7.1 启动服务

```bash
# 终端1：启动Mock AgentB（端口9090）
cd ~/gateway
mvn exec:java -Dexec.mainClass="com.example.mock.MockAgentBServer"

# 终端2：启动网关（端口8080）
cd ~/gateway
mvn exec:java -Dexec.mainClass="com.example.gateway.GatewayServer"
```

### 7.2 测试非A2A适配流程

```bash
curl -N http://127.0.0.1:8080/a2a/agentB -X POST \
  -d '{"jsonrpc":"2.0","method":"tasks/send","params":{"message":{"role":"user","parts":[{"type":"text","text":"帮我画一只猫"}]}}}' \
  -H 'Content-Type: application/json'
```

预期输出：

```
data: {"jsonrpc":"2.0","method":"tasks/status","params":{"id":"xxx","status":{"state":"working","message":{"role":"agent","parts":[{"type":"text","text":"正在处理请求..."}]}}}}

data: {"jsonrpc":"2.0","method":"tasks/status","params":{"id":"xxx","status":{"state":"working","message":{"role":"agent","parts":[{"type":"text","text":"你好，我是AgentB"}]}}}}

data: {"jsonrpc":"2.0","method":"tasks/status","params":{"id":"xxx","status":{"state":"working","message":{"role":"agent","parts":[{"type":"text","text":"这是第一段分析结果"}]}}}}

data: {"jsonrpc":"2.0","method":"tasks/status","params":{"id":"xxx","status":{"state":"working","message":{"role":"agent","parts":[{"type":"text","text":"正在生成图片..."}]}}}}

data: {"jsonrpc":"2.0","method":"tasks/artifact","params":{"id":"xxx","artifact":{"name":"generated_image","parts":[{"type":"file","url":"https://image.example.com/generated/cat-watercolor-1024.png"}]}}}

data: {"jsonrpc":"2.0","method":"tasks/status","params":{"id":"xxx","status":{"state":"completed"}}}
```

### 7.3 测试A2A透传流程

```bash
curl -N http://127.0.0.1:8080/a2a/agentD -X POST \
  -d '{"jsonrpc":"2.0","method":"tasks/send","params":{"message":{"role":"user","parts":[{"type":"text","text":"你好"}]}}}' \
  -H 'Content-Type: application/json'
```

此请求会走 MsgRouteHandler（因为agentD不在非A2A列表中），验证路由逻辑正确。

### 7.4 单独测试Mock服务

```bash
# token接口
curl -s http://127.0.0.1:9090/api/token -X POST \
  -d '{"appId":"test"}' -H 'Content-Type: application/json'

# params接口
curl -s http://127.0.0.1:9090/api/params -X POST \
  -H 'Authorization: Bearer mock-token-abc123' \
  -H 'Content-Type: application/json' -d '{"query":"test"}'

# SSE接口
curl -N http://127.0.0.1:9090/api/sse
```
