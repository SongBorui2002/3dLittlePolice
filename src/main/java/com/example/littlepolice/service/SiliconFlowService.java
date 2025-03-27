package com.example.littlepolice.service;

import com.example.littlepolice.api.SiliconFlowApi;
import com.example.littlepolice.model.SiliconFlowChatCompletionResult;
import com.example.littlepolice.model.SiliconFlowRequest;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import retrofit2.Retrofit;
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory;
import retrofit2.converter.jackson.JacksonConverterFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;

@Slf4j
@Service
public class SiliconFlowService implements DisposableBean {
    private final SiliconFlowApi api;
    private final ExecutorService executorService;

    public SiliconFlowService(@Value("${siliconflow.api.key}") String apiKey,
                              @Value("${siliconflow.api.url}") String apiUrl) {
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor(log::info);
        logging.setLevel(HttpLoggingInterceptor.Level.BASIC);

        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(logging)
                .addInterceptor(chain -> {
                    okhttp3.Request originalRequest = chain.request();
                    long startTime = System.currentTimeMillis();
                    okhttp3.Response response = null;
                    try {
                        okhttp3.Request newRequest = originalRequest.newBuilder()
                                .header("Authorization", "Bearer " + apiKey)
                                .header("Content-Type", "application/json")
                                .build();
                        response = chain.proceed(newRequest);

                        // 记录响应内容
//                        String responseBody = response.peekBody(Long.MAX_VALUE).string();
//                        log.info("API Response: {}", responseBody);

                        long endTime = System.currentTimeMillis();
                        log.info("API请求处理时间: {} 毫秒, URL: {}", endTime - startTime, originalRequest.url());
                        return response;
                    } catch (Exception e) {
                        log.error("API请求失败，耗时: {} 毫秒, URL: {}, 错误: {}",
                                System.currentTimeMillis() - startTime,
                                originalRequest.url(),
                                e.getMessage());
                        throw e;
                    }
                })
                .connectTimeout(Duration.ofSeconds(300))
                .readTimeout(Duration.ofSeconds(300))
                .writeTimeout(Duration.ofSeconds(300))
                .build();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(apiUrl)
                .client(client)
                .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
                .addConverterFactory(JacksonConverterFactory.create())
                .build();

        this.api = retrofit.create(SiliconFlowApi.class);
        this.executorService = Executors.newFixedThreadPool(20);
    }

    @Override
    public void destroy() throws Exception {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public String correctText(String text) {
        try {
            long startTime = System.currentTimeMillis();
            List<SiliconFlowRequest.Message> messages = Arrays.asList(
                    SiliconFlowRequest.Message.builder()
                            .role("system")
                            .content("你是一个中文语法检查助手，专门负责修正文本中的'的得地'用法。请只返回修改后的文本，不需要解释。请注意只修改“的得地”的错误用法，无需修正其他文字错误。可以适当结合每句话前一句与后一句的上下文，正确使用中文的“的得地”。")
                            .build(),
                    SiliconFlowRequest.Message.builder()
                            .role("user")
                            .content(text)
                            .build()
            );

            SiliconFlowRequest request = SiliconFlowRequest.builder()
                    .model("Pro/deepseek-ai/DeepSeek-V3")
                    .messages(messages)
                    .stream(false)
                    .maxTokens(4096)
                    .temperature(0.3)
                    .topP(0.3)
                    .topK(50)
                    .frequencyPenalty(0.5)
                    .n(1)
                    .build();

            log.info("开始发送API请求，文本长度: {}", text.length());
//            log.info("请求内容: {}", request);

            SiliconFlowChatCompletionResult result = api.createChatCompletion(request)
                    .blockingGet();

//            log.info("API返回结果: {}", result);

            String correctedText = result.getChoices().get(0).getMessage().getContent();
            long endTime = System.currentTimeMillis();
            log.info("API请求完成，耗时: {}ms", endTime - startTime);

//            // 添加响应内容的日志
//            log.info("API响应内容长度: {}", correctedText.length());
//            log.info("API响应内容是否包含分隔符: {}", correctedText.contains("\n---\n"));

            return correctedText;
        } catch (Exception e) {
            log.error("调用SiliconFlow API时发生错误", e);
            throw new RuntimeException("处理请求时发生错误: " + e.getMessage());
        }
    }

    public List<String> correctTextsParallel(List<String> texts) {
        try {
            log.info("开始并行处理 {} 个批次", texts.size());
            List<CompletableFuture<String>> futures = new ArrayList<>();

            // 为每个文本创建一个CompletableFuture
            for (int i = 0; i < texts.size(); i++) {
                final int index = i;
                final String text = texts.get(i);

                CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                    log.info("开始处理第 {}/{} 个批次", index + 1, texts.size());
                    try {
                        String result = correctText(text);
                        log.info("第 {}/{} 个批次处理完成", index + 1, texts.size());
                        return result;
                    } catch (Exception e) {
                        log.error("处理第 {}/{} 个批次时发生错误: {}", index + 1, texts.size(), e.getMessage());
                        return text; // 发生错误时返回原文
                    }
                }, executorService);

                futures.add(future);
            }

            // 等待所有Future完成
            CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0])
            );

            // 设置超时时间
            try {
                allFutures.get(10, TimeUnit.MINUTES);
            } catch (TimeoutException e) {
                log.error("并行处理超时");
                futures.forEach(f -> f.cancel(true));
            }

            // 收集结果
            List<String> results = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                try {
                    results.add(futures.get(i).get());
                } catch (Exception e) {
                    log.error("获取第 {} 个批次结果时发生错误", i + 1, e);
                    results.add(texts.get(i)); // 发生错误时使用原文
                }
            }

            log.info("所有批次处理完成，共 {} 个批次", texts.size());
            return results;
        } catch (Exception e) {
            log.error("并行处理过程中发生错误", e);
            throw new RuntimeException("并行处理请求时发生错误: " + e.getMessage());
        }
    }
}