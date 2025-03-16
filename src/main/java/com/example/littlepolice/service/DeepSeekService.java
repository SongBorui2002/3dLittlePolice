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
import retrofit2.Retrofit;
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory;
import retrofit2.converter.jackson.JacksonConverterFactory;

import java.time.Duration;
import java.util.Arrays;

@Slf4j
@Service
public class DeepSeekService {
    private final DeepSeekApi api;

    public DeepSeekService(@Value("${openai.api.key}") String apiKey,
                          @Value("${openai.api.url}") String apiUrl) {
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
            .connectTimeout(Duration.ofSeconds(30))
            .readTimeout(Duration.ofSeconds(30))
            .writeTimeout(Duration.ofSeconds(30))
            .build();

        Retrofit retrofit = new Retrofit.Builder()
            .baseUrl(apiUrl)
            .client(client)
            .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
            .addConverterFactory(JacksonConverterFactory.create())
            .build();

        this.api = retrofit.create(DeepSeekApi.class);
    }

    public String correctText(String text) {
        try {
            ChatCompletionRequest request = ChatCompletionRequest.builder()
                .model("deepseek-chat")
                .messages(Arrays.asList(
                    new ChatMessage("system", "你是一个中文语法检查助手，专门负责修正文本中的'的得地'用法。请只返回修改后的文本，不需要解释。"),
                    new ChatMessage("user", text)
                ))
                .build();

            log.info("Sending request to DeepSeek API with text: {}", text);

            DeepSeekChatCompletionResult result = api.createChatCompletion(request)
                .blockingGet();

            String correctedText = result.getChoices().get(0).getMessage().getContent();
            log.info("Received response from DeepSeek API: {}", correctedText);

            return correctedText;
        } catch (Exception e) {
            log.error("Error calling DeepSeek API", e);
            throw new RuntimeException("处理请求时发生错误: " + e.getMessage());
        }
    }
} 