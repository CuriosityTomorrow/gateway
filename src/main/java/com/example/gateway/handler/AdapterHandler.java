package com.example.gateway.handler;

import com.example.gateway.adapter.AgentBAdapter;
import com.example.gateway.adapter.ChannelResponseWriter;
import com.example.gateway.adapter.ProtocolAdapter;
import com.example.gateway.adapter.ResponseWriter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.util.Set;

/**
 * AdapterHandler —— 非A2A请求的拦截与分发
 *
 * 在Netty Pipeline中位于MsgRouteHandler之前：
 *   HttpServerCodec → HttpObjectAggregator → [AdapterHandler] → MsgRouteHandler
 *
 * 工作逻辑：
 *   1. 从请求URL中解析agentId（格式：/a2a/{agentId}）
 *   2. 判断该agentId是否在NON_A2A_AGENTS集合中
 *   3. 非A2A → new对应的Adapter + ChannelResponseWriter，交给Adapter处理，不往下传
 *   4. A2A → ctx.fireChannelRead(msg)透传给MsgRouteHandler
 *
 * 与MsgRouteHandler的解耦：
 *   - 非A2A请求被本Handler拦截，MsgRouteHandler根本收不到
 *   - 两个Handler之间没有共享状态、没有上下文依赖
 *   - 删掉本Handler，MsgRouteHandler行为不变；删掉MsgRouteHandler，本Handler也能独立工作
 *
 * 未来拆微服务时的变化：
 *   当前（嵌入式）：new Adapter() + new ChannelResponseWriter(inChannel)
 *   未来（微服务）：把请求转发给协议转换服务，协议转换服务返回A2A SSE流直接透传
 *   本Handler只需要把"本地路由"改为"HTTP转发"，Adapter代码搬到协议转换服务中即可
 */
public class AdapterHandler extends ChannelInboundHandlerAdapter {

    /**
     * 非A2A agent列表
     * 实际生产环境从配置中心或数据库获取，这里硬编码作为demo
     */
    private static final Set<String> NON_A2A_AGENTS = Set.of("agentB");

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

        String uri = request.uri();
        String agentId = extractAgentId(uri);

        if (agentId != null && NON_A2A_AGENTS.contains(agentId)) {
            System.out.println("[AdapterHandler] 检测到非A2A agent: " + agentId + "，启用适配器");

            // ====== 核心：每次请求new一个Adapter实例 ======
            // 状态（token、拼接缓冲等）绑定在实例上，请求结束GC回收
            ProtocolAdapter adapter = createAdapter(agentId);
            if (adapter != null) {
                // 创建ResponseWriter —— 当前使用ChannelResponseWriter直接写inChannel
                // 未来拆微服务时，这里换成HttpResponseWriter即可
                ResponseWriter writer = new ChannelResponseWriter(ctx.channel());

                // 交给Adapter处理，不再调用ctx.fireChannelRead()
                // 即MsgRouteHandler不会收到这个请求
                adapter.handle(request, agentId, writer);
                return;
            }
        }

        // A2A agent → 透传给MsgRouteHandler，本Handler完全不介入
        System.out.println("[AdapterHandler] A2A agent: " + agentId + "，透传到MsgRouteHandler");
        ctx.fireChannelRead(msg);
    }

    /**
     * Adapter工厂方法
     *
     * 新增agent适配器时，在这里加一个分支即可。
     * 未来可以优化为SPI机制或注册表模式，实现自动发现。
     */
    private ProtocolAdapter createAdapter(String agentId) {
        if ("agentB".equals(agentId)) {
            return new AgentBAdapter(downstreamHost, downstreamPort);
        }
        // 未来新增:
        // if ("agentC".equals(agentId)) {
        //     return new AgentCAdapter(...);
        // }
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
