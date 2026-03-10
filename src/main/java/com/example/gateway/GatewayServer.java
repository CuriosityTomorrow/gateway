package com.example.gateway;

import com.example.gateway.handler.AdapterHandler;
import com.example.gateway.handler.MsgRouteHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;

/**
 * A2A网关服务器
 * 监听8080端口，接收上游A2A请求
 *
 * Pipeline: HttpServerCodec → HttpObjectAggregator → AdapterHandler → MsgRouteHandler
 */
public class GatewayServer {

    private static final int PORT = 8080;
    // 下游服务地址（Mock AgentB / 下游A2A agent）
    private static final String DOWNSTREAM_HOST = "127.0.0.1";
    private static final int DOWNSTREAM_PORT = 9090;

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
                            ChannelPipeline p = ch.pipeline();
                            p.addLast(new HttpServerCodec());
                            p.addLast(new HttpObjectAggregator(65536));
                            // AdapterHandler在前，拦截非A2A请求
                            p.addLast(new AdapterHandler(DOWNSTREAM_HOST, DOWNSTREAM_PORT));
                            // MsgRouteHandler处理正常A2A代理
                            p.addLast(new MsgRouteHandler(DOWNSTREAM_HOST, DOWNSTREAM_PORT));
                        }
                    })
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true);

            ChannelFuture f = b.bind(PORT).sync();
            System.out.println("=================================");
            System.out.println("A2A Gateway 启动成功，端口: " + PORT);
            System.out.println("下游服务地址: " + DOWNSTREAM_HOST + ":" + DOWNSTREAM_PORT);
            System.out.println("=================================");
            f.channel().closeFuture().sync();
        } finally {
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }
}
