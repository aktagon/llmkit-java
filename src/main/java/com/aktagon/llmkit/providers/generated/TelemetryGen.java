//

package com.aktagon.llmkit.providers.generated;

import com.aktagon.llmkit.MiddlewareOp;

/*



*/
public final class TelemetryGen {
    private TelemetryGen() {}

    public static final String SEMCONV_VERSION = "1.29.0";
    public static final String TRACES_PATH = "/v1/traces";
    public static final boolean ENDPOINT_REQUIRED = true;
    public static final boolean CAPTURE_CONTENT_DEFAULT = false;

    //
    public static final String OTEL_ATTR_OP = "gen_ai.operation.name"; // Event.op
    public static final String OTEL_ATTR_PROVIDER = "gen_ai.system"; // Event.provider
    public static final String OTEL_ATTR_MODEL = "gen_ai.request.model"; // Event.model
    public static final String OTEL_ATTR_ERR_TYPE = "error.type"; // Event.errType

    //
    public static final String OTEL_USAGE_INPUT = "gen_ai.usage.input_tokens";
    public static final String OTEL_USAGE_OUTPUT = "gen_ai.usage.output_tokens";

    /*


*/
    public static String operationName(MiddlewareOp op) {
        switch (op) {
            case LLM_REQUEST: return "chat";
            case TOOL_CALL: return "execute_tool";
            default: return null;
        }
    }
}
