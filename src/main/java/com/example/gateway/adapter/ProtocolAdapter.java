package com.example.gateway.adapter;

import io.netty.handler.codec.http.FullHttpRequest;

/**
 * 协议适配器接口
 *
 * 设计要点：
 * 1. 每次请求new一个实例，状态绑定在实例上，用完由GC回收
 * 2. 通过ResponseWriter回写数据，不直接操作Netty Channel
 *    —— 这是为了和传输层解耦，未来拆微服务时Adapter代码不需要改
 * 3. 每个Adapter自由管理自己的下游调用流程，不遵循Backend Bootstrap的Action模式
 *    —— 因为非A2A协议的调用步骤和状态管理差异太大，无法用统一的Action抽象
 *
 * 新增Adapter的步骤：
 *   1. 实现此接口
 *   2. 在AdapterHandler.createAdapter()中注册
 *   3. 在AdapterHandler.NON_A2A_AGENTS中添加agentId
 */
public interface ProtocolAdapter {

    /**
     * 是否支持该agent的适配
     */
    boolean supports(String agentId);

    /**
     * 执行适配逻辑
     *
     * @param request  原始HTTP请求
     * @param agentId  目标agentId
     * @param writer   响应写入器 —— 通过writer回写A2A格式的SSE事件给上游
     *                 Adapter不需要关心数据最终写到哪里（Channel？HTTP Response？MQ？）
     *                 只管调用writer.onEvent()、writer.onComplete()、writer.onError()
     */
    void handle(FullHttpRequest request, String agentId, ResponseWriter writer);
}
