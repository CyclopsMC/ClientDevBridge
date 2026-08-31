package org.cyclops.clientdevbridge.handler;

import com.google.gson.JsonObject;
import org.cyclops.clientdevbridge.ClientDevBridge;
import org.cyclops.clientdevbridge.Reference;
import org.cyclops.clientdevbridge.mcadapter.ClientState;
import org.cyclops.clientdevbridge.mcadapter.ClientThread;
import org.cyclops.clientdevbridge.protocol.Dispatcher;
import org.cyclops.clientdevbridge.protocol.Json;
import org.cyclops.clientdevbridge.protocol.Params;
import org.cyclops.clientdevbridge.protocol.RpcErrorCodes;
import org.cyclops.clientdevbridge.protocol.RpcException;

import javax.annotation.Nullable;
import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.io.StringWriter;

/**
 * {@code eval}: the deliberate escape hatch, for anything the typed methods do not cover.
 *
 * The engine is reached through {@code javax.script} rather than by compiling against Groovy, so
 * the mod builds and runs whether or not Groovy is on the classpath — and when it is missing, the
 * failure says so instead of surfacing as a {@code NoClassDefFoundError}.
 *
 * This is gated behind {@code -Dclientdevbridge.eval=true} and, like everything else here, is only
 * reachable over the loopback socket. It is a dev tool and nothing else.
 *
 * @author rubensworks
 */
public class EvalHandler {

    /** Engine names to try, in order; the first one present wins. */
    private static final String[] ENGINE_NAMES = { "groovy", "Groovy" };

    @Nullable
    private static ScriptEngine cachedEngine;
    private static boolean engineLookupDone;

    private static java.util.List<ClassLoader> candidateLoaders() {
        java.util.List<ClassLoader> loaders = new java.util.ArrayList<>();
        loaders.add(EvalHandler.class.getClassLoader());
        loaders.add(Thread.currentThread().getContextClassLoader());
        loaders.add(ClientState.vanillaClassLoader());
        loaders.add(ClassLoader.getSystemClassLoader());
        loaders.add(ScriptEngineManager.class.getClassLoader());
        return loaders;
    }

    public static void register(Dispatcher dispatcher) {
        dispatcher.register("eval", raw -> {
            Params params = new Params(raw);
            String language = params.getEnum("language", "groovy", "groovy");
            String code = params.getString("code");
            requireEnabled();

            return ClientThread.submit(() -> {
                StringWriter out = new StringWriter();
                Object value = evaluate(code, out);
                JsonObject result = Json.object();
                result.add("value", describe(value));
                result.addProperty("stdout", out.toString());
                result.addProperty("language", language);
                return result;
            });
        });
    }

    private static void requireEnabled() {
        if (!ClientDevBridge.getConfig().isEvalEnabled()) {
            throw new RpcException(RpcErrorCodes.DISABLED,
                    "eval is disabled. Restart the client with -D" + Reference.PROPERTY_EVAL + "=true "
                            + "(the CLI passes it by default; you started with --no-eval).");
        }
    }

    /**
     * What a repeatedly-evaluated expression last came out as, so a wait that times out can say.
     *
     * A wait on an expression that throws, or that answers something other than a boolean, already
     * fails immediately with a message naming the cause. The one case left blind is the expression
     * that is well-formed and simply never true -- and there the reply used to describe the screen
     * and the world, which have nothing to do with what was asked.
     */
    public static final class Probe {
        private final java.util.concurrent.atomic.AtomicInteger evaluations =
                new java.util.concurrent.atomic.AtomicInteger();
        @Nullable
        private volatile Object lastValue;

        public int evaluations() {
            return evaluations.get();
        }

        @Nullable
        public Object lastValue() {
            return lastValue;
        }
    }

    /**
     * Evaluates on the client thread and coerces the result to a boolean, for {@code wait.for expr}.
     */
    public static boolean evaluateAsBoolean(String expression) {
        return evaluateAsBoolean(expression, null);
    }

    public static boolean evaluateAsBoolean(String expression, @Nullable Probe probe) {
        requireEnabled();
        Object value = evaluate(expression, new StringWriter());
        if (probe != null) {
            probe.evaluations.incrementAndGet();
            probe.lastValue = value;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return false;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0;
        }
        throw RpcException.invalidParams("The expression '" + expression + "' returned "
                + value.getClass().getSimpleName() + " rather than a boolean.");
    }

    private static Object evaluate(String code, StringWriter out) {
        ScriptEngine engine = requireEngine();
        Bindings bindings = engine.createBindings();
        for (java.util.Map.Entry<String, Object> binding : ClientState.scriptBindings().entrySet()) {
            bindings.put(binding.getKey(), binding.getValue());
        }

        ScriptContext context = engine.getContext();
        context.setBindings(bindings, ScriptContext.ENGINE_SCOPE);
        context.setWriter(out);
        context.setErrorWriter(out);

        try {
            return engine.eval(code, context);
        } catch (ScriptException e) {
            // Named, because a Groovy error does not say it is one: "Possible solutions: grep(),
            // tap(Closure)" is baffling to someone who assumed the engine was JavaScript, and
            // nothing else in the reply tells them which language they are writing.
            throw RpcException.invalidParams("The Groovy script failed: " + e.getMessage()
                    + hintFor(code, e));
        }
    }

