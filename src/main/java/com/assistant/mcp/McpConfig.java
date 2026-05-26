package com.assistant.mcp;

import com.assistant.config.AssistantProperties;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.service.tool.ToolProvider;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Configuration
public class McpConfig {

    private final AssistantProperties properties;
    private final List<McpClient> clients = new ArrayList<>();

    public McpConfig(AssistantProperties properties) {
        this.properties = properties;
    }

    @Bean
    public ToolProvider mcpToolProvider() {
        if (!properties.getMcp().isEnabled()) {
            log.info("MCP disabled, no MCP tools registered");
            return McpToolProvider.builder().build();
        }

        List<AssistantProperties.McpServer> servers = properties.getMcp().getServers();
        if (servers == null || servers.isEmpty()) {
            log.info("No MCP servers configured");
            return McpToolProvider.builder().build();
        }

        List<McpClient> mcpClients = new ArrayList<>();

        for (AssistantProperties.McpServer server : servers) {
            try {
                List<String> command = new ArrayList<>();
                command.add(server.getCommand());
                if (server.getArgs() != null) {
                    command.addAll(server.getArgs());
                }

                StdioMcpTransport transport = StdioMcpTransport.builder()
                        .command(command)
                        .build();

                McpClient client = DefaultMcpClient.builder()
                        .transport(transport)
                        .key(server.getName())
                        .clientName(server.getName())
                        .build();

                mcpClients.add(client);
                clients.add(client);
                log.info("MCP server '{}' connected via stdio", server.getName());
            } catch (Exception e) {
                log.warn("Failed to connect MCP server '{}': {}", server.getName(), e.getMessage());
            }
        }

        if (mcpClients.isEmpty()) {
            log.warn("All MCP servers failed to connect");
            return McpToolProvider.builder().build();
        }

        log.info("MCP initialized with {} server(s)", mcpClients.size());
        return McpToolProvider.builder()
                .mcpClients(mcpClients)
                .failIfOneServerFails(false)
                .build();
    }

    public List<Map<String, String>> getConnectedServers() {
        List<Map<String, String>> serverList = new ArrayList<>();
        for (McpClient client : clients) {
            Map<String, String> info = new HashMap<>();
            info.put("name", client.key());
            info.put("status", "connected");
            serverList.add(info);
        }
        return serverList;
    }

    @PreDestroy
    public void shutdown() {
        for (McpClient client : clients) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("Error closing MCP client: {}", e.getMessage());
            }
        }
        log.info("MCP clients shut down");
    }
}
