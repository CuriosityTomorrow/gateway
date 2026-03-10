package com.example.gateway.adapter;

import com.example.gateway.model.A2AMessage;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
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
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * AgentB适配器
 * 处理非A2A协议的AgentB调用：token获取 → 参数获取 → SSE流式调用 → 协议转换
 * 每次请求new一个实例，状态（如#picture拼接缓冲）绑定在实例上
 */
public class AgentBAdapter implements ProtocolAdapter {

    private static final Gson GSON = new Gson();
    private static final Pattern PICTURE_START = Pattern.compile("#picture");
    private static final Pattern PICTURE_END = Pattern.compile("#end");

    // 下游AgentB的地址配置
    private final String agentBHost;
    private final int agentBPort;

    // 实例级状态 —— 每次请求独立
    private boolean accumulatingPicture = false;
    private final StringBuilder pictureBuffer = new StringBuilder();
    private String taskId;
    private String token;

    // SSE数据缓冲（处理跨chunk的情况）
    private final StringBuilder sseLineBuffer = new StringBuilder();

    public AgentBAdapter(String agentBHost, int agentBPort) {
        this.agentBHost = agentBHost;
        this.agentBPort = agentBPort;
    }

    @Override
    public boolean supports(String agentId) {
        return "agentB".equals(agentId);
    }

    @Override
    public void handle(Channel inChannel, FullHttpRequest request, String agentId) {
        this.taskId = UUID.randomUUID().toString().substring(0, 8);

        // 先给上游回SSE响应头
        sendSseHeaders(inChannel);

        // 发一个"处理中"状态
        writeA2AEvent(inChannel, A2AMessage.taskStatus(taskId, "working", "正在处理请求..."));

        // 异步执行：token → params → SSE
        // 使用inChannel的eventLoop来保证线程安全
        inChannel.eventLoop().execute(() -> {
            try {
                // 第一步：获取token
                token = fetchToken();
                System.out.println("[AgentBAdapter] 获取token成功: " + token);

                // 第二步：获取SSE调用参数
                String sseParams = fetchParams(token);
                System.out.println("[AgentBAdapter] 获取SSE参数成功: " + sseParams);

                // 第三步：建立SSE连接，流式获取AgentB返回
                connectSse(inChannel, token, sseParams);

            } catch (Exception e) {
                System.err.println("[AgentBAdapter] 处理失败: " + e.getMessage());
                writeA2AEvent(inChannel, A2AMessage.taskStatus(taskId, "failed", "处理失败: " + e.getMessage()));
                closeSseStream(inChannel);
            }
        });
    }

