package com.example.littlepolice.service;

import com.example.littlepolice.api.DeepSeekApi;
import com.example.littlepolice.model.DeepSeekChatCompletionResult;
import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.DisposableBean;
import retrofit2.Retrofit;
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory;
import retrofit2.converter.jackson.JacksonConverterFactory;

import java.time.Duration;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

@Slf4j
@Service
public class DeepSeekService implements DisposableBean {
    private final DeepSeekApi api;
    private final ExecutorService executorService;

    public DeepSeekService(@Value("${openai.api.key}") String apiKey,
                          @Value("${openai.api.url}") String apiUrl) {
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

        this.api = retrofit.create(DeepSeekApi.class);
        // 创建固定大小的线程池，用于并行处理API请求
        this.executorService = Executors.newFixedThreadPool(3);
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
            ChatCompletionRequest request = ChatCompletionRequest.builder()
                .model("deepseek-chat")
                .messages(Arrays.asList(
                    new ChatMessage("system", "你是一个中文语法检查助手，专门负责修正文本中的'的得地'用法。请只返回修改后的文本，不需要解释。请注意只修改“的得地”的错误用法，无需修正其他错误"),
                    new ChatMessage("user", text)
                ))
                .build();

            log.info("开始发送API请求，文本长度: {}", text.length());

            DeepSeekChatCompletionResult result = api.createChatCompletion(request)
                .blockingGet();

            String correctedText = result.getChoices().get(0).getMessage().getContent();
            long endTime = System.currentTimeMillis();
            log.info("API请求完成，耗时: {}ms", endTime - startTime);

            return correctedText;
        } catch (Exception e) {
            log.error("调用DeepSeek API时发生错误", e);
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
                // 取消所有未完成的任务
                futures.forEach(f -> f.cancel(true));
            }

            // 收集结果
            List<String> results = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                try {
                    // 移除1秒超时限制，因为任务可能已经完成，只是在等待结果
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