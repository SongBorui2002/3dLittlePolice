package com.example.littlepolice.api;

import com.example.littlepolice.model.SiliconFlowChatCompletionResult;
import com.example.littlepolice.model.SiliconFlowRequest;
import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import io.reactivex.Single;
import retrofit2.http.Body;
import retrofit2.http.POST;

public interface SiliconFlowApi {
    @POST("v1/chat/completions")
    Single<SiliconFlowChatCompletionResult> createChatCompletion(@Body SiliconFlowRequest request);
}
