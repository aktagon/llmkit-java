package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.ToolCall;
import com.aktagon.llmkit.providers.generated.ToolResult;
import java.util.List;

/*






*/
sealed interface Msg {
    record Text(String role, String text) implements Msg {}

    /**/
    record Media(String role, String text, List<InputImage> images, List<FileRef> files) implements Msg {}

    record Calls(List<ToolCall> calls) implements Msg {}

    record ToolOutput(ToolResult result) implements Msg {}
}
