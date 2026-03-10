package com.example.gateway.adapter;

import com.example.gateway.model.A2AMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.*;

import java.nio.charset.StandardCharsets;

/**
 * ChannelResponseWriter —— 嵌入式（同进程）的ResponseWriter实现
 *
 * 直接操作Netty的inChannel，将A2A事件写回上游。
 * 当Adapter和Gateway在同一个进程中运行时使用此实现。
 *
 * 未来拆微服务时，会新增 HttpResponseWriter 实现，
 * 将数据写到HTTP Response而不是Netty Channel。
 * Adapter业务代码无需修改，只需在AdapterHandler中切换实现即可。
 */
public class ChannelResponseWriter implements ResponseWriter {

    private final Channel inChannel;

    public ChannelResponseWriter(Channel inChannel) {
        this.inChannel = inChannel;
    }

    /**
     * 发送SSE响应头
     * 设置Content-Type为text/event-stream，Transfer-Encoding为chunked
     * 这样后续的onEvent可以不断往通道里写数据块，上游按SSE流接收
     */
    @Override
    public void sendHeaders() {
        DefaultHttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream");
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
        response.headers().set(HttpHeaderNames.CONNECTION, "keep-alive");
        response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, "chunked");
        inChannel.writeAndFlush(response);
    }

    /**
     * 发送一条A2A事件
     * 将A2AMessage序列化为 "data: {json}\n\n" 格式，作为chunked的一个数据块写出
     */
    @Override
    public void onEvent(A2AMessage message) {
        if (inChannel.isActive()) {
            String sseData = message.toSseData();
            ByteBuf buf = Unpooled.copiedBuffer(sseData, StandardCharsets.UTF_8);
            inChannel.writeAndFlush(new DefaultHttpContent(buf));
            System.out.println("[ResponseWriter] → 上游: " + sseData.trim());
        }
    }

    /**
     * SSE流正常结束
     * 发送空的LastHttpContent作为chunked编码的结束标记，然后关闭连接
     */
    @Override
    public void onComplete() {
        if (inChannel.isActive()) {
            inChannel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
                    .addListener(ChannelFutureListener.CLOSE);
        }
    }

    /**
     * SSE流异常结束
     * 先发一条failed状态的A2A事件通知上游出错了，然后关闭连接
     */
    @Override
    public void onError(String error) {
        if (inChannel.isActive()) {
            // 用taskId="error"作为兜底，实际taskId由Adapter在onEvent中自行管理
            String errorData = "data: {\"error\":\"" + error + "\"}\n\n";
            ByteBuf buf = Unpooled.copiedBuffer(errorData, StandardCharsets.UTF_8);
            inChannel.writeAndFlush(new DefaultHttpContent(buf));
            inChannel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
                    .addListener(ChannelFutureListener.CLOSE);
        }
    }
}
