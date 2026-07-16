package com.aktagon.llmkit;

import java.util.Map;






interface HttpTransport {

    record Result(int statusCode, byte[] body) {}






    Result postJson(String url, String body, Map<String, String> headers);
}
