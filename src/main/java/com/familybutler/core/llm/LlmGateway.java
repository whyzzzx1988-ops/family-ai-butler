package com.familybutler.core.llm;

import com.familybutler.core.jdbc.GeneratedKey;

import com.familybutler.core.account.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;

/**
 * 模型网关（底座，D7 落地）：DeepSeek 官方 API（OpenAI 兼容协议），主力单商，
 * 底座期不做多商回退——失败透明报错，并且成功失败都落 llm_call_log（验收标准 5）。
 * 成本按 butler.llm.pricing 的参考价（元/百万 token）折算，价格可配不写死。
 */
@Service
public class LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(LlmGateway.class);

    private final LlmProperties properties;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public LlmGateway(LlmProperties properties, JdbcTemplate jdbc) {
        this.properties = properties;
        this.jdbc = jdbc;
    }

    public record ChatResult(String content, String model, int tokensIn, int tokensOut,
                             BigDecimal cost, long logId) {
    }

    public record UsageRow(String model, long calls, long tokensIn, long tokensOut, BigDecimal cost) {
    }

    public boolean isConfigured() {
        return properties.getDeepseekApiKey() != null && !properties.getDeepseekApiKey().isBlank();
    }

    /**
     * 一次聊天补全。无论成败都先落账再返回/抛错：
     * 成功 status=ok 带真实 token 与成本；失败 status=error、token 记 0，异常透明上抛。
     */
    public ChatResult chat(Long familyId, String module, String systemPrompt, String userPrompt) {
        if (!isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "LLM 未配置：请设置环境变量 DEEPSEEK_API_KEY");
        }
        String model = properties.getDefaultModel();
        long startNanos = System.nanoTime();
        try {
            ObjectNode requestBody = mapper.createObjectNode();
            requestBody.put("model", model);
            requestBody.put("stream", false);
            ArrayNode messages = requestBody.putArray("messages");
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                ObjectNode sys = messages.addObject();
                sys.put("role", "system");
                sys.put("content", systemPrompt);
            }
            ObjectNode user = messages.addObject();
            user.put("role", "user");
            user.put("content", userPrompt);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getBaseUrl() + "/chat/completions"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + properties.getDeepseekApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(requestBody)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int latencyMs = (int) ((System.nanoTime() - startNanos) / 1_000_000);

            if (response.statusCode() != 200) {
                logCall(familyId, module, model, 0, 0, BigDecimal.ZERO, latencyMs, "error");
                String detail = response.body() == null ? "" : response.body();
                if (detail.length() > 300) {
                    detail = detail.substring(0, 300);
                }
                throw new ApiException(HttpStatus.BAD_GATEWAY,
                        "模型调用失败（HTTP " + response.statusCode() + "）：" + detail);
            }

            JsonNode root = mapper.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            int tokensIn = root.path("usage").path("prompt_tokens").asInt(0);
            int tokensOut = root.path("usage").path("completion_tokens").asInt(0);
            BigDecimal cost = costOf(model, tokensIn, tokensOut);
            long logId = logCall(familyId, module, model, tokensIn, tokensOut, cost, latencyMs, "ok");
            return new ChatResult(content, model, tokensIn, tokensOut, cost, logId);
        } catch (ApiException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            int latencyMs = (int) ((System.nanoTime() - startNanos) / 1_000_000);
            logCallQuietly(familyId, module, model, latencyMs);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "模型调用被中断");
        } catch (Exception e) {
            int latencyMs = (int) ((System.nanoTime() - startNanos) / 1_000_000);
            logCallQuietly(familyId, module, model, latencyMs);
            log.warn("模型调用异常", e);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "模型调用异常：" + e.getMessage());
        }
    }

    /** 本家庭的模型账：按模型汇总调用数、token 与成本（只算 status=ok 的成本，失败单列计数在 calls 外）。 */
    public List<UsageRow> usageSummary(long familyId) {
        return jdbc.query(
                "select model, count(*) as calls, coalesce(sum(tokens_in),0) as tin, "
                        + "coalesce(sum(tokens_out),0) as tout, coalesce(sum(cost),0) as cost "
                        + "from llm_call_log where family_id = ? and status = 'ok' group by model order by model",
                (rs, i) -> new UsageRow(rs.getString("model"), rs.getLong("calls"),
                        rs.getLong("tin"), rs.getLong("tout"), rs.getBigDecimal("cost")),
                familyId);
    }

    public long failedCalls(long familyId) {
        Long count = jdbc.queryForObject(
                "select count(*) from llm_call_log where family_id = ? and status = 'error'", Long.class, familyId);
        return count == null ? 0 : count;
    }

    private BigDecimal costOf(String model, int tokensIn, int tokensOut) {
        LlmProperties.Price price = properties.getPricing().get(model);
        if (price == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal in = price.getInputPerMillion().multiply(BigDecimal.valueOf(tokensIn));
        BigDecimal out = price.getOutputPerMillion().multiply(BigDecimal.valueOf(tokensOut));
        return in.add(out).divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
    }

    private void logCallQuietly(Long familyId, String module, String model, int latencyMs) {
        try {
            logCall(familyId, module, model, 0, 0, BigDecimal.ZERO, latencyMs, "error");
        } catch (Exception ignored) {
            // 记账失败不掩盖原始错误
        }
    }

    private long logCall(Long familyId, String module, String model, int tokensIn, int tokensOut,
                         BigDecimal cost, int latencyMs, String status) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "insert into llm_call_log(family_id, module, model, tokens_in, tokens_out, cost, latency_ms, status) "
                            + "values (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            if (familyId == null) {
                ps.setNull(1, java.sql.Types.BIGINT);
            } else {
                ps.setLong(1, familyId);
            }
            ps.setString(2, module);
            ps.setString(3, model);
            ps.setInt(4, tokensIn);
            ps.setInt(5, tokensOut);
            ps.setBigDecimal(6, cost);
            ps.setInt(7, latencyMs);
            ps.setString(8, status);
            return ps;
        }, keyHolder);
        Number key = GeneratedKey.id(keyHolder);
        return key == null ? -1 : key.longValue();
    }
}