    /**
     * Turns the two script mistakes that are easy to make here into advice.
     *
     * The code is a script, not a single expression: statements are fine and the last one is the
     * value. That is not obvious from a compile error complaining about a semicolon, and the
     * natural repair -- wrapping the whole thing in {@code !( ... )} to negate a condition -- is
     * exactly what does not work.
     *
     * Game classes are another one. The script engine loads from a different class loader than the
     * transformed game, so naming a Minecraft class outright fails with a class-loader message
     * that says nothing about what to do instead: build them through the bound {@code dev} object.
     */
    private static String hintFor(String code, ScriptException e) {
        String message = String.valueOf(e.getMessage());
        if (message.contains("Unexpected input: ';'") || message.contains("expecting ')'")) {
            return "\nThis is a script, not one expression: statements are allowed and the last one"
                    + " is the value. To negate a condition, put the '!' on that last statement"
                    + " rather than around the whole script.";
        }
        if (message.contains("class loader") || message.contains("ClassLoader")) {
            return "\nGame classes are loaded by the transforming class loader and the script engine"
                    + " is not, so naming one directly fails. Build them through 'dev' instead --"
                    + " 'dev.pos(x, y, z)' rather than 'new net.minecraft.core.BlockPos(x, y, z)'."
                    + " It also has vec, block, blockId, blockEntity, nbt and item.";
        }
        return "";
    }

    private static synchronized ScriptEngine requireEngine() {
        if (!engineLookupDone) {
            engineLookupDone = true;
            // Mod loaders put mods and game libraries in separate class loaders, and
            // ScriptEngineManager finds engines through ServiceLoader — so which loader it is
            // handed decides whether Groovy is visible at all. Try each candidate rather than
            // assuming any particular loader layout, which differs between Fabric and NeoForge
            // and between their dev and production launches.
            for (ClassLoader loader : candidateLoaders()) {
                if (loader == null) {
                    continue;
                }
                ScriptEngineManager manager = new ScriptEngineManager(loader);
                for (String name : ENGINE_NAMES) {
                    ScriptEngine engine = manager.getEngineByName(name);
                    if (engine != null) {
                        cachedEngine = engine;
                        ClientDevBridge.LOGGER.debug("Found the {} script engine via {}",
                                engine.getFactory().getEngineName(), loader);
                        break;
                    }
                }
                if (cachedEngine != null) {
                    break;
                }
            }
        }
        if (cachedEngine == null) {
            throw new RpcException(RpcErrorCodes.DISABLED,
                    "No Groovy script engine is on the classpath, so eval and 'wait --expr' are unavailable. "
                            + "Add org.apache.groovy:groovy-jsr223 to the client's runtime classpath "
                            + "(the published ClientDevBridge artifacts declare it, so this normally means "
                            + "the consumer build excluded transitive dependencies).");
        }
        return cachedEngine;
    }

    /**
     * Converts a script result into JSON. Anything without an obvious mapping is described by its
     * {@code toString} plus its type, which is far more useful than an empty object.
     */
    /**
     * The mistake that makes a well-formed wait expression never come true.
     *
     * {@code dev.prop} answers the property's own value, which for {@code lit} is a Boolean and for
     * {@code power} an Integer -- so comparing it against a quoted string is false forever, and a
     * wait on it can only time out. This is the same class of advice as {@link #hintFor}: the
     * failure is not an error anywhere, so nothing else will ever point at it.
     */
    @Nullable
    public static String hintForExpression(String expression) {
        if (expression.contains("dev.prop") && expression.matches("(?s).*==\\s*['\"].*")) {
            return "dev.prop answers the property's own typed value -- Boolean for 'lit', Integer "
                    + "for 'power', a String only where the property really is textual -- so "
                    + "comparing it against a quoted string is never true. Write == true rather "
                    + "than == 'true'. 'eval' shows which you have: a String comes back quoted, a "
                    + "Boolean does not.";
        }
        return null;
    }

    static com.google.gson.JsonElement describe(@Nullable Object value) {
        if (value == null) {
            return com.google.gson.JsonNull.INSTANCE;
        }
        if (value instanceof Boolean || value instanceof Number || value instanceof String) {
            return Json.toTree(value);
        }
        if (value instanceof Iterable<?> iterable) {
            com.google.gson.JsonArray array = new com.google.gson.JsonArray();
            for (Object entry : iterable) {
                array.add(describe(entry));
            }
            return array;
        }
        JsonObject described = Json.object();
        described.addProperty("type", value.getClass().getName());
        described.addProperty("toString", String.valueOf(value));
        return described;
    }

}
