package com.example.gateway.handler;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;

import java.nio.charset.StandardCharsets;

/**
 * MsgRouteHandler - 正常A2A SSE代理
 * 上游agent(A2A) → Gateway(inChannel) → 下游agent(A2A)(outChannel)
 * 下游SSE分块直接回写给上游
 */
public class MsgRouteHandler extends ChannelInboundHandlerAdapter {

    // 模拟下游A2A agent地址（实际场景从注册中心或配置获取）
    private final String downstreamHost;
    private final int downstreamPort;

    public MsgRouteHandler(String downstreamHost, int downstreamPort) {
        this.downstreamHost = downstreamHost;
        this.downstreamPort = downstreamPort;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof FullHttpRequest request)) {
            return;
        }

        Channel inChannel = ctx.channel();
        String uri = request.uri();
        System.out.println("[MsgRouteHandler] 处理A2A请求: " + uri);

        // 给上游先回SSE头
        DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream");
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
        response.headers().set(HttpHeaderNames.CONNECTION, "keep-alive");
        response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, "chunked");
        inChannel.writeAndFlush(response);

        // 建立到下游agent的连接（outChannel）
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(inChannel.eventLoop())
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new HttpClientCodec());
                        // 下游SSE分块直接回写给上游inChannel
                        ch.pipeline().addLast(new A2AProxyHandler(inChannel));
                    }
                });

        bootstrap.connect(downstreamHost, downstreamPort).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                Channel outChannel = future.channel();
                System.out.println("[MsgRouteHandler] 下游连接建立成功");

                // 把原始请求转发给下游
                String body = request.content().toString(CharsetUtil.UTF_8);
                FullHttpRequest downstreamReq = new DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_1, HttpMethod.POST, "/a2a",
                        Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
                downstreamReq.headers().set(HttpHeaderNames.HOST, downstreamHost + ":" + downstreamPort);
                downstreamReq.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
                downstreamReq.headers().set(HttpHeaderNames.ACCEPT, "text/event-stream");
                downstreamReq.headers().set(HttpHeaderNames.CONTENT_LENGTH, downstreamReq.content().readableBytes());
                outChannel.writeAndFlush(downstreamReq);
            } else {
                System.err.println("[MsgRouteHandler] 下游连接失败: " + future.cause().getMessage());
                String error = "data: {\"error\":\"downstream connection failed\"}\n\n";
                inChannel.writeAndFlush(new DefaultHttpContent(
                        Unpooled.copiedBuffer(error, StandardCharsets.UTF_8)));
                inChannel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
                        .addListener(ChannelFutureListener.CLOSE);
            }
        });
    }

    /**
     * A2A代理Handler —— 下游SSE分块直接透传给上游
     */
    private static class A2AProxyHandler extends ChannelInboundHandlerAdapter {

        private final Channel inChannel;

        A2AProxyHandler(Channel inChannel) {
            this.inChannel = inChannel;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof HttpResponse) {
                // 忽略下游响应头，上游已经发了SSE头
                System.out.println("[A2AProxy] 下游响应头收到");
            }

            if (msg instanceof HttpContent content) {
                ByteBuf buf = content.content();
                if (buf.readableBytes() > 0) {
                    // 直接把下游的SSE数据透传给上游
                    inChannel.writeAndFlush(new DefaultHttpContent(buf.retain()));
                    System.out.println("[A2AProxy] 透传数据: " + buf.toString(CharsetUtil.UTF_8).trim());
                }
            }

            if (msg instanceof LastHttpContent) {
                System.out.println("[A2AProxy] 下游流结束");
                inChannel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
                        .addListener(ChannelFutureListener.CLOSE);
                ctx.close();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            System.err.println("[A2AProxy] 异常: " + cause.getMessage());
            inChannel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
                    .addListener(ChannelFutureListener.CLOSE);
            ctx.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.err.println("[MsgRouteHandler] 异常: " + cause.getMessage());
        ctx.close();
    }
}
