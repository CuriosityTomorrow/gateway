package com.example.gateway.handler;

import com.example.gateway.adapter.AgentBAdapter;
import com.example.gateway.adapter.ProtocolAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.util.List;
import java.util.Set;

/**
 * AdapterHandler - 在MsgRouteHandler前拦截非A2A请求
 * 判断目标agent是否为非A2A协议，是则new出对应adapter处理，否则透传给MsgRouteHandler
 */
public class AdapterHandler extends ChannelInboundHandlerAdapter {

    // 非A2A agent列表（实际场景从配置或数据库获取）
    private static final Set<String> NON_A2A_AGENTS = Set.of("agentB");

    // adapter工厂配置（实际场景可以用SPI或注册表）
    private final String downstreamHost;
    private final int downstreamPort;

    public AdapterHandler(String downstreamHost, int downstreamPort) {
        this.downstreamHost = downstreamHost;
        this.downstreamPort = downstreamPort;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof FullHttpRequest request)) {
            ctx.fireChannelRead(msg);
            return;
        }

        // 从URL中解析agentId: /a2a/{agentId}
        String uri = request.uri();
        String agentId = extractAgentId(uri);

        if (agentId != null && NON_A2A_AGENTS.contains(agentId)) {
            System.out.println("[AdapterHandler] 检测到非A2A agent: " + agentId + "，启用适配器");

            // 每次请求new一个adapter，状态绑定在实例上，用完即销毁
            ProtocolAdapter adapter = createAdapter(agentId);
            if (adapter != null) {
                // adapter接管处理，不再往下传递
                adapter.handle(ctx.channel(), request, agentId);
                return;
            }
        }

        // A2A agent，透传给MsgRouteHandler
        System.out.println("[AdapterHandler] A2A agent: " + agentId + "，透传到MsgRouteHandler");
        ctx.fireChannelRead(msg);
    }

    /**
     * 创建对应的adapter实例
     */
    private ProtocolAdapter createAdapter(String agentId) {
        // 未来五花八门的适配器在这里加
        if ("agentB".equals(agentId)) {
            return new AgentBAdapter(downstreamHost, downstreamPort);
        }
        // 未来: agentC, agentD, ...
        return null;
    }

    /**
     * 从URI中提取agentId
     * 格式: /a2a/{agentId} 或 /a2a/{agentId}?xxx=yyy
     */
    private String extractAgentId(String uri) {
        QueryStringDecoder decoder = new QueryStringDecoder(uri);
        String path = decoder.path();
        if (path.startsWith("/a2a/")) {
            return path.substring(5);
        }
        return null;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.err.println("[AdapterHandler] 异常: " + cause.getMessage());
        ctx.close();
    }
}
