package com.example.gateway.adapter;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.FullHttpRequest;

/**
 * 协议适配器接口
 * 每次请求new一个实例，用完销毁，状态绑定在实例上
 */
public interface ProtocolAdapter {

    /**
     * 是否支持该agent的适配
     */
    boolean supports(String agentId);

    /**
     * 执行适配逻辑
     * @param inChannel 上游通道（写回A2A响应给调用方）
     * @param request   原始HTTP请求
     * @param agentId   目标agentId
     */
    void handle(Channel inChannel, FullHttpRequest request, String agentId);
}
