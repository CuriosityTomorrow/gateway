package com.example.gateway.adapter;

import com.example.gateway.model.A2AMessage;

/**
 * ResponseWriter —— Adapter与传输层的解耦接口
 *
 * 设计目的：
 *   Adapter的业务逻辑（token获取、协议转换、状态机处理等）不应该直接依赖Netty Channel。
 *   通过这个接口，Adapter只关心"产出什么数据"，不关心"数据怎么传给上游"。
 *
 * 当前实现：
 *   ChannelResponseWriter —— 直接写Netty的inChannel（嵌入Gateway进程时使用）
 *
 * 未来拆微服务时：
 *   HttpResponseWriter —— 写HTTP Response（协议转换服务独立部署时使用）
 *   Adapter的业务代码一行不改，只需要换ResponseWriter的实现。
 */
public interface ResponseWriter {

    /**
     * 发送SSE响应头
     * 必须在第一次onEvent之前调用，告诉上游"我要开始SSE流了"
     */
    void sendHeaders();

    /**
     * 发送一条A2A事件
     * 将A2AMessage序列化为SSE格式（data: {...}\n\n）写给上游
     *
     * @param message A2A格式的消息（tasks/status 或 tasks/artifact）
     */
    void onEvent(A2AMessage message);

    /**
     * SSE流正常结束
     * 发送完成状态并关闭连接
     */
    void onComplete();

    /**
     * SSE流异常结束
     * 发送错误信息并关闭连接
     *
     * @param error 错误描述
     */
    void onError(String error);
}
