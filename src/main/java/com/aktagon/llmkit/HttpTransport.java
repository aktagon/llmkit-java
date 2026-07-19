package com.aktagon.llmkit;

import java.util.Map;

/*



*/
interface HttpTransport {
    /**/
    record Result(int statusCode, byte[] body) {}

    /**/
    record StreamResult(int statusCode, java.util.stream.Stream<String> lines) {}

    /*



*/
    Result postJson(String url, String body, Map<String, String> headers);

    /**/
    Result getText(String url, Map<String, String> headers);

    /*







*/
    Result postMultipart(
            String url,
            Map<String, String> fields,
            String fileField,
            String filename,
            String fileContentType,
            byte[] data,
            Map<String, String> headers);

    /*



*/
    Result postBytes(String url, byte[] body, Map<String, String> headers);

    /*


*/
    StreamResult postJsonStreaming(String url, String body, Map<String, String> headers);
}
