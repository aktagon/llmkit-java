package com.aktagon.llmkit;

import java.util.ArrayList;
import java.util.List;

/*





*/
final class PromptOptions {
    Integer maxTokens;
    Double temperature;
    Double topP;
    Integer topK;
    Long seed;
    Double frequencyPenalty;
    Double presencePenalty;
    Integer thinkingBudget;
    String reasoningEffort;
    List<String> stopSequences = new ArrayList<>();
    List<SafetySetting> safetySettings = new ArrayList<>();
    /**/
    String proto = "";
    /**/
    String schema;
    /*
*/
    boolean caching = false;
    /**/
    Integer cacheTtl;
    /**/
    List<MiddlewareFn> middleware = new ArrayList<>();

    PromptOptions() {}

    /**/
    PromptOptions copy() {
        PromptOptions o = new PromptOptions();
        o.maxTokens = maxTokens;
        o.temperature = temperature;
        o.topP = topP;
        o.topK = topK;
        o.seed = seed;
        o.frequencyPenalty = frequencyPenalty;
        o.presencePenalty = presencePenalty;
        o.thinkingBudget = thinkingBudget;
        o.reasoningEffort = reasoningEffort;
        o.stopSequences = new ArrayList<>(stopSequences);
        o.safetySettings = new ArrayList<>(safetySettings);
        o.proto = proto;
        o.schema = schema;
        o.caching = caching;
        o.cacheTtl = cacheTtl;
        o.middleware = new ArrayList<>(middleware);
        return o;
    }
}
