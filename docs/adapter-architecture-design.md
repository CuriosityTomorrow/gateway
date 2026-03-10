# AdapterHandler 架构设计文档

## 一、背景与问题

### 1.1 现有网关架构

现有A2A网关采用经典的 **Frontend Bootstrap + Backend Bootstrap** 双层设计：

```
上游Agent ──▶ Frontend Bootstrap (接收上游请求)
                    │
                    ▼
              MsgRouteHandler (路由 + 业务处理)
                    │
                    ▼
              Backend Bootstrap (建立下游连接)
                    │
                    ├── Action1: 构造下游请求
                    ├── Action2: 发送请求
                    └── Action3: 接收响应 → 回写上游
                    │
                    ▼
              下游Agent (A2A协议)
```

- **Frontend Bootstrap** 负责监听端口、接收上游连接、解码HTTP/SSE请求
- **Backend Bootstrap** 负责建立到下游agent的连接，内部通过一组 **Action** 来处理具体的转发逻辑
- 这套模式的前提假设是：**下游agent统一使用A2A协议**，Action只需要处理A2A协议内部的差异（如不同的method、不同的参数格式）

### 1.2 新需求：非A2A协议接入

部分团队的agent未采用A2A协议开发，需要在网关层做协议转换。这类agent的特点是：

- 调用步骤不固定（可能需要先获取token、再获取参数、再建SSE连接）
- 协议格式各不相同（有的是自定义JSON、有的是XML、有的是gRPC）
- 响应处理逻辑各不相同（有的需要拼接、有的需要聚合、有的需要调用第三方API）
- 各agent之间没有任何协议层面的共性

**核心问题：如何在不侵入现有 MsgRouteHandler 和 Backend Bootstrap 的前提下，支持这些非A2A协议的agent接入？**

---

## 二、为什么Adapter不遵循 Frontend→Backend 模式

### 2.1 Backend Action模式的适用场景

Backend Bootstrap 的 Action 体系本质上是一个**模板方法模式**：

```
建立连接 → Action1 → Action2 → Action3 → 关闭连接
```

这个模式的隐含前提是：
1. **调用步骤固定** — 所有下游agent的调用都是"建连→发请求→收响应→回写"
2. **协议统一** — 下游都是A2A协议，差异只在参数层面
3. **无状态** — 每个Action独立执行，不需要跨Action维护状态
4. **单次连接** — 一个请求对应一个下游连接

### 2.2 Adapter场景为什么不适用

以AgentB为例，一次请求的调用链路是：

```
1. HTTP POST /api/token     → 获取token（非SSE，独立HTTP调用）
2. HTTP POST /api/params    → 获取SSE参数（非SSE，独立HTTP调用，依赖步骤1的token）
3. HTTP GET  /api/sse       → SSE流式连接（需要步骤1的token和步骤2的params）
4. 流式处理中：遇到#picture  → 开始拼接参数（状态切换）
5. 流式处理中：遇到#end     → HTTP POST /api/picture（依赖步骤4拼接的参数）
6. 继续处理SSE流...
```

对比Backend Action模式的四个前提：

| 前提 | Backend Action | AgentB Adapter | 矛盾点 |
|------|---------------|----------------|---------|
| 调用步骤固定 | 建连→发→收→写 | token→params→SSE→拼接→图片API→继续SSE | **步骤数量和顺序完全不同** |
| 协议统一 | A2A | 自定义JSON + SSE混合 | **协议完全不同** |
| 无状态 | 每个Action独立 | #picture→#end之间需要跨chunk维护拼接状态 | **需要有状态处理** |
| 单次连接 | 1个请求→1个下游连接 | 1个请求→3次HTTP + 1个SSE连接 | **多次连接，混合同步和异步** |

**如果强行套Action模式会怎样？**

```
Action1: FetchTokenAction        → 获取token，存到上下文
Action2: FetchParamsAction       → 获取params，存到上下文
Action3: ConnectSseAction        → 建SSE连接
Action4: ProcessChunkAction      → 处理每个SSE分块
Action5: AccumulatePictureAction → #picture拼接
Action6: CallPictureApiAction    → 调图片API
```