    /**
     * 给上游回SSE响应头
     */
    private void sendSseHeaders(Channel inChannel) {
        DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream");
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
        response.headers().set(HttpHeaderNames.CONNECTION, "keep-alive");
        response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, "chunked");
        inChannel.writeAndFlush(response);
    }

    /**
     * 第一步：调用apiToken获取token
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

    /**
     * 第二步：调用AgentB非SSE接口获取参数
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

    /**
     * 第三步：SSE连接下游AgentB
     */
    private void connectSse(Channel inChannel, String token, String sseParams) {
        EventLoopGroup group = new NioEventLoopGroup(1);
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast(new HttpClientCodec());
                        p.addLast(new SseClientHandler(inChannel, group));
                    }
                });

        bootstrap.connect(agentBHost, agentBPort).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                Channel outChannel = future.channel();
                // 发送SSE请求
                FullHttpRequest sseReq = new DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/sse?params=" + sseParams);
                sseReq.headers().set(HttpHeaderNames.HOST, agentBHost + ":" + agentBPort);
                sseReq.headers().set(HttpHeaderNames.ACCEPT, "text/event-stream");
                sseReq.headers().set(HttpHeaderNames.AUTHORIZATION, "Bearer " + token);
                outChannel.writeAndFlush(sseReq);
                System.out.println("[AgentBAdapter] SSE连接已建立");
            } else {
                System.err.println("[AgentBAdapter] SSE连接失败: " + future.cause().getMessage());
                writeA2AEvent(inChannel, A2AMessage.taskStatus(taskId, "failed", "SSE连接失败"));
                closeSseStream(inChannel);
                group.shutdownGracefully();
            }
        });
    }

    /**
     * SSE客户端Handler —— 接收下游AgentB的SSE分块
     */
    private class SseClientHandler extends ChannelInboundHandlerAdapter {

        private final Channel inChannel;
        private final EventLoopGroup group;

        SseClientHandler(Channel inChannel, EventLoopGroup group) {
            this.inChannel = inChannel;
            this.group = group;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof io.netty.handler.codec.http.HttpResponse resp) {
                System.out.println("[AgentBAdapter] SSE响应状态: " + resp.status());
            }

            if (msg instanceof HttpContent content) {
                String chunk = content.content().toString(CharsetUtil.UTF_8);
                processRawChunk(chunk, inChannel, ctx);
            }

            if (msg instanceof LastHttpContent) {
                System.out.println("[AgentBAdapter] SSE流结束");
                // 发送完成状态
                writeA2AEvent(inChannel, A2AMessage.taskStatus(taskId, "completed", null));
                closeSseStream(inChannel);
                ctx.close();
                group.shutdownGracefully();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            System.err.println("[AgentBAdapter] SSE异常: " + cause.getMessage());
            writeA2AEvent(inChannel, A2AMessage.taskStatus(taskId, "failed", "SSE异常: " + cause.getMessage()));
            closeSseStream(inChannel);
            ctx.close();
            group.shutdownGracefully();
        }
    }

    /**
     * 处理原始SSE数据（可能跨chunk）
     */
    private void processRawChunk(String rawChunk, Channel inChannel, ChannelHandlerContext ctx) {
        sseLineBuffer.append(rawChunk);

        // 按行拆分处理
        String buffered = sseLineBuffer.toString();
        int lastNewline = buffered.lastIndexOf("\n");
        if (lastNewline == -1) {
            return; // 还没有完整行，继续缓冲
        }

        String complete = buffered.substring(0, lastNewline + 1);
        String remaining = buffered.substring(lastNewline + 1);
        sseLineBuffer.setLength(0);
        sseLineBuffer.append(remaining);

        String[] lines = complete.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("data: ")) {
                String data = line.substring(6).trim();
                if ("[DONE]".equals(data)) {
                    continue; // 结束标记，由LastHttpContent处理
                }
                processSseData(data, inChannel);
            }
        }
    }

    /**
     * 处理单条SSE data
     * 核心逻辑：正常文本转A2A回写，#picture开始拼接，#end结束拼接调图片API
     */
    private void processSseData(String data, Channel inChannel) {
        try {
            JsonObject json = GSON.fromJson(data, JsonObject.class);
            String text = json.has("text") ? json.get("text").getAsString() : "";

            if (!accumulatingPicture) {
                // 正常模式
                if (PICTURE_START.matcher(text).find()) {
                    // 进入图片拼接模式
                    accumulatingPicture = true;
                    pictureBuffer.setLength(0);
                    System.out.println("[AgentBAdapter] 开始图片参数拼接");
                    writeA2AEvent(inChannel, A2AMessage.taskStatus(taskId, "working", "正在生成图片..."));
                } else {
                    // 普通文本，转A2A格式回写
                    writeA2AEvent(inChannel, A2AMessage.taskStatus(taskId, "working", text));
                }
            } else {
                // 图片拼接模式
                if (PICTURE_END.matcher(text).find()) {
                    // 拼接结束，调用图片API
                    accumulatingPicture = false;
                    String pictureParams = pictureBuffer.toString().trim();
                    System.out.println("[AgentBAdapter] 图片参数拼接完成: " + pictureParams);

                    // 调用图片API（模拟）
                    String imageUrl = callPictureApi(pictureParams);
                    writeA2AEvent(inChannel, A2AMessage.taskArtifact(taskId, "generated_image", imageUrl));
                } else {
                    // 继续拼接
                    pictureBuffer.append(text);
                    System.out.println("[AgentBAdapter] 拼接中: " + text);
                }
            }
        } catch (Exception e) {
            System.err.println("[AgentBAdapter] 解析SSE数据失败: " + data + ", error: " + e.getMessage());
        }
    }

    /**
     * 调用图片API（模拟 —— 实际场景替换为真实的图片生成API）
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

    /**
     * 写A2A事件到上游inChannel
     */
    private void writeA2AEvent(Channel inChannel, A2AMessage message) {
        if (inChannel.isActive()) {
            String sseData = message.toSseData();
            ByteBuf buf = Unpooled.copiedBuffer(sseData, StandardCharsets.UTF_8);
            inChannel.writeAndFlush(new DefaultHttpContent(buf));
            System.out.println("[AgentBAdapter] → 上游: " + sseData.trim());
        }
    }

    /**
     * 关闭SSE流
     */
    private void closeSseStream(Channel inChannel) {
        if (inChannel.isActive()) {
            inChannel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
                    .addListener(ChannelFutureListener.CLOSE);
        }
    }
}
