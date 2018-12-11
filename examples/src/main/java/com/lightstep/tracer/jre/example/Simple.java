package com.lightstep.tracer.jre.example;

import com.lightstep.tracer.jre.JRETracer;
import com.lightstep.tracer.shared.Options;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.net.MalformedURLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Simple {

    private static final Logger LOGGER = LoggerFactory.getLogger(Simple.class);

    private static final String MESSAGE_KEY = "message";
    private static final String PAYLOAD_KEY = "payload";

    public static final String LIGHTSTEP_ENABLED_ENVVAR  = "LIGHTSTEP_ENABLED";
    public static final String LIGHTSTEP_ACCESSTOKEN_ENVVAR = "LIGHTSTEP_ACCESS_TOKEN";
    public static final String LIGHTSTEP_VERBOSE_ENVVAR = "LIGHTSTEP_VERBOSE";

    public static final String LIGHTSTEP_COLLECTORHOST_DEFAULT = "collector.lightstep.com";
    public static final int LIGHTSTEP_COLLECTORPORT_DEFAULT = 443;
    public static final String LIGHTSTEP_COLLECTORPROTOCOL_DEFAUlT = "http";

    public static final String LIGHTSTEP_ENABLED_DEFAUlT = "false";
    public static int LIGHTSTEP_VERBOSITY_DEFAULT = Options.VERBOSITY_INFO;

    public static final String serviceName = Simple.class.getName();

    public static void main(String[] args) throws InterruptedException, MalformedURLException {
        LOGGER.info("Starting Simple example...service name {}.", serviceName);

        Map<String, String> config = getSettings();
        String enabled = config.getOrDefault(LIGHTSTEP_ENABLED_ENVVAR, LIGHTSTEP_ENABLED_DEFAUlT);
        String verbose = config.getOrDefault(LIGHTSTEP_VERBOSE_ENVVAR, String.valueOf(LIGHTSTEP_VERBOSITY_DEFAULT));
        String accesstoken = config.getOrDefault(LIGHTSTEP_ACCESSTOKEN_ENVVAR, "");
        int verbosity = Integer.parseInt(verbose);

        // TODO unable to use http to our http collector.
        Options options = new Options.OptionsBuilder()
                .withAccessToken(accesstoken)
                .withComponentName(serviceName)
                .withVerbosity(verbosity)
                .build();

        final Tracer tracer = new JRETracer(options);

        // Create a simple span and delay for a while to ensure the reporting
        // loop works as expected
        final Span mySpan = tracer.buildSpan("my_span").startManual();

        // Play with different sorts of payloads for fun.
        mySpan.log("just a message");
        mySpan.log("just a message");
        mySpan.log("no payload");
        mySpan.log(getLogPayloadMap("string payload", "str"));
        Map<String, Object> m = new HashMap<>();
        m.put(MESSAGE_KEY, "map payload");
        m.put("foo", "bar");
        m.put("baz", 42);
        mySpan.log(m);
        mySpan.log(m);
        m.put("event", "now an event field exists");
        mySpan.log(m);
        mySpan.finish();
        Thread.sleep(4000);

        // Create an outer span to capture all activity
        final Span parentSpan = tracer
                .buildSpan("outer_span")
                .withTag("favorite_unicode", "🌠🍕🍕🍕🍕")
                .withTag("boring_characters", " \n\b\t()%20/\\#@$!-=")
                .withTag("Valid ASCII", "abcdefg")
                .withTag("Manual unicode", "\u0027\u0018\u00f6\u0003\u0012\u008e\u00fa\u00ec\u0011\r")
                .withTag("🍕", "pepperoni")
                .startManual();
        parentSpan.log("Starting outer span");


        // Create a simple child span
        Span childSpan = tracer.buildSpan("hello_world")
                .asChildOf(parentSpan.context())
                .withTag("hello", "world")
                .startManual();
        Thread.sleep(100);
        // Note that the returned SpanContext is still valid post-finish().
        SpanContext childCtx = childSpan.context();
        childSpan.finish();

        // Throw inject and extract into the mix, even though we aren't making
        // an RPC.
        Span grandchild = createChildViaInjectExtract(tracer, childCtx);
        grandchild.log("grandchild created");
        grandchild.finish();

        // Spawn some concurrent threads - which in turn will spawn their
        // own worker threads
        ExecutorService executor = Executors.newFixedThreadPool(4);
        for (int i = 0; i < 4; i++) {
            executor.execute(new Thread() {
                public void run() {
                    try {
                        spawnWorkers(tracer, parentSpan);
                    } catch (InterruptedException e) {
                        parentSpan.setTag("error", "true");
                        parentSpan.log(getLogPayloadMap("InterruptedException", e));
                    }
                }
            });
        }
        executor.shutdown();
        executor.awaitTermination(20, TimeUnit.SECONDS);

        parentSpan.finish();

        ((com.lightstep.tracer.jre.JRETracer) tracer).flush(20000);

        LOGGER.info("done!");
    }

    public static Map<String, String> getSettings() {
        String enabled = getProperty(LIGHTSTEP_ENABLED_ENVVAR, LIGHTSTEP_ENABLED_DEFAUlT);
        String accessToken = getProperty(LIGHTSTEP_ACCESSTOKEN_ENVVAR, "");
        String verbose  = getProperty(LIGHTSTEP_VERBOSE_ENVVAR, String.valueOf(LIGHTSTEP_VERBOSITY_DEFAULT));
        HashMap<String, String> config = new HashMap<>();
        config.put(LIGHTSTEP_ENABLED_ENVVAR, enabled);
        config.put(LIGHTSTEP_ACCESSTOKEN_ENVVAR, accessToken);
        config.put(LIGHTSTEP_VERBOSE_ENVVAR, verbose);
        return config;
    }

    private static String getEnv(String key, String defaultValue) {
        String result = System.getenv(key);
        return result != null ? result : defaultValue;
    }

    private static String getProperty(String key, String defaultValue) {
        String result = System.getProperty(key);
        return result != null ? result : defaultValue;
    }

    // An ultra-hacky demonstration of inject() and extract() in-process.
    private static Span createChildViaInjectExtract(Tracer tracer, SpanContext parentCtx) {
        final Map<String, String> textMap = new HashMap<>();
        final TextMap demoCarrier = new TextMap() {
            public void put(String key, String value) {
                textMap.put(key, value);
            }

            public Iterator<Map.Entry<String, String>> iterator() {
                return textMap.entrySet().iterator();
            }
        };

        tracer.inject(parentCtx, Format.Builtin.TEXT_MAP, demoCarrier);
        System.out.println("Carrier contents:");
        for (Map.Entry<String, String> entry : textMap.entrySet()) {
            System.out.println(
                    "    key='" + entry.getKey() +
                            "', value='" + entry.getValue() + "'");
        }
        SpanContext extracted = tracer.extract(Format.Builtin.TEXT_MAP, demoCarrier);
        return tracer.buildSpan("grandchild").asChildOf(extracted).startManual();
    }

    private static void spawnWorkers(final Tracer tracer, Span outerSpan) throws InterruptedException {
        final Span parentSpan = tracer.buildSpan("spawn_workers")
                .asChildOf(outerSpan.context())
                .startManual();

        System.out.println("Launching worker threads.");

        Thread workers[] = new Thread[4];
        workers[0] = new Thread() {
            public void run() {
                Span childSpan = tracer.buildSpan("worker0")
                        .asChildOf(parentSpan.context())
                        .startManual();
                for (int i = 0; i < 20; i++) {
                    Span innerSpan = tracer.buildSpan("worker0/microspan")
                            .asChildOf(childSpan.context())
                            .startManual();
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        childSpan.setTag("error", "true");
                        childSpan.log(getLogPayloadMap("InterruptedException!", e));
                    }
                    innerSpan.finish();
                }
                childSpan.finish();
            }
        };
        workers[1] = new Thread() {
            public void run() {
                Span childSpan = tracer.buildSpan("worker1")
                        .asChildOf(parentSpan.context())
                        .startManual();
                for (int i = 0; i < 20; i++) {
                    childSpan.log(getLogPayloadMap("Beginning inner loop", i));
                    for (int j = 0; j < 10; j++) {
                        try {
                            Thread.sleep(1);
                        } catch (InterruptedException e) {
                            childSpan.setTag("error", "true");
                            childSpan.log(getLogPayloadMap("InterruptedException!", e));
                        }
                    }
                }
                childSpan.finish();
            }
        };
        workers[2] = new Thread() {
            public void run() {
                Span childSpan = tracer.buildSpan("worker2")
                        .asChildOf(parentSpan.context())
                        .startManual();
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    childSpan.setTag("error", "true");
                    childSpan.log(getLogPayloadMap("InterruptedException!", e));
                }
                childSpan.finish();
            }
        };
        workers[3] = new Thread() {
            public void run() {
                Span childSpan = tracer.buildSpan("worker3")
                        .asChildOf(parentSpan.context())
                        .startManual();
                for (int i = 0; i < 20; i++) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        childSpan.setTag("error", "true");
                        childSpan.log(getLogPayloadMap("InterruptedException!", e));
                    }
                }
                childSpan.finish();
            }
        };

        for (int i = 0; i < 4; i++) {
            workers[i].start();
        }
        for (int i = 0; i < 4; i++) {
            workers[i].join();
        }
        System.out.println("Finished worker threads.");
        parentSpan.finish();
    }

    private static Map<String, Object> getLogPayloadMap(String message, Object payload) {
        Map<String,Object> m = new HashMap<>();
        m.put(MESSAGE_KEY, message);
        m.put(PAYLOAD_KEY, payload);
        return m;
    }
}
