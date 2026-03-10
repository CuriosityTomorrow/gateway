package com.example.gateway.adapter;

import com.example.gateway.model.A2AMessage;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * AgentB适配器 —— 非A2A协议的AgentB调用适配
 *
 * 完整调用流程：
 *   1. fetchToken()    → 调下游token接口获取鉴权token
 *   2. fetchParams()   → 带token调下游参数接口获取SSE连接所需参数
 *   3. connectSse()    → 用Netty Bootstrap建立SSE连接，流式接收下游数据
 *   4. 流式处理        → 每收到一个SSE分块：
 *                         - 普通文本：转A2A格式通过writer回写上游
 *                         - #picture：切换到拼接模式，开始累积参数
 *                         - 拼接中的文本：追加到pictureBuffer
 *                         - #end：拼接完成，调图片API获取URL，通过writer回写上游
 *
 * 状态管理：
 *   每次请求new一个AgentBAdapter实例，以下状态绑定在实例上：
 *   - accumulatingPicture: 是否处于图片参数拼接模式
 *   - pictureBuffer: 图片参数拼接缓冲区
 *   - sseLineBuffer: SSE行缓冲区（处理跨TCP包的半行数据）
 *   - token: 本次请求获取的鉴权token
 *   - taskId: 本次请求的任务ID
 *   请求结束后实例被GC回收，不存在状态泄漏问题。
 *
 * 与传输层的解耦：
 *   本Adapter不直接操作Netty Channel，而是通过ResponseWriter接口回写数据。
 *   未来拆微服务时，只需换ResponseWriter的实现（从ChannelResponseWriter换成HttpResponseWriter），
 *   本类的业务代码一行不改。
 */
public class AgentBAdapter implements ProtocolAdapter {

    private static final Gson GSON = new Gson();
    private static final Pattern PICTURE_START = Pattern.compile("#picture");
    private static final Pattern PICTURE_END = Pattern.compile("#end");

    // ====== 下游AgentB的地址配置 ======
    private final String agentBHost;
    private final int agentBPort;

    // ====== 实例级状态（每次请求独立，new时初始化，请求结束GC回收）======

    /** 图片拼接模式开关：遇到#picture设为true，遇到#end设为false */
    private boolean accumulatingPicture = false;

    /** 图片参数拼接缓冲区：#picture和#end之间的文本累积在这里 */
    private final StringBuilder pictureBuffer = new StringBuilder();

    /** 本次请求的任务ID，用于A2A响应中的id字段 */
    private String taskId;

    /** 本次请求获取的鉴权token，后续所有下游调用都带这个token */
    private String token;

    /**
     * SSE行缓冲区 —— 处理跨TCP包的数据完整性问题
     *
     * 网络传输中，一个SSE事件（如 "data: {"text":"hello"}\n\n"）可能被拆成多个TCP包：
     *   包1: "data: {\"tex"
     *   包2: "t\":\"hello\"}\n\n"
     *
     * sseLineBuffer负责把这些碎片拼成完整行后再交给业务处理。
     * 只有遇到换行符(\n)才认为一行完整，取出处理，剩余部分继续缓冲。
     */
    private final StringBuilder sseLineBuffer = new StringBuilder();

    /** 响应写入器 —— 通过此接口回写数据，不直接操作Channel */
    private ResponseWriter writer;

    public AgentBAdapter(String agentBHost, int agentBPort) {
        this.agentBHost = agentBHost;
        this.agentBPort = agentBPort;
    }

    @Override
    public boolean supports(String agentId) {
        return "agentB".equals(agentId);
    }