问题：
- Action4和Action5需要共享状态（`accumulatingPicture`、`pictureBuffer`），破坏了Action的独立性
- Action4会被调用多次（每个SSE chunk一次），但Action模式假设每个Action只执行一次
- Action3建立的SSE连接是长连接，数据持续流入，不符合"Action执行完毕→下一个Action"的线性模型
- 换一个agentC，上面的Action数量、顺序、依赖关系可能完全不同，根本无法复用

### 2.3 结论

Backend Action模式是为**统一协议、固定步骤、无状态转发**设计的抽象。Adapter面对的是**协议各异、步骤灵活、有状态处理**的场景，两者的前提假设完全不同。

**正确做法：Adapter独立管理自己的调用流程和下游连接，不复用Backend Bootstrap和Action体系。** 每个Adapter是一个自包含的处理单元，内部自由定义自己需要多少步骤、建多少连接、维护什么状态。

---

## 三、当前设计：嵌入式Adapter

### 3.1 架构图

```
                      Netty Pipeline
                 ┌──────────────────────┐
                 │    HttpServerCodec    │
                 │  HttpObjectAggregator │
上游Agent(A2A) ─▶│    AdapterHandler    ─┼── 判断agentId
                 │     │          │      │
                 │  [非A2A]    [A2A]     │
                 │     │          │      │
                 │  Adapter    MsgRoute  │
                 │  (new)     Handler    │
                 └──────────────────────┘
                       │
            ┌──────────┼──────────┐
            ▼          ▼          ▼
        AgentB      AgentC     AgentD
        Adapter     Adapter    Adapter
       (各自独立管理下游调用)
```

### 3.2 核心设计

- **AdapterHandler** 在 MsgRouteHandler 之前，拦截非A2A请求
- 每次请求 `new` 一个 Adapter 实例，**状态绑定在实例上，请求结束自动销毁**
- Adapter 内部自由管理下游调用（HTTP、SSE、gRPC等），不受Backend Bootstrap约束
- Adapter 通过 **ResponseWriter 回调接口** 回写数据，不直接操作 Netty Channel

### 3.3 ResponseWriter 解耦层

Adapter 不直接操作 inChannel，而是通过 ResponseWriter 接口回写数据：

```java
public interface ResponseWriter {
    /** 发送SSE响应头（首次调用时触发） */
    void sendHeaders();
    /** 发送一条A2A事件 */
    void onEvent(A2AMessage message);
    /** 流正常结束 */
    void onComplete();
    /** 流异常结束 */
    void onError(String error);
}
```

**为什么需要这层抽象：**

Adapter 的业务逻辑（获取token → 获取参数 → SSE调用 → 协议转换 → 状态机处理）和数据输出方式应该是解耦的。同样的业务逻辑，可能需要把结果写到：
- Netty的inChannel（当前：嵌入Gateway进程）
- HTTP Response（未来：独立微服务）
- 消息队列（未来：异步解耦模式）

ResponseWriter 让 Adapter 只关心"产出什么数据"，不关心"数据怎么传给上游"。

---

## 四、未来演进：独立协议转换微服务

### 4.1 为什么要拆

随着非A2A agent越来越多，Adapter的代码会不断膨胀。每个Adapter可能有自己的依赖（特定SDK、特定协议库），全部放在Gateway进程中会带来：

1. **部署耦合** — 改一个Adapter的逻辑需要重新部署整个Gateway
2. **依赖膨胀** — Gateway引入大量Adapter的第三方依赖
3. **故障隔离差** — 一个Adapter的bug（如内存泄漏）影响整个Gateway
4. **团队协作难** — 不同团队维护不同Adapter，都要改Gateway代码

### 4.2 目标架构

```
                    A2A Gateway                          协议转换服务
              ┌───────────────────┐              ┌──────────────────────┐
              │   AdapterHandler  │   HTTP/SSE   │   ConvertController  │
上游Agent ───▶│       │          │─────────────▶│         │            │
              │    [非A2A]       │              │    AdapterRouter     │
              │       │          │◀─────────────│         │            │
              │   转发并透传     │  A2A SSE流    │   ┌─────┼─────┐      │
              │       │          │              │   ▼     ▼     ▼      │
              │  MsgRouteHandler │              │ AgentB AgentC AgentD │
              │   (A2A不受影响)  │              │ Adapter Adapter Adapter│
              └───────────────────┘              └──────────────────────┘
```

