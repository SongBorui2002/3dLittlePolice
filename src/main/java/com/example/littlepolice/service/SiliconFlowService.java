package com.example.littlepolice.service;

import com.example.littlepolice.api.SiliconFlowApi;
import com.example.littlepolice.model.ModelParameters;
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
    private SiliconFlowApi api;
    private final ExecutorService executorService;
    private final String apiUrl;
    private OkHttpClient client;
    private Retrofit retrofit;

    public SiliconFlowService(@Value("${siliconflow.api.url}") String apiUrl) {
        this.apiUrl = apiUrl;
        this.executorService = Executors.newFixedThreadPool(200);
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

    private synchronized void initializeApiClient(String apiKey) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException("API key cannot be null or empty");
        }

        try {
            HttpLoggingInterceptor logging = new HttpLoggingInterceptor(log::info);
            logging.setLevel(HttpLoggingInterceptor.Level.BASIC);

            OkHttpClient client = new OkHttpClient.Builder()
                    .addInterceptor(logging)
                    .addInterceptor(chain -> {
                        okhttp3.Request originalRequest = chain.request();
                        okhttp3.Request newRequest = originalRequest.newBuilder()
                                .header("Authorization", "Bearer " + apiKey)
                                .header("Content-Type", "application/json")
                                .build();
                        return chain.proceed(newRequest);
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

            log.info("Successfully initialized API client with new key");
        } catch (Exception e) {
            log.error("Failed to initialize API client: {}", e.getMessage());
            throw new RuntimeException("Failed to initialize API client", e);
        }
    }

    public String correctText(String text, ModelParameters parameters) {
        initializeApiClient(parameters.getSiliconflowKey());
        try {
            long startTime = System.currentTimeMillis();
            List<SiliconFlowRequest.Message> messages = Arrays.asList(
                    SiliconFlowRequest.Message.builder()
                            .role("system")
                            .content("你是一个中文语法检查助手，专门负责修正文本中的'的得地'用法，无需修改其余语法与标点错误。请只返回修改后的文本，不需要解释。" +
                                    "如：（如果我们信奉得神 还有我们追逐地希望\n" +
                                    "---\n" +
                                    "都已经只是科学性的东西的话 那么我们的爱是否 也将科学化呢\n" +
                                    "---\n" +
                                    "利尔亚当《未来得夏娃》）" +
                                    "只需修改为" +
                                    "（如果我们信奉的神 还有我们追逐的希望\n" +
                                    "---\n" +
                                    "都已经只是科学性的东西的话 那么我们的爱是否 也将科学化呢\n" +
                                    "---\n" +
                                    "利尔亚当《未来的夏娃》）" +
                                    "而无需修改为" +
                                    "（如果我们信奉的神，还有我们追逐的希望啊\n" +
                                    "---\n" +
                                    "都已经只是科学性的东西的话，那么我们的爱是否，也将科学化呢\n" +
                                    "---\n" +
                                    "利尔亚当《未来的夏娃》），请注意：只修改“的得地”的错误用法，无需修改其余语法与标点错误。请只返回修改后的文本，不需要解释。" )
                            .build(),
                    SiliconFlowRequest.Message.builder()
                            .role("user")
                            .content(text)
                            .build()
            );

            SiliconFlowRequest request = SiliconFlowRequest.builder()
//                    .model("Pro/deepseek-ai/DeepSeek-V3-1226")
                    .model(parameters.getModel())
                    .messages(messages)
                    .stream(false)
                    .maxTokens(parameters.getMaxTokens())
                    .temperature(parameters.getTemperature())
                    .topP(parameters.getTopP())
                    .topK(parameters.getTopK())
                    .frequencyPenalty(parameters.getFrequencyPenalty())
                    .n(1)
                    .build();

            log.info("开始发送API请求，文本长度: {}", text.length());
            log.info("请求内容: {}", request);

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

    public List<String> correctTextsParallel(List<String> texts, ModelParameters parameters) {
        try {
            log.info("开始并行处理 {} 个批次", texts.size());
            List<CompletableFuture<String>> futures = new ArrayList<>();

            // 为每个文本创建一个CompletableFuture
            for (int i = 0; i < texts.size(); i++) {
                final int index = i;
                final String text = texts.get(i);

                CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
//                    log.info("开始处理第 {}/{} 个批次", index + 1, texts.size());
                    try {
                        String result = correctText(text, parameters);
//                        log.info("第 {}/{} 个批次处理完成", index + 1, texts.size());
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

            allFutures.get(10, TimeUnit.MINUTES);
//            log.info("所有API请求完成，开始收集结果");
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

//            log.info("所有批次处理完成，共 {} 个批次", texts.size());
            return results;
        } catch (Exception e) {
            log.error("并行处理过程中发生错误", e);
            throw new RuntimeException("并行处理请求时发生错误: " + e.getMessage());
        }
    }
}