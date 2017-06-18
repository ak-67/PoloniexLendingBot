package com.dremanovich.leadingbot.bot;

import com.dremanovich.leadingbot.Main;
import com.dremanovich.leadingbot.api.IPoloniexApi;
import com.dremanovich.leadingbot.api.serializers.CurrencyValueSerializer;
import com.dremanovich.leadingbot.api.serializers.RateValueSerializer;
import com.dremanovich.leadingbot.bot.calculators.Calculator;
import com.dremanovich.leadingbot.bot.calculators.ICalculator;
import com.dremanovich.leadingbot.bot.listeners.LoggerAverageStatisticListener;
import com.dremanovich.leadingbot.bot.listeners.LoggerStrategyListener;
import com.dremanovich.leadingbot.bot.strategies.IPoloniexBotLendingStrategy;
import com.dremanovich.leadingbot.bot.strategies.SimpleLendingStrategy;
import com.dremanovich.leadingbot.helpers.SettingsHelper;
import com.dremanovich.leadingbot.retrofit.PostParameterCallAdapterFactory;
import com.dremanovich.leadingbot.retrofit.annotations.PostParameter;
import com.dremanovich.leadingbot.retrofit.interceptors.RequestPrinterInterceptor;
import com.dremanovich.leadingbot.retrofit.interceptors.PostParameterInterceptor;
import com.dremanovich.leadingbot.retrofit.interceptors.SignInterceptor;
import com.dremanovich.leadingbot.api.NonceReminder;
import com.dremanovich.leadingbot.types.CurrencyValue;
import com.dremanovich.leadingbot.types.RateValue;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import okhttp3.OkHttpClient;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import retrofit2.*;
import retrofit2.converter.gson.GsonConverterFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;

public class PoloniexBot {

    private static final Logger log = LogManager.getLogger(SimpleLendingStrategy.class);

    private NonceReminder reminder;

    private AggregatorPoloniexBot aggregator;

    private SettingsHelper settings;

    private IPoloniexBotLendingStrategy strategy;


    public PoloniexBot(SettingsHelper settings,  NonceReminder reminder) throws IllegalArgumentException {
        this.reminder = reminder;
        this.settings = settings;


        Map<Integer, PostParameter> annotationRegistration = new HashMap<>();
        List<CallAdapter.Factory> factories = new ArrayList<>();

        factories.add(Retrofit2Platform.defaultCallAdapterFactory(null));

        PostParameterCallAdapterFactory callAdapterFactory =
                new PostParameterCallAdapterFactory(factories, annotationRegistration);

        SignInterceptor signInterceptor = new SignInterceptor(
                settings.getKey(),
                settings.getSecretKey(),
                reminder
        );


        PostParameterInterceptor postParameterInterceptor = new PostParameterInterceptor(annotationRegistration);

        OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder()
                .connectTimeout(settings.getConnectTimeout(), TimeUnit.SECONDS)
                .readTimeout(settings.getConnectTimeout(), TimeUnit.SECONDS)
                .addInterceptor(postParameterInterceptor)
                .addInterceptor(signInterceptor);

        if (settings.isPrintRequest()){
            RequestPrinterInterceptor requestPrinterInterceptor = new RequestPrinterInterceptor();
            clientBuilder.addInterceptor(requestPrinterInterceptor);
        }

        OkHttpClient client = clientBuilder.build();

        //Gson serializers
        Gson gson = new GsonBuilder()
                .registerTypeAdapter(RateValue.class, new RateValueSerializer())
                .registerTypeAdapter(CurrencyValue.class, new CurrencyValueSerializer())
                .create();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(settings.getUrl())
                .addConverterFactory(GsonConverterFactory.create(gson))
                .addCallAdapterFactory(callAdapterFactory)
                .client(client)
                .build();

        IPoloniexApi api = retrofit.create(IPoloniexApi.class);

        aggregator = new AggregatorPoloniexBot(api, settings.getRequestDelay());

        //TODO: Dependency injection
        ICalculator calculator = new Calculator();
        strategy = new SimpleLendingStrategy(api, settings, calculator);
        strategy.addStrategyListener(new LoggerStrategyListener(log, settings, calculator));
        strategy.addStrategyListener(new LoggerAverageStatisticListener(log, settings, calculator));

    }

    public void start() {

        aggregator.setChangeCallback((dto) -> {
            if (strategy != null){
                strategy.start(dto);
            }
        });

        aggregator.aggregate(settings.getCurrencies());

    }

    public void stop() {
        try {
            aggregator.close();
            reminder.close();
        } catch (Exception e) {
           log.error(e.getMessage(), e);
        }

    }

}