**关键变化：**
- Gateway的AdapterHandler不再自己new Adapter处理，而是把请求**转发给协议转换服务**
- 协议转换服务内部路由到具体的Adapter，执行业务逻辑
- 协议转换服务返回的已经是**标准A2A格式的SSE流**
- Gateway只管透传这个SSE流给上游，就像处理正常A2A请求一样

### 4.3 协议转换服务的对外接口

```
POST /convert/{agentId}
Content-Type: application/json

请求体：原始A2A请求（直接从上游透传）
响应：text/event-stream（标准A2A SSE格式）
```

对Gateway来说，协议转换服务就是一个"会说A2A协议的中间人"，Gateway完全不关心内部转换细节。

### 4.4 迁移步骤

#### 阶段一：加 ResponseWriter 抽象（当前阶段，嵌入式）

```
                现在
Adapter ──▶ ResponseWriter(接口)
                  │
                  ▼
            ChannelResponseWriter(实现)
                  │
                  ▼
            直接写inChannel
```

- 改动范围：Adapter和AdapterHandler
- 对MsgRouteHandler：零影响
- 目的：让Adapter不依赖Netty Channel，为拆服务做准备

#### 阶段二：部署协议转换服务

```
                未来
Adapter ──▶ ResponseWriter(接口)
                  │
                  ▼
            HttpResponseWriter(实现)
                  │
                  ▼
            写HTTP Response给Gateway
```

- 把所有Adapter代码搬到协议转换服务中
- Adapter内部的业务逻辑**一行不改**，只是换了ResponseWriter的实现
- Gateway的AdapterHandler改为转发模式（类似MsgRouteHandler的透传逻辑）

#### 阶段三：Gateway侧改造

AdapterHandler 从"路由到本地Adapter"变为"转发到协议转换服务"：

```java
// 阶段一（当前）：本地处理
ProtocolAdapter adapter = createAdapter(agentId);
ResponseWriter writer = new ChannelResponseWriter(inChannel);
adapter.handle(request, agentId, writer);

// 阶段三（未来）：转发到协议转换服务
// AdapterHandler退化为一个简单的反向代理
forwardToConversionService(inChannel, request, agentId);
// 协议转换服务返回A2A SSE流，直接透传给上游
```

### 4.5 迁移成本评估

| 组件 | 阶段一→阶段二改动 | 说明 |
|------|-------------------|------|
| ProtocolAdapter接口 | 加ResponseWriter参数 | 一处改动 |
| 各Adapter实现 | 把writeA2AEvent改为writer.onEvent | 全局替换 |
| Adapter业务逻辑 | **不改** | token→params→SSE→拼接，原封不动搬过去 |
| AdapterHandler | 从本地路由改为HTTP转发 | 逻辑简化 |
| MsgRouteHandler | **不改** | 始终零影响 |

### 4.6 独立服务的额外能力

协议转换服务独立后，不仅能转换下游协议，还能转换上游协议：

```
上游非A2A Agent → 协议转换服务(转成A2A) → Gateway → 下游A2A Agent
上游A2A Agent  → Gateway → 协议转换服务(转成非A2A) → 下游非A2A Agent
上游非A2A Agent → 协议转换服务(转A2A) → Gateway → 协议转换服务(转非A2A) → 下游非A2A Agent
```

只要是涉及协议转换的场景，都可以丢给这个服务处理。

---

## 五、总结

| 决策 | 结论 | 原因 |
|------|------|------|
| Adapter是否遵循Backend Action模式 | **否** | Action模式假设协议统一、步骤固定、无状态，Adapter场景的前提完全不同 |
| Adapter是否使用Backend Bootstrap | **否** | Adapter可能需要多次异步HTTP + SSE混合调用，不适合单一Bootstrap模型 |
| Adapter如何回写上游 | **通过ResponseWriter接口** | 解耦业务逻辑和传输层，支持未来拆服务 |
| 是否现在就拆微服务 | **否，先加ResponseWriter抽象** | 当前agent数量少，嵌入式足够；加抽象层为未来铺路，改动最小 |
| 拆服务时Adapter代码要改吗 | **业务逻辑不改，只换ResponseWriter实现** | 这就是加抽象层的价值 |
