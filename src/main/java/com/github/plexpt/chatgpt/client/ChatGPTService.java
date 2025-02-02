package com.github.plexpt.chatgpt.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.github.plexpt.chatgpt.api.conversation.Content;
import com.github.plexpt.chatgpt.api.conversation.ConversationRequest;
import com.github.plexpt.chatgpt.api.conversation.ConversationResponse;
import com.github.plexpt.chatgpt.api.conversation.Message;
import com.github.plexpt.chatgpt.api.conversations.ConversationsResponse;
import com.github.plexpt.chatgpt.api.model.ModelResponse;
import io.reactivex.Observable;
import io.reactivex.schedulers.Schedulers;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import retrofit2.Retrofit;
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory;
import retrofit2.converter.jackson.JacksonConverterFactory;
import retrofit2.converter.scalars.ScalarsConverterFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static java.time.Duration.ofSeconds;

public class ChatGPTService {
    private static final String BASE_URL = "https://chatgpt.duti.tech";


    final ChatGPTApi chatGPTApi;
    final ConversationApi conversationApi;



    /**
     * Creates a new ChatGPTService that wraps ChatGPTApi
     *
     * @param token ChatGPT Access token string "sk-XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"
     */
    public ChatGPTService(final String token)  {
        this(token, BASE_URL, ofSeconds(10));
    }

    public ChatGPTService(final String token, final String baseUrl, final Duration timeout) {

        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.setPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE);

        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(new AuthenticationInterceptor(token))
                .connectionPool(new ConnectionPool(5, 1, TimeUnit.SECONDS))
                .readTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                        .hostnameVerifier((hostname, session) -> true)
                .build();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(JacksonConverterFactory.create(mapper))
                .addConverterFactory(ScalarsConverterFactory.create())
                .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
                .build();

        Retrofit noParseRetrofit = new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)

                .addConverterFactory(ScalarsConverterFactory.create())
                .addCallAdapterFactory(RxJava2CallAdapterFactory.create())
                .build();

        this.chatGPTApi = retrofit.create(ChatGPTApi.class);
        this.conversationApi = retrofit.create(ConversationApi.class);
    }

    public ModelResponse getModels() {
        return chatGPTApi.getModels().blockingGet();
    }

    public ConversationsResponse getConversations(int offset, int limit) {
        return chatGPTApi.getConversations(offset, limit).blockingGet();
    }

    public ConversationRequest parseNewConversation(String inputMessage) {
        ArrayList<String> parts = new ArrayList<>();
        ArrayList<Message> messages = new ArrayList<>();

        parts.add(inputMessage);

        Content content = Content.builder()
                .content_type("text")
                .parts(parts).build();

        Message message = Message.builder()
                .id(java.util.UUID.randomUUID().toString())
                .role("user")
                .content(content)
                .build();

        messages.add(message);

        return ConversationRequest.builder()
                .action("next")
                .messages(messages)
                .conversation_id(null)
                .parent_message_id(java.util.UUID.randomUUID().toString())
                .model("text-davinci-002-render-sha")
                .build();

    }

    public Observable<ConversationResponse> getNewStreamConversation(String inputMessage) {

        return conversationApi.getConversationStream(parseNewConversation(inputMessage))
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .flatMap(responseBody -> event(responseBody.source()));
    }

    public List<ConversationResponse> getNewConversation(String inputMessage) {
        ArrayList<ConversationResponse> conversationResponseList = new ArrayList<>();

        ResponseBody responseBody = conversationApi.getConversation(parseNewConversation(inputMessage)).blockingGet();

        try {
            String body = responseBody.string();
            for (String s :body.split("\n")) {
                if ((s == null) || "".equals(s)) {
                    continue;
                }
                if (s.contains("data: [DONE]")) {
                    continue;
                }

                String part = s.substring(5);

                conversationResponseList.add(new ObjectMapper().readValue(part, ConversationResponse.class));
            }

        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return conversationResponseList;

    }

    /*
    Handle BufferedSource
    https://stackoverflow.com/questions/36603368/android-retrofit-2-rxjava-listen-to-endless-stream
    * */

    public Observable<ConversationResponse> event(BufferedSource source)
    {
        return Observable.create(observableEmitter -> {
            boolean isCompleted = false;
            String data = source.readUtf8Line();
            try {
                while (!source.exhausted()) {
                    data = source.readUtf8Line();

                    if ((data == null) || "".equals(data)) {
                        continue;
                    }

                    if (data.contains("data: [DONE]")) {
                        isCompleted = true;
                        observableEmitter.onComplete();
                        break;
                    }

                    String part = data.substring(5);
                    observableEmitter.onNext(new ObjectMapper().readValue(part, ConversationResponse.class));
                }
            } catch (IOException e) {
                if (e.getMessage().equals("data: [DONE]")) {
                    isCompleted = true;
                    observableEmitter.onComplete();
                } else {
                    throw new UncheckedIOException(e);
                }
            }
            //if response end we get here
            if (!isCompleted) {
                observableEmitter.onComplete();
            }
        });
    }
}
