package com.familybutler.core.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型网关配置（butler.llm.*）：Key 只走环境变量 DEEPSEEK_API_KEY。
 * pricing 为参考价（元 / 百万 token），按官方调价改 yml 即可，不用动代码。
 */
@Component
@ConfigurationProperties(prefix = "butler.llm")
public class LlmProperties {

    private String deepseekApiKey = "";
    private String baseUrl = "https://api.deepseek.com";
    private String defaultModel = "deepseek-chat";
    private Map<String, Price> pricing = new LinkedHashMap<>();

    public static class Price {
        private BigDecimal inputPerMillion = BigDecimal.ZERO;
        private BigDecimal outputPerMillion = BigDecimal.ZERO;

        public BigDecimal getInputPerMillion() {
            return inputPerMillion;
        }

        public void setInputPerMillion(BigDecimal inputPerMillion) {
            this.inputPerMillion = inputPerMillion;
        }

        public BigDecimal getOutputPerMillion() {
            return outputPerMillion;
        }

        public void setOutputPerMillion(BigDecimal outputPerMillion) {
            this.outputPerMillion = outputPerMillion;
        }
    }

    public String getDeepseekApiKey() {
        return deepseekApiKey;
    }

    public void setDeepseekApiKey(String deepseekApiKey) {
        this.deepseekApiKey = deepseekApiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public Map<String, Price> getPricing() {
        return pricing;
    }

    public void setPricing(Map<String, Price> pricing) {
        this.pricing = pricing;
    }
}
