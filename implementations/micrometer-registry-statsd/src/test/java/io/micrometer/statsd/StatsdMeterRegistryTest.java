/**
 * Copyright 2017 Pivotal Software, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micrometer.statsd;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.micrometer.core.Issue;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.binder.logging.LogbackMetrics;
import io.micrometer.core.lang.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.reactivestreams.Processor;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Operators;
import reactor.core.publisher.UnicastProcessor;
import reactor.test.StepVerifier;
import reactor.util.concurrent.Queues;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * @author Jon Schneider
 */
class StatsdMeterRegistryTest {
    private MockClock clock = new MockClock();

    @BeforeAll
    static void before() {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).setLevel(Level.INFO);
    }

    @ParameterizedTest
    @EnumSource(StatsdFlavor.class)
    void counterLineProtocol(StatsdFlavor flavor) {
        String line = null;
        switch (flavor) {
            case ETSY:
                line = "myCounter.myTag.val.statistic.count:2|c";
                break;
            case DATADOG:
                line = "my.counter:2|c|#statistic:count,my.tag:val";
                break;
            case TELEGRAF:
                line = "my_counter,statistic=count,my_tag=val:2|c";
                break;
            case SYSDIG:
                line = "my.counter#statistic=count,my.tag=val:2|c";
                break;
            default:
                fail("Unexpected flavor");
        }

        final Processor<String, String> lines = lineProcessor();
        MeterRegistry registry = StatsdMeterRegistry.builder(configWithFlavor(flavor))
                .clock(clock)
                .lineSink(toSink(lines))
                .build();

        StepVerifier.create(lines)
                .then(() -> registry.counter("my.counter", "my.tag", "val").increment(2.1))
                .expectNext(line)
                .verifyComplete();
    }

    @ParameterizedTest
    @EnumSource(StatsdFlavor.class)
    void gaugeLineProtocol(StatsdFlavor flavor) {
        final AtomicInteger n = new AtomicInteger(2);
        final StatsdConfig config = configWithFlavor(flavor);

        String line = null;
        switch (flavor) {
            case ETSY:
                line = "myGauge.myTag.val.statistic.value:2|g";
                break;
            case DATADOG:
                line = "my.gauge:2|g|#statistic:value,my.tag:val";
                break;
            case TELEGRAF:
                line = "my_gauge,statistic=value,my_tag=val:2|g";
                break;
            case SYSDIG:
                line = "my.gauge#statistic=value,my.tag=val:2|g";
                break;
            default:
                fail("Unexpected flavor");
        }

        StepVerifier
                .withVirtualTime(() -> {
                    final Processor<String, String> lines = lineProcessor();
                    MeterRegistry registry = StatsdMeterRegistry.builder(config)
                            .clock(clock)
                            .lineSink(toSink(lines))
                            .build();

                    registry.gauge("my.gauge", Tags.of("my.tag", "val"), n);
                    return lines;
                })
                .then(() -> clock.add(config.step()))
                .thenAwait(config.step())
                .expectNext(line)
                .verifyComplete();
    }

    @ParameterizedTest
    @EnumSource(StatsdFlavor.class)
    void timerLineProtocol(StatsdFlavor flavor) {
        String line = null;
        switch (flavor) {
            case ETSY:
                line = "myTimer.myTag.val:1|ms";
                break;
            case DATADOG:
                line = "my.timer:1|ms|#my.tag:val";
                break;
            case TELEGRAF:
                line = "my_timer,my_tag=val:1|ms";
                break;
            case SYSDIG:
                line = "my.timer#my.tag=val:1|ms";
                break;
            default:
                fail("Unexpected flavor");
        }

        final Processor<String, String> lines = lineProcessor();
        MeterRegistry registry = StatsdMeterRegistry.builder(configWithFlavor(flavor))
                .clock(clock)
                .lineSink(toSink(lines))
                .build();

        StepVerifier.create(lines)
                .then(() -> registry.timer("my.timer", "my.tag", "val").record(1, TimeUnit.MILLISECONDS))
                .expectNext(line)
                .verifyComplete();
    }

    @ParameterizedTest
    @EnumSource(StatsdFlavor.class)
    void summaryLineProtocol(StatsdFlavor flavor) {
        String line = null;
        switch (flavor) {
            case ETSY:
                line = "mySummary.myTag.val:1|h";
                break;
            case DATADOG:
                line = "my.summary:1|h|#my.tag:val";
                break;
            case TELEGRAF:
                line = "my_summary,my_tag=val:1|h";
                break;
            case SYSDIG:
                line = "my.summary#my.tag=val:1|h";
                break;
            default:
                fail("Unexpected flavor");
        }

        final Processor<String, String> lines = lineProcessor();
        MeterRegistry registry = StatsdMeterRegistry.builder(configWithFlavor(flavor))
                .clock(clock)
                .lineSink(toSink(lines))
                .build();

        StepVerifier.create(lines)
                .then(() -> registry.summary("my.summary", "my.tag", "val").record(1))
                .expectNext(line)
                .verifyComplete();
    }

    @ParameterizedTest
    @EnumSource(StatsdFlavor.class)
    void longTaskTimerLineProtocol(StatsdFlavor flavor) {
        final StatsdConfig config = configWithFlavor(flavor);
        long stepMillis = config.step().toMillis();

        String[] expectLines = null;
        switch (flavor) {
            case ETSY:
                expectLines = new String[]{
                        "myLongTask.myTag.val.statistic.activeTasks:1|g",
                        "myLongTask.myTag.val.statistic.duration:" + stepMillis + "|g",
                };
                break;
            case DATADOG:
                expectLines = new String[]{
                        "my.long.task:1|g|#statistic:activeTasks,my.tag:val",
                        "my.long.task:" + stepMillis + "|g|#statistic:duration,my.tag:val",
                };
                break;
            case TELEGRAF:
                expectLines = new String[]{
                        "my_long_task,statistic=activeTasks,my_tag=val:1|g",
                        "my_long_task,statistic=duration,my_tag=val:" + stepMillis + "|g",
                };
                break;
            case SYSDIG:
                expectLines = new String[]{
                    "my.long.task#statistic=activeTasks,my.tag=val:1|g",
                    "my.long.task#statistic=duration,my.tag=val:" + stepMillis + "|g",
                };
                break;
            default:
                fail("Unexpected flavor");
        }

        AtomicReference<LongTaskTimer> ltt = new AtomicReference<>();
        AtomicReference<LongTaskTimer.Sample> sample = new AtomicReference<>();

        StepVerifier
                .withVirtualTime(() -> {
                    final Processor<String, String> lines = lineProcessor();
                    MeterRegistry registry = StatsdMeterRegistry.builder(config)
                            .clock(clock)
                            .lineSink(toSink(lines, 2))
                            .build();

                    ltt.set(registry.more().longTaskTimer("my.long.task", "my.tag", "val"));
                    return lines;
                })
                .then(() -> sample.set(ltt.get().start()))
                .then(() -> clock.add(config.step()))
                .thenAwait(config.step())
                .expectNext(expectLines[0])
                .expectNext(expectLines[1])
                .verifyComplete();
    }

    @Test
    void customNamingConvention() {
        final Processor<String, String> lines = lineProcessor();
        MeterRegistry registry = StatsdMeterRegistry.builder(configWithFlavor(StatsdFlavor.ETSY))
                .nameMapper((id, convention) -> id.getName().toUpperCase())
                .clock(clock)
                .lineSink(toSink(lines))
                .build();

        StepVerifier.create(lines)
                .then(() -> registry.counter("my.counter", "my.tag", "val").increment(2.1))
                .expectNext("MY.COUNTER:2|c")
                .verifyComplete();
    }

    @Issue("#411")
    @Test
    void counterIncrementDoesNotCauseStackOverflow() {
        StatsdMeterRegistry registry = new StatsdMeterRegistry(configWithFlavor(StatsdFlavor.ETSY), clock);
        new LogbackMetrics().bindTo(registry);

        // Cause the publisher to get into a state that would make it perform logging at DEBUG level.
        ((Logger) LoggerFactory.getLogger(Operators.class)).setLevel(Level.DEBUG);
        registry.publisher.onComplete();

        registry.counter("my.counter").increment();
    }

    @ParameterizedTest
    @EnumSource(StatsdFlavor.class)
    @Issue("#370")
    void slasOnlyNoPercentileHistogram(StatsdFlavor flavor) {
        StatsdConfig config = configWithFlavor(flavor);
        MeterRegistry registry = new StatsdMeterRegistry(config, clock);
        DistributionSummary summary = DistributionSummary.builder("my.summary").sla(1, 2).register(registry);
        summary.record(1);

        Timer timer = Timer.builder("my.timer").sla(Duration.ofMillis(1)).register(registry);
        timer.record(1, TimeUnit.MILLISECONDS);

        Gauge summaryHist1 = registry.get("my.summary.histogram").tags("le", "1").gauge();
        Gauge summaryHist2 = registry.get("my.summary.histogram").tags("le", "2").gauge();
        Gauge timerHist = registry.get("my.timer.histogram").tags("le", "1").gauge();

        assertThat(summaryHist1.value()).isEqualTo(1);
        assertThat(summaryHist2.value()).isEqualTo(1);
        assertThat(timerHist.value()).isEqualTo(1);

        clock.add(config.step());

        assertThat(summaryHist1.value()).isEqualTo(0);
        assertThat(summaryHist2.value()).isEqualTo(0);
        assertThat(timerHist.value()).isEqualTo(0);
    }

    @Test
    void interactWithStoppedRegistry() {
        StatsdMeterRegistry registry = new StatsdMeterRegistry(configWithFlavor(StatsdFlavor.ETSY), clock);
        registry.stop();
        registry.counter("my.counter").increment();
    }

    private static StatsdConfig configWithFlavor(StatsdFlavor flavor) {
        return new StatsdConfig() {
            @Override
            @Nullable
            public String get(String key) {
                return null;
            }

            @Override
            public StatsdFlavor flavor() {
                return flavor;
            }
        };
    }

    private UnicastProcessor<String> lineProcessor() {
        return UnicastProcessor.create(Queues.<String>unboundedMultiproducer().get());
    }

    private Consumer<String> toSink(Processor<String, String> lines) {
        return toSink(lines, 1);
    }

    private Consumer<String> toSink(Processor<String, String> lines, int numLines) {
        AtomicInteger latch = new AtomicInteger(numLines);
        return l -> {
            System.out.println(l);
            lines.onNext(l);
            if (latch.decrementAndGet() == 0) {
                lines.onComplete();
            }
        };
    }
}