    /**
     * 适配器入口 —— 由AdapterHandler调用
     *
     * 执行流程：
     *   1. 生成taskId标识本次请求
     *   2. 通过writer发送SSE响应头（告诉上游"我要开始SSE流了"）
     *   3. 发送一条"处理中"状态（让上游知道网关已经在干活了）
     *   4. 异步执行后续步骤（token → params → SSE连接）
     *
     * 注意：异步执行是因为fetchToken和fetchParams是同步HTTP调用，
     * 如果在Netty的IO线程中直接执行会阻塞EventLoop。
     * 生产环境建议改为全异步。
     */
    @Override
    public void handle(FullHttpRequest request, String agentId, ResponseWriter writer) {
        this.writer = writer;
        this.taskId = UUID.randomUUID().toString().substring(0, 8);

        // 先给上游回SSE响应头 —— 通过writer，不直接操作Channel
        writer.sendHeaders();

        // 发一个"处理中"状态 —— 让上游立即知道请求已被接收
        writer.onEvent(A2AMessage.taskStatus(taskId, "working", "正在处理请求..."));

        // 异步执行后续步骤
        // 这里创建一个新线程执行，避免阻塞调用方的EventLoop
        Thread.ofVirtual().start(() -> {
            try {
                // ========== 第一步：获取token ==========
                token = fetchToken();
                System.out.println("[AgentBAdapter] 获取token成功: " + token);

                // ========== 第二步：获取SSE调用参数 ==========
                String sseParams = fetchParams(token);
                System.out.println("[AgentBAdapter] 获取SSE参数成功: " + sseParams);

                // ========== 第三步：建立SSE连接，流式获取AgentB返回 ==========
                connectSse(token, sseParams);

            } catch (Exception e) {
                System.err.println("[AgentBAdapter] 处理失败: " + e.getMessage());
                writer.onEvent(A2AMessage.taskStatus(taskId, "failed", "处理失败: " + e.getMessage()));
                writer.onComplete();
            }
        });
    }

    // ==================== 第一步：获取Token ====================

