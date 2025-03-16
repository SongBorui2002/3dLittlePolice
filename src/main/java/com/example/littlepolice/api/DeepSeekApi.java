package com.example.littlepolice.api;

import com.example.littlepolice.model.DeepSeekChatCompletionResult;
import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import io.reactivex.Single;
import retrofit2.http.Body;
import retrofit2.http.POST;

public interface DeepSeekApi {
    @POST("chat/completions")
    Single<DeepSeekChatCompletionResult> createChatCompletion(@Body ChatCompletionRequest request);
} 