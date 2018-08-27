package org.jenkinsci.plugins.ParameterizedRemoteTrigger.utils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jenkinsci.plugins.ParameterizedRemoteTrigger.BasicBuildContext;
import org.jenkinsci.plugins.tokenmacro.MacroEvaluationException;
import org.jenkinsci.plugins.tokenmacro.TokenMacro;

public class TokenMacroUtils
{

    public static String applyTokenMacroReplacements(String input, BasicBuildContext context) throws IOException
    {
        try {
            if (isUseTokenMacro(context)) {
                return TokenMacro.expandAll(context.run, context.workspace, context.listener, input);
            }
        }
        catch (MacroEvaluationException e) {
            throw new IOException(e);
        }
        catch (InterruptedException e) {
            throw new IOException(e);
        }
        return input;
    }

    public static Map<String, Object> applyTokenMacroReplacements(Map<String, Object> inputs, BasicBuildContext context) throws IOException
    {
        HashMap<String, Object> outputs = new HashMap<String, Object>();
        for (String key : inputs.keySet()) {
            Object value = inputs.get(key);
            if (value instanceof String) {
                outputs.put(key, applyTokenMacroReplacements((String) value, context));
            }
            else {
                outputs.put(key, value);
            }
        }
        return outputs;
    }

    public static boolean isUseTokenMacro(BasicBuildContext context)
    {
        return context != null && context.run != null && context.workspace != null && context.listener != null;
    }

}
