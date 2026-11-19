package org.example.capstone;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;

import java.time.Duration;

/** Builds the API client with a request timeout and a small retry count, so a hung request cannot stall a run. */
public final class Clients {

    /** One request may take this long (a search request with thinking takes 5-25 s in practice). */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(90);
    /** Connection errors, timeouts, 429 and 5xx are retried this many times by the SDK. */
    static final int MAX_RETRIES = 1;

    private Clients() {}

    /** Reads ANTHROPIC_API_KEY from the environment. */
    public static AnthropicClient fromEnv() {
        return AnthropicOkHttpClient.builder().fromEnv().timeout(REQUEST_TIMEOUT).maxRetries(MAX_RETRIES).build();
    }

    /** For fault tests: a client that points somewhere else (for example a port nobody listens on). */
    public static AnthropicClient forTest(String baseUrl) {
        return AnthropicOkHttpClient.builder().apiKey("test-key").baseUrl(baseUrl)
                .timeout(Duration.ofSeconds(5)).maxRetries(0).build();
    }
}
