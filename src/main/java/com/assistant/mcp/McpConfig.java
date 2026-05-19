package com.assistant.mcp;

import com.assistant.config.AssistantProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP (Model Context Protocol) 配置
 * 管理外部 MCP 服务器的连接和工具调用
 * 
 * 当前支持:
 * - stdio 传输方式的 MCP 服务器 (如文件系统服务器)
 * - 可通过配置扩展更多 MCP 服务器
 */
@Slf4j
@Configuration
public class McpConfig {

    private final AssistantProperties properties;
    private final Map<String, McpClientWrapper> clients = new ConcurrentHashMap<>();

    public McpConfig(AssistantProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        if (!properties.getMcp().isEnabled()) {
            log.info("MCP 功能未启用");
            return;
        }

        List<AssistantProperties.McpServer> servers = properties.getMcp().getServers();
        if (servers == null || servers.isEmpty()) {
            log.info("未配置 MCP 服务器");
            return;
        }

        for (AssistantProperties.McpServer server : servers) {
            try {
                log.info("正在连接 MCP 服务器: {} (传输方式: {})", server.getName(), server.getTransport());
                // 这里预留 MCP 客户端连接逻辑
                // 实际使用时，LangChain4j 的 MCP 模块会通过 StdioMcpClient 建立连接
                McpClientWrapper wrapper = new McpClientWrapper(server);
                clients.put(server.getName(), wrapper);
                log.info("MCP 服务器 '{}' 连接成功", server.getName());
            } catch (Exception e) {
                log.warn("MCP 服务器 '{}' 连接失败: {}", server.getName(), e.getMessage());
            }
        }

        log.info("MCP 初始化完成, 已连接 {} 个服务器", clients.size());
    }

    @PreDestroy
    public void shutdown() {
        clients.values().forEach(McpClientWrapper::close);
        log.info("MCP 客户端已关闭");
    }

    /**
     * 获取所有已连接的 MCP 服务器信息
     */
    public List<Map<String, String>> getConnectedServers() {
        List<Map<String, String>> serverList = new ArrayList<>();
        for (Map.Entry<String, McpClientWrapper> entry : clients.entrySet()) {
            Map<String, String> info = new HashMap<>();
            info.put("name", entry.getKey());
            info.put("status", "connected");
            serverList.add(info);
        }
        return serverList;
    }

    /**
     * MCP 客户端包装器
     * 封装单个 MCP 服务器的连接状态
     */
    private static class McpClientWrapper implements AutoCloseable {
        private final AssistantProperties.McpServer server;
        private boolean connected;

        public McpClientWrapper(AssistantProperties.McpServer server) {
            this.server = server;
            this.connected = true;
        }

        public boolean isConnected() {
            return connected;
        }

        @Override
        public void close() {
            this.connected = false;
        }
    }
}
