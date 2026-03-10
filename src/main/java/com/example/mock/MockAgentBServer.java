package com.example.mock;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 模拟下游AgentB服务
 * 监听9090端口，提供：
 * - POST /api/token   → 返回token
 * - POST /api/params  → 返回SSE调用参数
 * - GET  /api/sse     → SSE流式返回（含#picture...#end）
 * - POST /api/picture → 模拟图片生成API
 */
public class MockAgentBServer {

    private static final int PORT = 9090;

    public static void main(String[] args) throws Exception {
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(new HttpServerCodec());
                            ch.pipeline().addLast(new HttpObjectAggregator(65536));
                            ch.pipeline().addLast(new MockHandler());
                        }
                    });

            ChannelFuture f = b.bind(PORT).sync();
            System.out.println("=================================");
            System.out.println("Mock AgentB 启动成功，端口: " + PORT);
            System.out.println("=================================");
            f.channel().closeFuture().sync();
        } finally {
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }

    static class MockHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!(msg instanceof FullHttpRequest request)) {
                return;
            }

            String uri = request.uri();
            String path = new QueryStringDecoder(uri).path();
            System.out.println("[MockAgentB] 收到请求: " + request.method() + " " + path);

            switch (path) {
                case "/api/token" -> handleToken(ctx);
                case "/api/params" -> handleParams(ctx);
                case "/api/sse" -> handleSse(ctx);
                case "/api/picture" -> handlePicture(ctx, request);
                default -> sendJson(ctx, HttpResponseStatus.NOT_FOUND, "{\"error\":\"not found\"}");
            }
        }

        /**
         * POST /api/token → 返回token
         */
        private void handleToken(ChannelHandlerContext ctx) {
            System.out.println("[MockAgentB] 发放token");
            sendJson(ctx, HttpResponseStatus.OK, "{\"token\":\"mock-token-abc123\",\"expiresIn\":3600}");
        }

        /**
         * POST /api/params → 返回SSE调用参数
         */
        private void handleParams(ChannelHandlerContext ctx) {
            System.out.println("[MockAgentB] 返回SSE参数");
            sendJson(ctx, HttpResponseStatus.OK,
                    "{\"sessionId\":\"sess-001\",\"model\":\"agent-b-v1\",\"stream\":true}");
        }

        /**
         * GET /api/sse → 模拟SSE流式返回
         * 包含普通文本 + #picture...#end 场景
         */
        private void handleSse(ChannelHandlerContext ctx) {
            System.out.println("[MockAgentB] 开始SSE流式返回");

            // 发SSE响应头
            DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream");
            response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
            response.headers().set(HttpHeaderNames.CONNECTION, "keep-alive");
            response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, "chunked");
            ctx.writeAndFlush(response);

            // 模拟SSE分块数据，每500ms发一条
            String[] sseEvents = {
                    "data: {\"text\": \"你好，我是AgentB\"}\n\n",
                    "data: {\"text\": \"这是第一段分析结果\"}\n\n",
                    "data: {\"text\": \"这是第二段分析结果\"}\n\n",
                    "data: {\"text\": \"#picture\"}\n\n",           // 开始图片拼接
                    "data: {\"text\": \"prompt=一只可爱的猫咪\"}\n\n",  // 图片参数1
                    "data: {\"text\": \"&style=水彩画\"}\n\n",       // 图片参数2
                    "data: {\"text\": \"&size=1024x1024\"}\n\n",   // 图片参数3
                    "data: {\"text\": \"#end\"}\n\n",              // 结束图片拼接
                    "data: {\"text\": \"以上是为你生成的图片\"}\n\n",
                    "data: {\"text\": \"还有什么我可以帮你的吗？\"}\n\n",
                    "data: [DONE]\n\n"
            };

            for (int i = 0; i < sseEvents.length; i++) {
                final int index = i;
                ctx.executor().schedule(() -> {
                    if (ctx.channel().isActive()) {
                        ByteBuf buf = Unpooled.copiedBuffer(sseEvents[index], StandardCharsets.UTF_8);
                        ctx.writeAndFlush(new DefaultHttpContent(buf));
                        System.out.println("[MockAgentB] 发送SSE: " + sseEvents[index].trim());

                        // 最后一条发完后关闭
                        if (index == sseEvents.length - 1) {
                            ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
                        }
                    }
                }, (i + 1) * 500L, TimeUnit.MILLISECONDS);
            }
        }

        /**
         * POST /api/picture → 模拟图片生成
         */
        private void handlePicture(ChannelHandlerContext ctx, FullHttpRequest request) {
            String body = request.content().toString(CharsetUtil.UTF_8);
            System.out.println("[MockAgentB] 图片生成请求: " + body);
            sendJson(ctx, HttpResponseStatus.OK,
                    "{\"url\":\"https://image.example.com/generated/cat-watercolor-1024.png\",\"status\":\"success\"}");
        }

        private void sendJson(ChannelHandlerContext ctx, HttpResponseStatus status, String json) {
            ByteBuf content = Unpooled.copiedBuffer(json, StandardCharsets.UTF_8);
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content);
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            System.err.println("[MockAgentB] 异常: " + cause.getMessage());
            ctx.close();
        }
    }
}
