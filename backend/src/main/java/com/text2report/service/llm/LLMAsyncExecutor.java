package com.text2report.service.llm;

import com.text2report.common.exception.BusinessException;
import com.text2report.common.exception.ErrorCode;
import com.text2report.domain.meta.entity.MetaLlmProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Async executor for LLM HTTP calls.
 *
 * This is a separate Spring Bean to ensure @Async proxy works correctly.
 * Self-invocation within the same bean bypasses the proxy and @Async has no effect.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LLMAsyncExecutor {

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    @Async("llmExecutor")
    public CompletableFuture<String> callAsync(String requestBody, MetaLlmProvider provider, String secret) {
        try {
            int timeoutSeconds = provider.getTimeoutSeconds() != null ? provider.getTimeoutSeconds() : 60;
            String url = buildApiUrl(resolveBaseUrl(provider), resolveEndpointPath(provider));

            okhttp3.RequestBody body = okhttp3.RequestBody.create(
                    requestBody,
                    okhttp3.MediaType.parse("application/json; charset=utf-8")
            );

            okhttp3.Request.Builder builder = new okhttp3.Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .post(body);

            if (secret != null && !secret.isBlank() && !"NONE".equalsIgnoreCase(provider.getAuthType())) {
                builder.addHeader("Authorization", "Bearer " + secret);
            }

            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                    .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                    .readTimeout(Duration.ofSeconds(timeoutSeconds))
                    .writeTimeout(Duration.ofSeconds(timeoutSeconds))
                    .build();

            try (okhttp3.Response response = client.newCall(builder.build()).execute()) {
                if (!response.isSuccessful()) {
                    throw new BusinessException(ErrorCode.LLM_ERROR,
                            "LLM API error: HTTP " + response.code() + " - " + response.message());
                }

                String responseBody = response.body() != null ? response.body().string() : "";
                if (responseBody.isEmpty()) {
                    throw new BusinessException(ErrorCode.LLM_ERROR, "Empty response from LLM");
                }

                return CompletableFuture.completedFuture(responseBody);
            }
        } catch (BusinessException e) {
            return CompletableFuture.failedFuture(e);
        } catch (Exception e) {
            log.error("LLM call failed: {}", e.getMessage(), e);
            return CompletableFuture.failedFuture(
                    new BusinessException(ErrorCode.LLM_ERROR, "LLM call failed: " + e.getMessage()));
        }
    }

    /**
     * Build API URL avoiding double slashes.
     */
    private String buildApiUrl(String baseUrl, String path) {
        String cleanBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String cleanPath = path.startsWith("/") ? path : "/" + path;
        return cleanBase + cleanPath;
    }

    private String resolveBaseUrl(MetaLlmProvider provider) {
        if (provider.getBaseUrl() != null && !provider.getBaseUrl().isBlank()) {
            return provider.getBaseUrl();
        }
        String host = provider.getHost() != null ? provider.getHost() : "";
        if (provider.getPort() == null) {
            return host;
        }
        return host + ":" + provider.getPort();
    }

    private String resolveEndpointPath(MetaLlmProvider provider) {
        return provider.getEndpointPath() != null && !provider.getEndpointPath().isBlank()
                ? provider.getEndpointPath()
                : CHAT_COMPLETIONS_PATH;
    }
}