    /**
     * 调用下游AgentB的token接口
     * 使用JDK自带的HttpClient（同步调用）
     * 生产环境可替换为Netty异步HTTP客户端
     */
    private String fetchToken() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://" + agentBHost + ":" + agentBPort + "/api/token"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"appId\":\"gateway\"}"))
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        JsonObject json = GSON.fromJson(resp.body(), JsonObject.class);
        return json.get("token").getAsString();
    }

    // ==================== 第二步：获取SSE调用参数 ====================

    /**
     * 带token调用下游AgentB的参数接口
     * 返回的参数将用于后续建立SSE连接
     */
    private String fetchParams(String token) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://" + agentBHost + ":" + agentBPort + "/api/params"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"query\":\"test\"}"))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        return resp.body();
    }

    // ==================== 第三步：建立SSE连接 ====================

    /**
     * 用Netty Bootstrap建立到下游AgentB的SSE连接
     *
     * 连接成功后，往outChannel发一个GET /api/sse请求，
     * 下游会持续返回SSE分块，由内部类SseClientHandler接收处理。
     *
     * 为什么用Netty Bootstrap而不是JDK HttpClient：
     *   SSE是长连接流式响应，需要逐块处理。Netty的事件驱动模型天然支持这种场景：
     *   每来一个数据块自动回调channelRead，不需要轮询或阻塞等待。
     *   JDK HttpClient虽然也能处理流式响应，但API不如Netty灵活。
     */
    private void connectSse(String token, String sseParams) {
        EventLoopGroup group = new NioEventLoopGroup(1);
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        // HttpClientCodec: 把下游返回的原始字节解码成HttpResponse/HttpContent对象
                        p.addLast(new HttpClientCodec());
                        // SseClientHandler: 我们的业务处理器，接收解码后的SSE数据
                        p.addLast(new SseClientHandler(group));
                    }
                });

        bootstrap.connect(agentBHost, agentBPort).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                Channel outChannel = future.channel();
                // 连接成功，发送SSE请求
                FullHttpRequest sseReq = new DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/sse?params=" + sseParams);
                sseReq.headers().set(HttpHeaderNames.HOST, agentBHost + ":" + agentBPort);
                sseReq.headers().set(HttpHeaderNames.ACCEPT, "text/event-stream");
                sseReq.headers().set(HttpHeaderNames.AUTHORIZATION, "Bearer " + token);
                outChannel.writeAndFlush(sseReq);
                System.out.println("[AgentBAdapter] SSE连接已建立");
            } else {
                System.err.println("[AgentBAdapter] SSE连接失败: " + future.cause().getMessage());
                writer.onEvent(A2AMessage.taskStatus(taskId, "failed", "SSE连接失败"));
                writer.onComplete();
                group.shutdownGracefully();
            }
        });
    }

    // ==================== SSE分块接收与处理 ====================

    /**
     * SseClientHandler —— 接收下游AgentB的SSE分块数据
     *
     * 这是AgentBAdapter的内部类，注册在outChannel的Pipeline中。
     * Netty的事件驱动机制保证：下游每发一块数据，channelRead会被自动回调。
     *
     * 注意：这个Handler通过外部类的writer字段回写数据，
     * 不直接持有inChannel引用，实现了与传输层的解耦。
     */
    private class SseClientHandler extends ChannelInboundHandlerAdapter {

        private final EventLoopGroup group;

        SseClientHandler(EventLoopGroup group) {
            this.group = group;
        }

        /**
         * Netty自动回调 —— 下游每发一块数据就调一次
         *
         * 消息到达顺序：
         *   1. HttpResponse（响应头）—— 第一次channelRead收到
         *   2. HttpContent（数据块）—— 每个SSE分块触发一次，可能多次
         *   3. LastHttpContent（流结束标记）—— 最后一次channelRead收到
         */
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            // 1. 收到下游的HTTP响应头（状态码、Content-Type等）
            if (msg instanceof io.netty.handler.codec.http.HttpResponse resp) {
                System.out.println("[AgentBAdapter] SSE响应状态: " + resp.status());
            }

            // 2. 收到SSE数据块 —— 交给processRawChunk处理
            if (msg instanceof HttpContent content) {
                String chunk = content.content().toString(CharsetUtil.UTF_8);
                processRawChunk(chunk);
            }

            // 3. 流结束 —— 发送completed状态，关闭连接，释放资源
            if (msg instanceof LastHttpContent) {
                System.out.println("[AgentBAdapter] SSE流结束");
                writer.onEvent(A2AMessage.taskStatus(taskId, "completed", null));
                writer.onComplete();
                ctx.close();
                group.shutdownGracefully();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            System.err.println("[AgentBAdapter] SSE异常: " + cause.getMessage());
            writer.onEvent(A2AMessage.taskStatus(taskId, "failed", "SSE异常: " + cause.getMessage()));
            writer.onComplete();
            ctx.close();
            group.shutdownGracefully();
        }
    }

    // ==================== SSE数据解析层 ====================

    /**
     * 处理原始SSE数据 —— 解决跨TCP包的数据完整性问题
     *
     * SSE协议中，一条完整事件的格式是 "data: {...}\n\n"
     * 但TCP传输不保证按SSE事件边界切分，可能出现：
     *   chunk1: "data: {\"text\":\"hel"
     *   chunk2: "lo\"}\n\ndata: {\"text\":\"world\"}\n\n"
     *
     * 处理策略：
     *   1. 所有收到的数据先追加到sseLineBuffer
     *   2. 找到最后一个换行符，把换行符之前的内容当作完整数据逐行处理
     *   3. 换行符之后的不完整数据留在buffer里，等下一个chunk来了继续拼
     */
    private void processRawChunk(String rawChunk) {
        sseLineBuffer.append(rawChunk);

        String buffered = sseLineBuffer.toString();
        int lastNewline = buffered.lastIndexOf("\n");
        if (lastNewline == -1) {
            return; // 还没有完整行，继续缓冲等待
        }

        // 分离完整部分和不完整部分
        String complete = buffered.substring(0, lastNewline + 1);
        String remaining = buffered.substring(lastNewline + 1);
        sseLineBuffer.setLength(0);
        sseLineBuffer.append(remaining);

        // 逐行处理完整的SSE事件
        String[] lines = complete.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("data: ")) {
                String data = line.substring(6).trim();
                if ("[DONE]".equals(data)) {
                    continue; // [DONE]是SSE结束标记，由LastHttpContent触发流结束逻辑
                }
                processSseData(data);
            }
        }
    }

    // ==================== 业务处理层（协议转换 + 状态机）====================

    /**
     * 处理单条SSE data —— 协议转换的核心逻辑
     *
     * 下游AgentB返回的格式：{"text": "..."}
     * 需要转成A2A格式：{"jsonrpc":"2.0","method":"tasks/status","params":{...}}
     *
     * 状态机逻辑（accumulatingPicture作为开关）：
     *
     *   普通模式 (accumulatingPicture=false)
     *     ├─ 收到普通文本 → 转A2A的tasks/status → writer.onEvent()
     *     └─ 收到"#picture" → 切换到拼接模式
     *
     *   拼接模式 (accumulatingPicture=true)
     *     ├─ 收到普通文本 → 追加到pictureBuffer
     *     └─ 收到"#end" → 拼接完成 → callPictureApi() → writer.onEvent()
     *                    → 切回普通模式
     */
    private void processSseData(String data) {
        try {
            JsonObject json = GSON.fromJson(data, JsonObject.class);
            String text = json.has("text") ? json.get("text").getAsString() : "";

            if (!accumulatingPicture) {
                // ====== 普通模式 ======
                if (PICTURE_START.matcher(text).find()) {
                    // 遇到#picture → 切换到拼接模式
                    accumulatingPicture = true;
                    pictureBuffer.setLength(0); // 清空缓冲区
                    System.out.println("[AgentBAdapter] 开始图片参数拼接");
                    writer.onEvent(A2AMessage.taskStatus(taskId, "working", "正在生成图片..."));
                } else {
                    // 普通文本 → 转A2A格式回写上游
                    writer.onEvent(A2AMessage.taskStatus(taskId, "working", text));
                }
            } else {
                // ====== 拼接模式 ======
                if (PICTURE_END.matcher(text).find()) {
                    // 遇到#end → 拼接完成
                    accumulatingPicture = false; // 切回普通模式
                    String pictureParams = pictureBuffer.toString().trim();
                    System.out.println("[AgentBAdapter] 图片参数拼接完成: " + pictureParams);

                    // 用拼接好的参数调用图片API
                    String imageUrl = callPictureApi(pictureParams);
                    // 以A2A artifact格式回写上游（包含图片URL）
                    writer.onEvent(A2AMessage.taskArtifact(taskId, "generated_image", imageUrl));
                } else {
                    // 继续拼接 → 追加到pictureBuffer
                    pictureBuffer.append(text);
                    System.out.println("[AgentBAdapter] 拼接中: " + text);
                }
            }
        } catch (Exception e) {
            System.err.println("[AgentBAdapter] 解析SSE数据失败: " + data + ", error: " + e.getMessage());
        }
    }

    // ==================== 外部API调用 ====================

    /**
     * 调用图片生成API
     *
     * 将#picture和#end之间拼接的参数作为入参，调用图片API获取生成的图片URL。
     * 当前为模拟实现（调Mock服务），实际场景替换为真实的图片生成服务地址。
     *
     * @param params 拼接好的图片参数（如 "prompt=一只猫&style=水彩画&size=1024x1024"）
     * @return 图片URL
     */
    private String callPictureApi(String params) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + agentBHost + ":" + agentBPort + "/api/picture"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"params\":\"" + params + "\"}"))
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            JsonObject json = GSON.fromJson(resp.body(), JsonObject.class);
            return json.get("url").getAsString();
        } catch (Exception e) {
            System.err.println("[AgentBAdapter] 图片API调用失败: " + e.getMessage());
            return "https://error.example.com/failed.png";
        }
    }
}
