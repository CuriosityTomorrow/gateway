package com.example.gateway.model;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * A2A协议消息封装
 */
public class A2AMessage {

    private static final Gson GSON = new Gson();

    private String jsonrpc = "2.0";
    private String method;
    private JsonObject params;

    public static A2AMessage taskStatus(String taskId, String state, String text) {
        A2AMessage msg = new A2AMessage();
        msg.method = "tasks/status";

        JsonObject params = new JsonObject();
        params.addProperty("id", taskId);

        JsonObject status = new JsonObject();
        status.addProperty("state", state);

        if (text != null) {
            JsonObject message = new JsonObject();
            message.addProperty("role", "agent");

            JsonObject part = new JsonObject();
            part.addProperty("type", "text");
            part.addProperty("text", text);

            com.google.gson.JsonArray parts = new com.google.gson.JsonArray();
            parts.add(part);
            message.add("parts", parts);
            status.add("message", message);
        }

        params.add("status", status);
        msg.params = params;
        return msg;
    }

    public static A2AMessage taskArtifact(String taskId, String name, String url) {
        A2AMessage msg = new A2AMessage();
        msg.method = "tasks/artifact";

        JsonObject params = new JsonObject();
        params.addProperty("id", taskId);

        JsonObject artifact = new JsonObject();
        artifact.addProperty("name", name);

        JsonObject part = new JsonObject();
        part.addProperty("type", "file");
        part.addProperty("url", url);

        com.google.gson.JsonArray parts = new com.google.gson.JsonArray();
        parts.add(part);
        artifact.add("parts", parts);

        params.add("artifact", artifact);
        msg.params = params;
        return msg;
    }

    public String toSseData() {
        return "data: " + GSON.toJson(this) + "\n\n";
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    // getters/setters
    public String getJsonrpc() { return jsonrpc; }
    public String getMethod() { return method; }
    public JsonObject getParams() { return params; }
}
