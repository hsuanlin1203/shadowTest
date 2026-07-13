# Shadow Test Comparison Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Java 21 + Spring Boot 3 service that, on an API trigger, compares prod vs shadow responses stored in two Elasticsearch indexes (paired by traceId, fetched in 10-minute windows) and writes mismatches and unmatched records to an anomaly index.

**Architecture:** A REST controller triggers an async job (returns a jobId). Each job runs an isolated `ComparisonEngine` that iterates the configured time range in 10-minute windows, fetches prod and shadow records from ES, pairs them by traceId in a per-job in-memory `MatchBuffer` (holding full records so cross-window mismatches can be diffed directly), compares them via a per-product pluggable `ResponseComparator` (default = SHA-256 of body), and bulk-writes anomalies back to ES.

**Tech Stack:** Java 21, Spring Boot 3.3.x, Maven, Elasticsearch 8.x official Java client (`co.elastic.clients`), JUnit 5, AssertJ, Testcontainers (Elasticsearch).

## Global Constraints

- Java 21 (`maven.compiler.release` = 21).
- Spring Boot 3.3.x parent.
- Elasticsearch access via `co.elastic.clients:elasticsearch-java` 8.x only (no `RestHighLevelClient`, no Spring Data ES).
- Base package: `com.example.shadowtest`.
- Comparison task config, ES connection, and anomaly index name all come from `application.yml`.
- Each job's state (`MatchBuffer`, counters, engine instance) is job-local and never shared between jobs.
- The unmatched buffer is pure in-memory and holds the **full** `LogRecord` (including body).
- The trigger API is async: returns `202` + `jobId`; a same task id already running is rejected with `409`.
- Anomalies carry `jobId`, `taskId`, and `product` fields; default anomaly index name `shadow-test-anomalies`.
- No job-state persistence across restart; no raw-log text parsing (fields are structured metadata); no UI/auth beyond the ES connection.

---

### Task 1: Project scaffold (Maven + Spring Boot app + config skeleton)

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/com/example/shadowtest/ShadowTestApplication.java`
- Create: `src/main/resources/application.yml`
- Test: `src/test/java/com/example/shadowtest/ShadowTestApplicationTests.java`

**Interfaces:**
- Consumes: nothing.
- Produces: buildable Spring Boot app; base package `com.example.shadowtest`.

- [ ] **Step 1: Write the failing test (context loads)**

`src/test/java/com/example/shadowtest/ShadowTestApplicationTests.java`:
```java
package com.example.shadowtest;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "elasticsearch.host=localhost",
    "elasticsearch.port=9200"
})
class ShadowTestApplicationTests {
    @Test
    void contextLoads() {
    }
}
```

- [ ] **Step 2: Create `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.5</version>
        <relativePath/>
    </parent>

    <groupId>com.example</groupId>
    <artifactId>shadow-test</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>shadow-test</name>

    <properties>
        <java.version>21</java.version>
        <elasticsearch.version>8.15.0</elasticsearch.version>
        <testcontainers.version>1.20.1</testcontainers.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>co.elastic.clients</groupId>
            <artifactId>elasticsearch-java</artifactId>
            <version>${elasticsearch.version}</version>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>elasticsearch</artifactId>
            <version>${testcontainers.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>${testcontainers.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 3: Create the application class**

`src/main/java/com/example/shadowtest/ShadowTestApplication.java`:
```java
package com.example.shadowtest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
public class ShadowTestApplication {
    public static void main(String[] args) {
        SpringApplication.run(ShadowTestApplication.class, args);
    }
}
```

- [ ] **Step 4: Create `application.yml` skeleton**

`src/main/resources/application.yml`:
```yaml
elasticsearch:
  host: localhost
  port: 9200
  scheme: http
  username:
  password:

anomaly:
  index-name: shadow-test-anomalies

comparison:
  tasks:
    demo-product:
      product: demo
      prod-index: prod-responses
      shadow-index: shadow-responses
      host: api.example.com
      host-field: host
      time-field: "@timestamp"
      trace-id-field: traceId
      body-field: responseBody
      window-minutes: 10
      page-size: 500
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q test -Dtest=ShadowTestApplicationTests`
Expected: PASS (context loads; ES beans are lenient—see Task 8 for the client bean created lazily).

> Note: if `contextLoads` fails because the ES client tries to connect at startup, ensure the `ElasticsearchClient` bean (Task 8) does not eagerly ping ES. The 8.x client does not connect on construction, so this passes.

- [ ] **Step 6: Commit**

```bash
git init
git add pom.xml src/main/java/com/example/shadowtest/ShadowTestApplication.java src/main/resources/application.yml src/test/java/com/example/shadowtest/ShadowTestApplicationTests.java
git commit -m "chore: scaffold Spring Boot 3 + ES 8 project"
```

---

### Task 2: `LogRecord` model

**Files:**
- Create: `src/main/java/com/example/shadowtest/model/LogRecord.java`
- Test: `src/test/java/com/example/shadowtest/model/LogRecordTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `record LogRecord(String traceId, String host, Instant timestamp, String docId, String index, String rawBody, String product)`.

- [ ] **Step 1: Write the failing test**

```java
package com.example.shadowtest.model;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class LogRecordTest {
    @Test
    void holdsAllFields() {
        Instant now = Instant.parse("2026-07-13T00:00:00Z");
        LogRecord r = new LogRecord("t1", "api.example.com", now, "doc1", "prod-responses", "{\"a\":1}", "demo");
        assertThat(r.traceId()).isEqualTo("t1");
        assertThat(r.host()).isEqualTo("api.example.com");
        assertThat(r.timestamp()).isEqualTo(now);
        assertThat(r.docId()).isEqualTo("doc1");
        assertThat(r.index()).isEqualTo("prod-responses");
        assertThat(r.rawBody()).isEqualTo("{\"a\":1}");
        assertThat(r.product()).isEqualTo("demo");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=LogRecordTest`
Expected: FAIL — `LogRecord` does not exist.

- [ ] **Step 3: Create `LogRecord`**

```java
package com.example.shadowtest.model;

import java.time.Instant;

public record LogRecord(
        String traceId,
        String host,
        Instant timestamp,
        String docId,
        String index,
        String rawBody,
        String product
) {}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=LogRecordTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/shadowtest/model/LogRecord.java src/test/java/com/example/shadowtest/model/LogRecordTest.java
git commit -m "feat: add LogRecord model"
```

---

### Task 3: `Signature` + `ResponseComparator` interface + `HashResponseComparator`

**Files:**
- Create: `src/main/java/com/example/shadowtest/comparator/Signature.java`
- Create: `src/main/java/com/example/shadowtest/comparator/ResponseComparator.java`
- Create: `src/main/java/com/example/shadowtest/comparator/HashResponseComparator.java`
- Test: `src/test/java/com/example/shadowtest/comparator/HashResponseComparatorTest.java`

**Interfaces:**
- Consumes: `LogRecord` (Task 2).
- Produces:
  - `record Signature(String value)`.
  - `interface ResponseComparator { String product(); Signature signature(LogRecord r); default boolean matches(Signature a, Signature b); }`.
  - `HashResponseComparator` — `product()` returns `ResponseComparator.DEFAULT_PRODUCT` (`"__default__"`); signature = SHA-256 hex of `rawBody`.

- [ ] **Step 1: Write the failing test**

```java
package com.example.shadowtest.comparator;

import com.example.shadowtest.model.LogRecord;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class HashResponseComparatorTest {

    private final HashResponseComparator comparator = new HashResponseComparator();

    private LogRecord record(String body) {
        return new LogRecord("t1", "h", Instant.EPOCH, "d", "i", body, "demo");
    }

    @Test
    void identicalBodiesProduceEqualSignatures() {
        Signature a = comparator.signature(record("hello"));
        Signature b = comparator.signature(record("hello"));
        assertThat(a).isEqualTo(b);
        assertThat(comparator.matches(a, b)).isTrue();
    }

    @Test
    void differentBodiesProduceDifferentSignatures() {
        Signature a = comparator.signature(record("hello"));
        Signature b = comparator.signature(record("world"));
        assertThat(a).isNotEqualTo(b);
        assertThat(comparator.matches(a, b)).isFalse();
    }

    @Test
    void signatureIsStableSha256Hex() {
        // sha256("hello") known value
        assertThat(comparator.signature(record("hello")).value())
            .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    }

    @Test
    void nullBodyIsHandled() {
        Signature a = comparator.signature(record(null));
        Signature b = comparator.signature(record(null));
        assertThat(comparator.matches(a, b)).isTrue();
    }

    @Test
    void defaultProductMarker() {
        assertThat(comparator.product()).isEqualTo(ResponseComparator.DEFAULT_PRODUCT);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=HashResponseComparatorTest`
Expected: FAIL — types do not exist.

- [ ] **Step 3: Create `Signature`**

```java
package com.example.shadowtest.comparator;

public record Signature(String value) {}
```

- [ ] **Step 4: Create `ResponseComparator`**

```java
package com.example.shadowtest.comparator;

import com.example.shadowtest.model.LogRecord;
import java.util.Objects;

/**
 * Compares prod vs shadow responses for one product.
 * Implement as a Spring bean returning your product name from {@link #product()}.
 */
public interface ResponseComparator {

    /** Marker product used by the built-in default comparator. */
    String DEFAULT_PRODUCT = "__default__";

    /** The product this comparator handles. */
    String product();

    /** Reduce a record to a lightweight signature used for pairing/comparison. */
    Signature signature(LogRecord record);

    /** Compare two signatures. Default: exact equality. */
    default boolean matches(Signature a, Signature b) {
        return Objects.equals(a, b);
    }
}
```

- [ ] **Step 5: Create `HashResponseComparator`**

```java
package com.example.shadowtest.comparator;

import com.example.shadowtest.model.LogRecord;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Default comparator: signature is the SHA-256 hex of the raw response body. */
@Component
public class HashResponseComparator implements ResponseComparator {

    @Override
    public String product() {
        return DEFAULT_PRODUCT;
    }

    @Override
    public Signature signature(LogRecord record) {
        String body = record.rawBody() == null ? "" : record.rawBody();
        return new Signature(sha256Hex(body));
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn -q test -Dtest=HashResponseComparatorTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/example/shadowtest/comparator/ src/test/java/com/example/shadowtest/comparator/HashResponseComparatorTest.java
git commit -m "feat: add Signature, ResponseComparator, default hash comparator"
```

---

### Task 4: `ComparatorRegistry`

**Files:**
- Create: `src/main/java/com/example/shadowtest/comparator/ComparatorRegistry.java`
- Test: `src/test/java/com/example/shadowtest/comparator/ComparatorRegistryTest.java`

**Interfaces:**
- Consumes: `ResponseComparator`, `HashResponseComparator`.
- Produces: `ComparatorRegistry(List<ResponseComparator> all, HashResponseComparator defaultComparator)`; `ResponseComparator forProduct(String product)` returns the product-specific comparator or the default.

- [ ] **Step 1: Write the failing test**

```java
package com.example.shadowtest.comparator;

import com.example.shadowtest.model.LogRecord;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ComparatorRegistryTest {

    static class LenientComparator implements ResponseComparator {
        @Override public String product() { return "lenient"; }
        @Override public Signature signature(LogRecord r) { return new Signature("const"); }
    }

    @Test
    void returnsProductSpecificComparator() {
        HashResponseComparator def = new HashResponseComparator();
        LenientComparator lenient = new LenientComparator();
        ComparatorRegistry registry = new ComparatorRegistry(List.of(def, lenient), def);

        assertThat(registry.forProduct("lenient")).isSameAs(lenient);
    }

    @Test
    void fallsBackToDefaultForUnknownProduct() {
        HashResponseComparator def = new HashResponseComparator();
        ComparatorRegistry registry = new ComparatorRegistry(List.of(def), def);

        assertThat(registry.forProduct("unknown")).isSameAs(def);
    }

    @Test
    void defaultMarkerIsNotRegisteredAsProduct() {
        HashResponseComparator def = new HashResponseComparator();
        ComparatorRegistry registry = new ComparatorRegistry(List.of(def), def);
        // "__default__" should resolve to the default, not be treated as a real product key
        assertThat(registry.forProduct(ResponseComparator.DEFAULT_PRODUCT)).isSameAs(def);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=ComparatorRegistryTest`
Expected: FAIL — `ComparatorRegistry` does not exist.

- [ ] **Step 3: Create `ComparatorRegistry`**

```java
package com.example.shadowtest.comparator;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ComparatorRegistry {

    private final Map<String, ResponseComparator> byProduct = new HashMap<>();
    private final ResponseComparator defaultComparator;

    public ComparatorRegistry(List<ResponseComparator> all, HashResponseComparator defaultComparator) {
        this.defaultComparator = defaultComparator;
        for (ResponseComparator c : all) {
            if (c == defaultComparator) {
                continue;
            }
            if (ResponseComparator.DEFAULT_PRODUCT.equals(c.product())) {
                continue;
            }
            byProduct.put(c.product(), c);
        }
    }

    public ResponseComparator forProduct(String product) {
        return byProduct.getOrDefault(product, defaultComparator);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=ComparatorRegistryTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/shadowtest/comparator/ComparatorRegistry.java src/test/java/com/example/shadowtest/comparator/ComparatorRegistryTest.java
git commit -m "feat: add ComparatorRegistry with product-based lookup"
```

---

### Task 5: `TimeWindowIterator`

**Files:**
- Create: `src/main/java/com/example/shadowtest/engine/TimeWindow.java`
- Create: `src/main/java/com/example/shadowtest/engine/TimeWindowIterator.java`
- Test: `src/test/java/com/example/shadowtest/engine/TimeWindowIteratorTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `record TimeWindow(Instant start, Instant end)`.
  - `TimeWindowIterator implements Iterator<TimeWindow>` — constructed with `(Instant start, Instant end, Duration window)`; splits `[start, end)` into consecutive windows; last window clamped to `end`; also `int totalWindows()`.

- [ ] **Step 1: Write the failing test**

```java
package com.example.shadowtest.engine;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class TimeWindowIteratorTest {

    private static final Duration TEN_MIN = Duration.ofMinutes(10);

    @Test
    void splitsRangeIntoTenMinuteWindows() {
        Instant start = Instant.parse("2026-07-13T00:00:00Z");
        Instant end = Instant.parse("2026-07-13T00:30:00Z");
        TimeWindowIterator it = new TimeWindowIterator(start, end, TEN_MIN);

        List<TimeWindow> windows = new ArrayList<>();
        it.forEachRemaining(windows::add);

        assertThat(windows).containsExactly(
            new TimeWindow(Instant.parse("2026-07-13T00:00:00Z"), Instant.parse("2026-07-13T00:10:00Z")),
            new TimeWindow(Instant.parse("2026-07-13T00:10:00Z"), Instant.parse("2026-07-13T00:20:00Z")),
            new TimeWindow(Instant.parse("2026-07-13T00:20:00Z"), Instant.parse("2026-07-13T00:30:00Z"))
        );
    }

    @Test
    void lastWindowIsClampedToEnd() {
        Instant start = Instant.parse("2026-07-13T00:00:00Z");
        Instant end = Instant.parse("2026-07-13T00:25:00Z");
        TimeWindowIterator it = new TimeWindowIterator(start, end, TEN_MIN);

        List<TimeWindow> windows = new ArrayList<>();
        it.forEachRemaining(windows::add);

        assertThat(windows).hasSize(3);
        assertThat(windows.get(2)).isEqualTo(
            new TimeWindow(Instant.parse("2026-07-13T00:20:00Z"), Instant.parse("2026-07-13T00:25:00Z")));
    }

    @Test
    void totalWindowsCountedUpFront() {
        Instant start = Instant.parse("2026-07-13T00:00:00Z");
        Instant end = Instant.parse("2026-07-13T00:25:00Z");
        assertThat(new TimeWindowIterator(start, end, TEN_MIN).totalWindows()).isEqualTo(3);
    }

    @Test
    void singleShortRangeIsOneWindow() {
        Instant start = Instant.parse("2026-07-13T00:00:00Z");
        Instant end = Instant.parse("2026-07-13T00:03:00Z");
        TimeWindowIterator it = new TimeWindowIterator(start, end, TEN_MIN);
        List<TimeWindow> windows = new ArrayList<>();
        it.forEachRemaining(windows::add);
        assertThat(windows).containsExactly(new TimeWindow(start, end));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=TimeWindowIteratorTest`
Expected: FAIL — types do not exist.

- [ ] **Step 3: Create `TimeWindow`**

```java
package com.example.shadowtest.engine;

import java.time.Instant;

public record TimeWindow(Instant start, Instant end) {}
```

- [ ] **Step 4: Create `TimeWindowIterator`**

```java
package com.example.shadowtest.engine;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.NoSuchElementException;

public class TimeWindowIterator implements Iterator<TimeWindow> {

    private final Instant end;
    private final Duration window;
    private Instant cursor;

    public TimeWindowIterator(Instant start, Instant end, Duration window) {
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end must be after start");
        }
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive");
        }
        this.end = end;
        this.window = window;
        this.cursor = start;
    }

    @Override
    public boolean hasNext() {
        return cursor.isBefore(end);
    }

    @Override
    public TimeWindow next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        Instant windowEnd = cursor.plus(window);
        if (windowEnd.isAfter(end)) {
            windowEnd = end;
        }
        TimeWindow tw = new TimeWindow(cursor, windowEnd);
        cursor = windowEnd;
        return tw;
    }

    public int totalWindows() {
        long totalSeconds = Duration.between(cursor, end).getSeconds();
        long windowSeconds = window.getSeconds();
        return (int) ((totalSeconds + windowSeconds - 1) / windowSeconds);
    }
}
```

> Note: `totalWindows()` is intended to be called before iteration starts (cursor at `start`).

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q test -Dtest=TimeWindowIteratorTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/shadowtest/engine/TimeWindow.java src/main/java/com/example/shadowtest/engine/TimeWindowIterator.java src/test/java/com/example/shadowtest/engine/TimeWindowIteratorTest.java
git commit -m "feat: add TimeWindowIterator for 10-minute windowing"
```

---

### Task 6: `MatchBuffer` (core cross-window pairing)

**Files:**
- Create: `src/main/java/com/example/shadowtest/engine/MatchBuffer.java`
- Test: `src/test/java/com/example/shadowtest/engine/MatchBufferTest.java`

**Interfaces:**
- Consumes: `LogRecord`, `Signature`.
- Produces:
  - `enum MatchBuffer.Side { PROD, SHADOW }`.
  - `record MatchBuffer.Entry(LogRecord record, Signature signature)`.
  - `record MatchBuffer.Pair(Entry prod, Entry shadow)`.
  - `Optional<Pair> offer(Side side, LogRecord record, Signature signature)` — if the opposite side already buffered this traceId, removes it and returns the pair; otherwise buffers this entry and returns empty.
  - `Collection<Entry> remainingProd()`, `Collection<Entry> remainingShadow()`.

- [ ] **Step 1: Write the failing test**

```java
package com.example.shadowtest.engine;

import com.example.shadowtest.comparator.Signature;
import com.example.shadowtest.engine.MatchBuffer.Pair;
import com.example.shadowtest.engine.MatchBuffer.Side;
import com.example.shadowtest.model.LogRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MatchBufferTest {

    private LogRecord rec(String traceId, String body) {
        return new LogRecord(traceId, "h", Instant.EPOCH, "doc-" + traceId, "i", body, "demo");
    }

    private Signature sig(String v) {
        return new Signature(v);
    }

    @Test
    void firstSideBuffersAndReturnsEmpty() {
        MatchBuffer buffer = new MatchBuffer();
        Optional<Pair> result = buffer.offer(Side.PROD, rec("t1", "a"), sig("h1"));
        assertThat(result).isEmpty();
        assertThat(buffer.remainingProd()).hasSize(1);
        assertThat(buffer.remainingShadow()).isEmpty();
    }

    @Test
    void oppositeSideSameTraceIdPairsAndClears() {
        MatchBuffer buffer = new MatchBuffer();
        buffer.offer(Side.PROD, rec("t1", "a"), sig("h1"));
        Optional<Pair> result = buffer.offer(Side.SHADOW, rec("t1", "a"), sig("h1"));

        assertThat(result).isPresent();
        assertThat(result.get().prod().record().traceId()).isEqualTo("t1");
        assertThat(result.get().shadow().record().traceId()).isEqualTo("t1");
        assertThat(result.get().prod().signature()).isEqualTo(sig("h1"));
        assertThat(buffer.remainingProd()).isEmpty();
        assertThat(buffer.remainingShadow()).isEmpty();
    }

    @Test
    void crossWindowPairingKeepsFullRecords() {
        // prod arrives in an early "window", shadow much later — both bodies retained
        MatchBuffer buffer = new MatchBuffer();
        buffer.offer(Side.PROD, rec("t1", "prod-body"), sig("h1"));
        // ... many other offers for other traceIds could happen here ...
        buffer.offer(Side.PROD, rec("t2", "x"), sig("hx"));
        Optional<Pair> result = buffer.offer(Side.SHADOW, rec("t1", "shadow-body"), sig("h2"));

        assertThat(result).isPresent();
        assertThat(result.get().prod().record().rawBody()).isEqualTo("prod-body");
        assertThat(result.get().shadow().record().rawBody()).isEqualTo("shadow-body");
        assertThat(buffer.remainingProd()).extracting(e -> e.record().traceId()).containsExactly("t2");
    }

    @Test
    void unmatchedRemainOnBothSides() {
        MatchBuffer buffer = new MatchBuffer();
        buffer.offer(Side.PROD, rec("only-prod", "a"), sig("h1"));
        buffer.offer(Side.SHADOW, rec("only-shadow", "b"), sig("h2"));

        assertThat(buffer.remainingProd()).extracting(e -> e.record().traceId()).containsExactly("only-prod");
        assertThat(buffer.remainingShadow()).extracting(e -> e.record().traceId()).containsExactly("only-shadow");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=MatchBufferTest`
Expected: FAIL — `MatchBuffer` does not exist.

- [ ] **Step 3: Create `MatchBuffer`**

```java
package com.example.shadowtest.engine;

import com.example.shadowtest.comparator.Signature;
import com.example.shadowtest.model.LogRecord;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Pairs prod and shadow records by traceId across windows.
 * Holds full records so a mismatch can be diffed directly, and leftover
 * records can be reported as unmatched with their bodies.
 * Not thread-safe: one instance per job, used from a single job thread.
 */
public class MatchBuffer {

    public enum Side { PROD, SHADOW }

    public record Entry(LogRecord record, Signature signature) {}

    public record Pair(Entry prod, Entry shadow) {}

    private final Map<String, Entry> prod = new HashMap<>();
    private final Map<String, Entry> shadow = new HashMap<>();

    public Optional<Pair> offer(Side side, LogRecord record, Signature signature) {
        Entry entry = new Entry(record, signature);
        String traceId = record.traceId();
        Map<String, Entry> own = side == Side.PROD ? prod : shadow;
        Map<String, Entry> other = side == Side.PROD ? shadow : prod;

        Entry counterpart = other.remove(traceId);
        if (counterpart != null) {
            Pair pair = side == Side.PROD
                    ? new Pair(entry, counterpart)
                    : new Pair(counterpart, entry);
            return Optional.of(pair);
        }
        own.put(traceId, entry);
        return Optional.empty();
    }

    public Collection<Entry> remainingProd() {
        return prod.values();
    }

    public Collection<Entry> remainingShadow() {
        return shadow.values();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q test -Dtest=MatchBufferTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/shadowtest/engine/MatchBuffer.java src/test/java/com/example/shadowtest/engine/MatchBufferTest.java
git commit -m "feat: add MatchBuffer for cross-window traceId pairing"
```

---

### Task 7: `Anomaly` model + `DiffUtil`

**Files:**
- Create: `src/main/java/com/example/shadowtest/anomaly/Anomaly.java`
- Create: `src/main/java/com/example/shadowtest/anomaly/DiffUtil.java`
- Test: `src/test/java/com/example/shadowtest/anomaly/DiffUtilTest.java`

**Interfaces:**
- Consumes: nothing (plain values).
- Produces:
  - `enum Anomaly.Type { MISMATCH, UNMATCHED_PROD, UNMATCHED_SHADOW }`.
  - `record Anomaly(String jobId, String taskId, String product, Anomaly.Type type, String traceId, String host, Instant windowStart, Instant windowEnd, String prodBody, String shadowBody, String diff, Instant detectedAt)`.
  - `DiffUtil.lineDiff(String prod, String shadow)` → unified-ish line diff string; equal inputs → `""`.

- [ ] **Step 1: Write the failing test**

```java
package com.example.shadowtest.anomaly;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class DiffUtilTest {

    @Test
    void equalStringsProduceEmptyDiff() {
        assertThat(DiffUtil.lineDiff("same", "same")).isEmpty();
    }

    @Test
    void differingLinesAreMarked() {
        String diff = DiffUtil.lineDiff("a\nb\nc", "a\nX\nc");
        assertThat(diff).contains("- b");
        assertThat(diff).contains("+ X");
        assertThat(diff).doesNotContain("- a");
    }

    @Test
    void handlesNullsAsEmpty() {
        String diff = DiffUtil.lineDiff(null, "x");
        assertThat(diff).contains("+ x");
    }

    @Test
    void extraLinesOnOneSideAreMarked() {
        String diff = DiffUtil.lineDiff("a", "a\nb");
        assertThat(diff).contains("+ b");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=DiffUtilTest`
Expected: FAIL — `DiffUtil` does not exist.

- [ ] **Step 3: Create `DiffUtil`**

```java
package com.example.shadowtest.anomaly;

/** Minimal line-based diff for reporting mismatched response bodies. */
public final class DiffUtil {

    private DiffUtil() {}

    public static String lineDiff(String prod, String shadow) {
        String p = prod == null ? "" : prod;
        String s = shadow == null ? "" : shadow;
        if (p.equals(s)) {
            return "";
        }
        String[] pl = p.split("\n", -1);
        String[] sl = s.split("\n", -1);
        int max = Math.max(pl.length, sl.length);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < max; i++) {
            String pv = i < pl.length ? pl[i] : null;
            String sv = i < sl.length ? sl[i] : null;
            if (pv != null && pv.equals(sv)) {
                continue;
            }
            if (pv != null) {
                sb.append("- ").append(pv).append('\n');
            }
            if (sv != null) {
                sb.append("+ ").append(sv).append('\n');
            }
        }
        return sb.toString();
    }
}
```

- [ ] **Step 4: Create `Anomaly`**

```java
package com.example.shadowtest.anomaly;

import java.time.Instant;

public record Anomaly(
        String jobId,
        String taskId,
        String product,
        Type type,
        String traceId,
        String host,
        Instant windowStart,
        Instant windowEnd,
        String prodBody,
        String shadowBody,
        String diff,
        Instant detectedAt
) {
    public enum Type { MISMATCH, UNMATCHED_PROD, UNMATCHED_SHADOW }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q test -Dtest=DiffUtilTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/shadowtest/anomaly/Anomaly.java src/main/java/com/example/shadowtest/anomaly/DiffUtil.java src/test/java/com/example/shadowtest/anomaly/DiffUtilTest.java
git commit -m "feat: add Anomaly model and DiffUtil"
```

---

### Task 8: Config properties + ES client bean

**Files:**
- Create: `src/main/java/com/example/shadowtest/config/ComparisonTaskConfig.java`
- Create: `src/main/java/com/example/shadowtest/config/ComparisonTaskProperties.java`
- Create: `src/main/java/com/example/shadowtest/config/AnomalyProperties.java`
- Create: `src/main/java/com/example/shadowtest/config/ElasticsearchClientConfig.java`
- Test: `src/test/java/com/example/shadowtest/config/ComparisonTaskPropertiesTest.java`
- Test resource: `src/test/resources/application-configtest.yml`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `ComparisonTaskConfig` with getters: `product`, `prodIndex`, `shadowIndex`, `host`, `hostField`, `timeField`, `traceIdField`, `bodyField`, `windowMinutes` (default 10), `pageSize` (default 500).
  - `ComparisonTaskProperties` (`@ConfigurationProperties("comparison")`) with `Map<String, ComparisonTaskConfig> getTasks()`.
  - `AnomalyProperties` (`@ConfigurationProperties("anomaly")`) with `String getIndexName()`.
  - `ElasticsearchClientConfig` — `@Bean ElasticsearchClient elasticsearchClient(...)`.

- [ ] **Step 1: Write the failing test**

`src/test/resources/application-configtest.yml`:
```yaml
comparison:
  tasks:
    task-a:
      product: alpha
      prod-index: prod-a
      shadow-index: shadow-a
      host: a.example.com
      host-field: host
      time-field: "@timestamp"
      trace-id-field: traceId
      body-field: responseBody
      window-minutes: 5
      page-size: 100
```

`src/test/java/com/example/shadowtest/config/ComparisonTaskPropertiesTest.java`:
```java
package com.example.shadowtest.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = ComparisonTaskPropertiesTest.TestApp.class)
@ActiveProfiles("configtest")
class ComparisonTaskPropertiesTest {

    @SpringBootApplication
    @EnableConfigurationProperties(ComparisonTaskProperties.class)
    static class TestApp {}

    @Autowired
    ComparisonTaskProperties properties;

    @Test
    void bindsTasksByIdWithDefaults() {
        ComparisonTaskConfig task = properties.getTasks().get("task-a");
        assertThat(task).isNotNull();
        assertThat(task.getProduct()).isEqualTo("alpha");
        assertThat(task.getProdIndex()).isEqualTo("prod-a");
        assertThat(task.getShadowIndex()).isEqualTo("shadow-a");
        assertThat(task.getHost()).isEqualTo("a.example.com");
        assertThat(task.getTraceIdField()).isEqualTo("traceId");
        assertThat(task.getBodyField()).isEqualTo("responseBody");
        assertThat(task.getWindowMinutes()).isEqualTo(5);
        assertThat(task.getPageSize()).isEqualTo(100);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=ComparisonTaskPropertiesTest`
Expected: FAIL — property classes do not exist.

- [ ] **Step 3: Create `ComparisonTaskConfig`**

```java
package com.example.shadowtest.config;

public class ComparisonTaskConfig {
    private String product;
    private String prodIndex;
    private String shadowIndex;
    private String host;
    private String hostField = "host";
    private String timeField = "@timestamp";
    private String traceIdField = "traceId";
    private String bodyField = "responseBody";
    private int windowMinutes = 10;
    private int pageSize = 500;

    public String getProduct() { return product; }
    public void setProduct(String product) { this.product = product; }
    public String getProdIndex() { return prodIndex; }
    public void setProdIndex(String prodIndex) { this.prodIndex = prodIndex; }
    public String getShadowIndex() { return shadowIndex; }
    public void setShadowIndex(String shadowIndex) { this.shadowIndex = shadowIndex; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public String getHostField() { return hostField; }
    public void setHostField(String hostField) { this.hostField = hostField; }
    public String getTimeField() { return timeField; }
    public void setTimeField(String timeField) { this.timeField = timeField; }
    public String getTraceIdField() { return traceIdField; }
    public void setTraceIdField(String traceIdField) { this.traceIdField = traceIdField; }
    public String getBodyField() { return bodyField; }
    public void setBodyField(String bodyField) { this.bodyField = bodyField; }
    public int getWindowMinutes() { return windowMinutes; }
    public void setWindowMinutes(int windowMinutes) { this.windowMinutes = windowMinutes; }
    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }
}
```

- [ ] **Step 4: Create `ComparisonTaskProperties`**

```java
package com.example.shadowtest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "comparison")
public class ComparisonTaskProperties {
    private Map<String, ComparisonTaskConfig> tasks = new LinkedHashMap<>();
    public Map<String, ComparisonTaskConfig> getTasks() { return tasks; }
    public void setTasks(Map<String, ComparisonTaskConfig> tasks) { this.tasks = tasks; }
}
```

- [ ] **Step 5: Create `AnomalyProperties`**

```java
package com.example.shadowtest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "anomaly")
public class AnomalyProperties {
    private String indexName = "shadow-test-anomalies";
    public String getIndexName() { return indexName; }
    public void setIndexName(String indexName) { this.indexName = indexName; }
}
```

- [ ] **Step 6: Create `ElasticsearchClientConfig`**

```java
package com.example.shadowtest.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class ElasticsearchClientConfig {

    @Bean
    public ElasticsearchClient elasticsearchClient(
            @Value("${elasticsearch.host}") String host,
            @Value("${elasticsearch.port}") int port,
            @Value("${elasticsearch.scheme:http}") String scheme,
            @Value("${elasticsearch.username:}") String username,
            @Value("${elasticsearch.password:}") String password) {

        RestClient.builder(new HttpHost(host, port, scheme));
        var builder = RestClient.builder(new HttpHost(host, port, scheme));
        if (StringUtils.hasText(username)) {
            var creds = new BasicCredentialsProvider();
            creds.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(username, password));
            builder.setHttpClientConfigCallback(cb -> cb.setDefaultCredentialsProvider(creds));
        }
        RestClient restClient = builder.build();
        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        return new ElasticsearchClient(transport);
    }
}
```

> The 8.x client does not connect on construction, so the bean is safe to create even when ES is unreachable (e.g. in `contextLoads`).

- [ ] **Step 7: Run tests to verify they pass**

Run: `mvn -q test -Dtest=ComparisonTaskPropertiesTest,ShadowTestApplicationTests`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/example/shadowtest/config/ src/test/java/com/example/shadowtest/config/ComparisonTaskPropertiesTest.java src/test/resources/application-configtest.yml
git commit -m "feat: add config properties and Elasticsearch client bean"
```

---

### Task 9: `EsLogFetcher` (windowed fetch, PIT + search_after) — integration test

**Files:**
- Create: `src/main/java/com/example/shadowtest/es/EsLogFetcher.java`
- Test: `src/test/java/com/example/shadowtest/es/EsLogFetcherIT.java`
- Create: `src/test/java/com/example/shadowtest/support/ElasticsearchTestBase.java`

**Interfaces:**
- Consumes: `ElasticsearchClient`, `ComparisonTaskConfig`, `TimeWindow`, `LogRecord`.
- Produces: `EsLogFetcher(ElasticsearchClient client)` with `List<LogRecord> fetch(String index, ComparisonTaskConfig cfg, TimeWindow window)` — returns all records in `[window.start, window.end)` for the configured host, mapped from ES fields.

- [ ] **Step 1: Write the shared Testcontainers base**

`src/test/java/com/example/shadowtest/support/ElasticsearchTestBase.java`:
```java
package com.example.shadowtest.support;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

public abstract class ElasticsearchTestBase {

    protected static ElasticsearchContainer container;
    protected static ElasticsearchClient client;
    protected static RestClient restClient;

    @BeforeAll
    static void startEs() {
        container = new ElasticsearchContainer(
                DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.15.0"))
                .withEnv("xpack.security.enabled", "false")
                .withEnv("discovery.type", "single-node");
        container.start();
        restClient = RestClient.builder(HttpHost.create(container.getHttpHostAddress())).build();
        client = new ElasticsearchClient(new RestClientTransport(restClient, new JacksonJsonpMapper()));
    }

    @AfterAll
    static void stopEs() throws Exception {
        if (restClient != null) restClient.close();
        if (container != null) container.stop();
    }
}
```

`src/test/java/com/example/shadowtest/es/EsLogFetcherIT.java`:
```java
package com.example.shadowtest.es;

import co.elastic.clients.elasticsearch._types.Refresh;
import com.example.shadowtest.config.ComparisonTaskConfig;
import com.example.shadowtest.engine.TimeWindow;
import com.example.shadowtest.model.LogRecord;
import com.example.shadowtest.support.ElasticsearchTestBase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class EsLogFetcherIT extends ElasticsearchTestBase {

    static ComparisonTaskConfig cfg;

    @BeforeAll
    static void seed() throws Exception {
        cfg = new ComparisonTaskConfig();
        cfg.setHost("api.example.com");
        cfg.setHostField("host");
        cfg.setTimeField("@timestamp");
        cfg.setTraceIdField("traceId");
        cfg.setBodyField("responseBody");
        cfg.setPageSize(2); // force pagination

        index("prod-responses", "t1", "api.example.com", "2026-07-13T00:01:00Z", "body-1");
        index("prod-responses", "t2", "api.example.com", "2026-07-13T00:02:00Z", "body-2");
        index("prod-responses", "t3", "api.example.com", "2026-07-13T00:03:00Z", "body-3");
        index("prod-responses", "other", "OTHER.host", "2026-07-13T00:04:00Z", "body-x");
        index("prod-responses", "late", "api.example.com", "2026-07-13T00:15:00Z", "body-late");
        client.indices().refresh(r -> r.index("prod-responses"));
    }

    static void index(String index, String traceId, String host, String ts, String body) throws Exception {
        client.index(i -> i
            .index(index)
            .refresh(Refresh.True)
            .document(Map.of(
                "traceId", traceId,
                "host", host,
                "@timestamp", ts,
                "responseBody", body)));
    }

    @Test
    void fetchesOnlyMatchingHostWithinWindowAcrossPages() {
        TimeWindow window = new TimeWindow(
            Instant.parse("2026-07-13T00:00:00Z"),
            Instant.parse("2026-07-13T00:10:00Z"));

        List<LogRecord> records = new EsLogFetcher(client).fetch("prod-responses", cfg, window);

        assertThat(records).extracting(LogRecord::traceId)
            .containsExactlyInAnyOrder("t1", "t2", "t3"); // not "other" (host), not "late" (window)
        assertThat(records).allSatisfy(r -> {
            assertThat(r.host()).isEqualTo("api.example.com");
            assertThat(r.rawBody()).startsWith("body-");
            assertThat(r.index()).isEqualTo("prod-responses");
            assertThat(r.docId()).isNotBlank();
        });
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=EsLogFetcherIT`
Expected: FAIL — `EsLogFetcher` does not exist. (Requires Docker running.)

- [ ] **Step 3: Create `EsLogFetcher`**

```java
package com.example.shadowtest.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.OpenPointInTimeResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;
import com.example.shadowtest.config.ComparisonTaskConfig;
import com.example.shadowtest.engine.TimeWindow;
import com.example.shadowtest.model.LogRecord;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Fetches all records for a host within a time window using PIT + search_after. */
public class EsLogFetcher {

    private final ElasticsearchClient client;

    public EsLogFetcher(ElasticsearchClient client) {
        this.client = client;
    }

    public List<LogRecord> fetch(String index, ComparisonTaskConfig cfg, TimeWindow window) {
        try {
            return doFetch(index, cfg, window);
        } catch (IOException e) {
            throw new EsFetchException("failed to fetch from index " + index, e);
        }
    }

    private List<LogRecord> doFetch(String index, ComparisonTaskConfig cfg, TimeWindow window) throws IOException {
        OpenPointInTimeResponse pit = client.openPointInTime(p -> p
            .index(index)
            .keepAlive(k -> k.time("1m")));
        String pitId = pit.id();

        List<LogRecord> results = new ArrayList<>();
        try {
            Query query = Query.of(q -> q.bool(b -> b
                .filter(f -> f.term(t -> t.field(cfg.getHostField()).value(cfg.getHost())))
                .filter(f -> f.range(r -> r
                    .field(cfg.getTimeField())
                    .gte(JsonData.of(window.start().toString()))
                    .lt(JsonData.of(window.end().toString()))))));

            List<FieldValue> searchAfter = null;
            while (true) {
                final List<FieldValue> after = searchAfter;
                SearchRequest req = SearchRequest.of(s -> {
                    s.size(cfg.getPageSize())
                     .query(query)
                     .pit(p -> p.id(pitId).keepAlive(k -> k.time("1m")))
                     .sort(so -> so.field(f -> f.field(cfg.getTimeField()).order(SortOrder.Asc)))
                     .sort(so -> so.field(f -> f.field("_shard_doc").order(SortOrder.Asc)));
                    if (after != null) {
                        s.searchAfter(after);
                    }
                    return s;
                });

                @SuppressWarnings("rawtypes")
                SearchResponse<Map> resp = client.search(req, Map.class);
                List<Hit<Map>> hits = resp.hits().hits();
                if (hits.isEmpty()) {
                    break;
                }
                for (Hit<Map> hit : hits) {
                    results.add(toRecord(index, cfg, hit));
                }
                searchAfter = hits.get(hits.size() - 1).sort();
                if (hits.size() < cfg.getPageSize()) {
                    break;
                }
            }
        } finally {
            final String id = pitId;
            client.closePointInTime(c -> c.id(id));
        }
        return results;
    }

    @SuppressWarnings("rawtypes")
    private LogRecord toRecord(String index, ComparisonTaskConfig cfg, Hit<Map> hit) {
        Map source = hit.source();
        String traceId = asString(source.get(cfg.getTraceIdField()));
        String host = asString(source.get(cfg.getHostField()));
        String body = asString(source.get(cfg.getBodyField()));
        Instant ts = Instant.parse(asString(source.get(cfg.getTimeField())));
        return new LogRecord(traceId, host, ts, hit.id(), index, body, cfg.getProduct());
    }

    private String asString(Object o) {
        return o == null ? null : o.toString();
    }
}
```

- [ ] **Step 4: Create `EsFetchException`**

`src/main/java/com/example/shadowtest/es/EsFetchException.java`:
```java
package com.example.shadowtest.es;

public class EsFetchException extends RuntimeException {
    public EsFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q test -Dtest=EsLogFetcherIT`
Expected: PASS (Docker required).

> If `_shard_doc` sort is rejected by your ES version, it is only valid with a PIT — which this code uses — so it should work. If the date range needs typed dates, `JsonData.of(instant.toString())` with ISO-8601 is accepted by ES date fields.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/shadowtest/es/ src/test/java/com/example/shadowtest/es/EsLogFetcherIT.java src/test/java/com/example/shadowtest/support/ElasticsearchTestBase.java
git commit -m "feat: add EsLogFetcher with PIT + search_after windowed fetch"
```

---

### Task 10: `AnomalyWriter` (bulk index) — integration test

**Files:**
- Create: `src/main/java/com/example/shadowtest/anomaly/AnomalyWriter.java`
- Test: `src/test/java/com/example/shadowtest/anomaly/AnomalyWriterIT.java`

**Interfaces:**
- Consumes: `ElasticsearchClient`, `AnomalyProperties`, `Anomaly`.
- Produces: `AnomalyWriter(ElasticsearchClient client, AnomalyProperties props)` with `void write(List<Anomaly> anomalies)` — bulk-indexes anomalies into the configured index (no-op on empty list).

- [ ] **Step 1: Write the failing test**

```java
package com.example.shadowtest.anomaly;

import co.elastic.clients.elasticsearch._types.Refresh;
import com.example.shadowtest.config.AnomalyProperties;
import com.example.shadowtest.support.ElasticsearchTestBase;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class AnomalyWriterIT extends ElasticsearchTestBase {

    @Test
    void bulkWritesAnomaliesToConfiguredIndex() throws Exception {
        AnomalyProperties props = new AnomalyProperties();
        props.setIndexName("test-anomalies");
        AnomalyWriter writer = new AnomalyWriter(client, props);

        Anomaly a = new Anomaly("job1", "task-a", "alpha", Anomaly.Type.MISMATCH,
            "t1", "api.example.com",
            Instant.parse("2026-07-13T00:00:00Z"), Instant.parse("2026-07-13T00:10:00Z"),
            "prod-body", "shadow-body", "- prod-body\n+ shadow-body\n", Instant.now());

        writer.write(List.of(a));
        client.indices().refresh(r -> r.index("test-anomalies"));

        var count = client.count(c -> c.index("test-anomalies")).count();
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void emptyListIsNoOp() {
        AnomalyProperties props = new AnomalyProperties();
        props.setIndexName("test-anomalies-empty");
        AnomalyWriter writer = new AnomalyWriter(client, props);
        writer.write(List.of()); // must not throw
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q test -Dtest=AnomalyWriterIT`
Expected: FAIL — `AnomalyWriter` does not exist.

- [ ] **Step 3: Create `AnomalyWriter`**

```java
package com.example.shadowtest.anomaly;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import com.example.shadowtest.config.AnomalyProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

@Component
public class AnomalyWriter {

    private final ElasticsearchClient client;
    private final AnomalyProperties props;

    public AnomalyWriter(ElasticsearchClient client, AnomalyProperties props) {
        this.client = client;
        this.props = props;
    }

    public void write(List<Anomaly> anomalies) {
        if (anomalies.isEmpty()) {
            return;
        }
        BulkRequest.Builder br = new BulkRequest.Builder().refresh(Refresh.False);
        for (Anomaly a : anomalies) {
            br.operations(op -> op.index(idx -> idx.index(props.getIndexName()).document(a)));
        }
        try {
            BulkResponse resp = client.bulk(br.build());
            if (resp.errors()) {
                throw new AnomalyWriteException("bulk anomaly write had failures");
            }
        } catch (IOException e) {
            throw new AnomalyWriteException("failed to bulk write anomalies", e);
        }
    }
}
```

- [ ] **Step 4: Create `AnomalyWriteException`**

`src/main/java/com/example/shadowtest/anomaly/AnomalyWriteException.java`:
```java
package com.example.shadowtest.anomaly;

public class AnomalyWriteException extends RuntimeException {
    public AnomalyWriteException(String message) { super(message); }
    public AnomalyWriteException(String message, Throwable cause) { super(message, cause); }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q test -Dtest=AnomalyWriterIT`
Expected: PASS (Docker required).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/shadowtest/anomaly/AnomalyWriter.java src/main/java/com/example/shadowtest/anomaly/AnomalyWriteException.java src/test/java/com/example/shadowtest/anomaly/AnomalyWriterIT.java
git commit -m "feat: add AnomalyWriter bulk indexing"
```

---

### Task 11: `JobState` + `ComparisonJobManager` (async, active-id guard)

**Files:**
- Create: `src/main/java/com/example/shadowtest/job/JobState.java`
- Create: `src/main/java/com/example/shadowtest/job/JobStatus.java`
- Create: `src/main/java/com/example/shadowtest/job/DuplicateTaskException.java`
- Create: `src/main/java/com/example/shadowtest/job/ComparisonJobManager.java`
- Test: `src/test/java/com/example/shadowtest/job/ComparisonJobManagerTest.java`

**Interfaces:**
- Consumes: `ComparisonTaskProperties`, `ComparisonEngine` (Task 12 — defined next; this task depends on its `void run(String jobId, String taskId, ComparisonTaskConfig cfg, JobState state)` signature).
- Produces:
  - `enum JobStatus { PENDING, RUNNING, COMPLETED, FAILED }`.
  - `JobState` (mutable, thread-safe fields): `jobId`, `taskId`, `product`, `volatile JobStatus status`, atomic counters `matched`, `mismatch`, `unmatched`, `currentWindow`, `totalWindows`, `String error`, timestamps. Setters/getters.
  - `ComparisonJobManager`: `String trigger(String taskId)` → returns jobId, throws `DuplicateTaskException` if taskId already active, `IllegalArgumentException` if unknown taskId; `Optional<JobState> getJob(String jobId)`.

> **Design note:** because Task 12 (`ComparisonEngine`) isn't built yet, in this task inject `ComparisonEngine` as a dependency and only assert on the manager's guard/registration behavior using a stubbed engine. The async execution is delegated to the engine via a Spring `@Async` wrapper created in this task.

- [ ] **Step 1: Create `JobStatus`**

```java
package com.example.shadowtest.job;

public enum JobStatus { PENDING, RUNNING, COMPLETED, FAILED }
```

- [ ] **Step 2: Create `JobState`**

```java
package com.example.shadowtest.job;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

public class JobState {
    private final String jobId;
    private final String taskId;
    private final String product;
    private volatile JobStatus status = JobStatus.PENDING;
    private final AtomicInteger matched = new AtomicInteger();
    private final AtomicInteger mismatch = new AtomicInteger();
    private final AtomicInteger unmatched = new AtomicInteger();
    private final AtomicInteger currentWindow = new AtomicInteger();
    private volatile int totalWindows;
    private volatile String error;
    private final Instant startedAt = Instant.now();
    private volatile Instant finishedAt;

    public JobState(String jobId, String taskId, String product) {
        this.jobId = jobId;
        this.taskId = taskId;
        this.product = product;
    }

    public String getJobId() { return jobId; }
    public String getTaskId() { return taskId; }
    public String getProduct() { return product; }
    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus status) { this.status = status; }
    public int getMatched() { return matched.get(); }
    public void incMatched() { matched.incrementAndGet(); }
    public int getMismatch() { return mismatch.get(); }
    public void incMismatch() { mismatch.incrementAndGet(); }
    public int getUnmatched() { return unmatched.get(); }
    public void addUnmatched(int n) { unmatched.addAndGet(n); }
    public int getCurrentWindow() { return currentWindow.get(); }
    public void incCurrentWindow() { currentWindow.incrementAndGet(); }
    public int getTotalWindows() { return totalWindows; }
    public void setTotalWindows(int totalWindows) { this.totalWindows = totalWindows; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
}
```

- [ ] **Step 3: Create `DuplicateTaskException`**

```java
package com.example.shadowtest.job;

public class DuplicateTaskException extends RuntimeException {
    public DuplicateTaskException(String taskId) {
        super("comparison already running for task id: " + taskId);
    }
}
```

- [ ] **Step 4: Write the failing test**

```java
package com.example.shadowtest.job;

import com.example.shadowtest.config.ComparisonTaskConfig;
import com.example.shadowtest.config.ComparisonTaskProperties;
import com.example.shadowtest.engine.ComparisonEngine;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComparisonJobManagerTest {

    private ComparisonTaskProperties propsWithTaskA() {
        ComparisonTaskConfig cfg = new ComparisonTaskConfig();
        cfg.setProduct("alpha");
        ComparisonTaskProperties props = new ComparisonTaskProperties();
        props.setTasks(Map.of("task-a", cfg));
        return props;
    }

    @Test
    void unknownTaskIdRejected() {
        ComparisonEngine engine = Mockito.mock(ComparisonEngine.class);
        ComparisonJobManager manager = new ComparisonJobManager(propsWithTaskA(), engine);
        assertThatThrownBy(() -> manager.trigger("nope"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void triggerRegistersJobAndDelegatesToEngine() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        ComparisonEngine engine = Mockito.mock(ComparisonEngine.class);
        Mockito.doAnswer(inv -> { started.countDown(); return null; })
            .when(engine).run(Mockito.anyString(), Mockito.eq("task-a"), Mockito.any(), Mockito.any());

        ComparisonJobManager manager = new ComparisonJobManager(propsWithTaskA(), engine);
        String jobId = manager.trigger("task-a");

        assertThat(jobId).isNotBlank();
        assertThat(manager.getJob(jobId)).isPresent();
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void duplicateTaskIdRejectedWhileActive() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ComparisonEngine engine = Mockito.mock(ComparisonEngine.class);
        Mockito.doAnswer(inv -> { release.await(); return null; })
            .when(engine).run(Mockito.anyString(), Mockito.eq("task-a"), Mockito.any(), Mockito.any());

        ComparisonJobManager manager = new ComparisonJobManager(propsWithTaskA(), engine);
        manager.trigger("task-a");
        try {
            assertThatThrownBy(() -> manager.trigger("task-a"))
                .isInstanceOf(DuplicateTaskException.class);
        } finally {
            release.countDown();
        }
    }
}
```

Add Mockito (already transitively in `spring-boot-starter-test`).

- [ ] **Step 5: Run test to verify it fails**

Run: `mvn -q test -Dtest=ComparisonJobManagerTest`
Expected: FAIL — `ComparisonJobManager` does not exist.

- [ ] **Step 6: Create `ComparisonJobManager`**

```java
package com.example.shadowtest.job;

import com.example.shadowtest.config.ComparisonTaskConfig;
import com.example.shadowtest.config.ComparisonTaskProperties;
import com.example.shadowtest.engine.ComparisonEngine;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

@Service
public class ComparisonJobManager {

    private final ComparisonTaskProperties properties;
    private final ComparisonEngine engine;
    private final ConcurrentHashMap<String, JobState> jobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> activeTaskIds = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public ComparisonJobManager(ComparisonTaskProperties properties, ComparisonEngine engine) {
        this.properties = properties;
        this.engine = engine;
    }

    public String trigger(String taskId) {
        ComparisonTaskConfig cfg = properties.getTasks().get(taskId);
        if (cfg == null) {
            throw new IllegalArgumentException("unknown task id: " + taskId);
        }
        if (activeTaskIds.putIfAbsent(taskId, Boolean.TRUE) != null) {
            throw new DuplicateTaskException(taskId);
        }

        String jobId = UUID.randomUUID().toString();
        JobState state = new JobState(jobId, taskId, cfg.getProduct());
        jobs.put(jobId, state);

        executor.submit(() -> {
            try {
                state.setStatus(JobStatus.RUNNING);
                engine.run(jobId, taskId, cfg, state);
                state.setStatus(JobStatus.COMPLETED);
            } catch (Exception e) {
                state.setStatus(JobStatus.FAILED);
                state.setError(e.getMessage());
            } finally {
                state.setFinishedAt(Instant.now());
                activeTaskIds.remove(taskId);
            }
        });
        return jobId;
    }

    public Optional<JobState> getJob(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }
}
```

> Uses an internal `ExecutorService` (not `@Async`) so the guard/lifecycle is fully owned here and testable without Spring. `@EnableAsync` remains for any future use.

- [ ] **Step 7: Run test to verify it passes**

Run: `mvn -q test -Dtest=ComparisonJobManagerTest`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/example/shadowtest/job/ src/test/java/com/example/shadowtest/job/ComparisonJobManagerTest.java
git commit -m "feat: add ComparisonJobManager with async execution and active-id guard"
```

---

### Task 12: `ComparisonEngine` (wire fetch + buffer + comparator + anomalies)

**Files:**
- Create: `src/main/java/com/example/shadowtest/engine/ComparisonEngine.java`
- Test: `src/test/java/com/example/shadowtest/engine/ComparisonEngineTest.java`

**Interfaces:**
- Consumes: `EsLogFetcher`, `ComparatorRegistry`, `AnomalyWriter`, `ComparisonTaskConfig`, `TimeWindowIterator`, `MatchBuffer`, `JobState`, `DiffUtil`, `Anomaly`.
- Produces: `ComparisonEngine(EsLogFetcher fetcher, ComparatorRegistry registry, AnomalyWriter writer)` with `void run(String jobId, String taskId, ComparisonTaskConfig cfg, JobState state)`. The time range for the run comes from `cfg` start/end — add `startTime`/`endTime` fields to `ComparisonTaskConfig` (ISO-8601 strings) in this task.

> **Testing approach:** unit test the engine with a real `MatchBuffer`, real `HashResponseComparator`/`ComparatorRegistry`, a **fake `EsLogFetcher`** (subclass overriding `fetch`) returning canned records per window/index, and a **capturing `AnomalyWriter`** (subclass capturing the anomalies list). No ES needed.

- [ ] **Step 1: Add start/end to `ComparisonTaskConfig`**

Add to `ComparisonTaskConfig` (Task 8 file):
```java
    private String startTime; // ISO-8601, e.g. 2026-07-13T00:00:00Z
    private String endTime;

    public String getStartTime() { return startTime; }
    public void setStartTime(String startTime) { this.startTime = startTime; }
    public String getEndTime() { return endTime; }
    public void setEndTime(String endTime) { this.endTime = endTime; }
```

- [ ] **Step 2: Write the failing test**

```java
package com.example.shadowtest.engine;

import com.example.shadowtest.anomaly.Anomaly;
import com.example.shadowtest.anomaly.AnomalyWriter;
import com.example.shadowtest.comparator.ComparatorRegistry;
import com.example.shadowtest.comparator.HashResponseComparator;
import com.example.shadowtest.config.AnomalyProperties;
import com.example.shadowtest.config.ComparisonTaskConfig;
import com.example.shadowtest.es.EsLogFetcher;
import com.example.shadowtest.job.JobState;
import com.example.shadowtest.model.LogRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ComparisonEngineTest {

    private LogRecord rec(String index, String traceId, String body, String ts) {
        return new LogRecord(traceId, "h", Instant.parse(ts), "doc-" + traceId, index, body, "demo");
    }

    // Fake fetcher returns canned records keyed by (index, window-start).
    static class FakeFetcher extends EsLogFetcher {
        final Map<String, List<LogRecord>> byIndexAndWindow;
        FakeFetcher(Map<String, List<LogRecord>> data) { super(null); this.byIndexAndWindow = data; }
        @Override public List<LogRecord> fetch(String index, ComparisonTaskConfig cfg, TimeWindow w) {
            return byIndexAndWindow.getOrDefault(index + "@" + w.start(), List.of());
        }
    }

    static class CapturingWriter extends AnomalyWriter {
        final List<Anomaly> captured = new ArrayList<>();
        CapturingWriter() { super(null, new AnomalyProperties()); }
        @Override public void write(List<Anomaly> anomalies) { captured.addAll(anomalies); }
    }

    private ComparisonTaskConfig config() {
        ComparisonTaskConfig cfg = new ComparisonTaskConfig();
        cfg.setProduct("demo");
        cfg.setProdIndex("prod");
        cfg.setShadowIndex("shadow");
        cfg.setWindowMinutes(10);
        cfg.setStartTime("2026-07-13T00:00:00Z");
        cfg.setEndTime("2026-07-13T00:20:00Z");
        return cfg;
    }

    private ComparatorRegistry registry() {
        HashResponseComparator def = new HashResponseComparator();
        return new ComparatorRegistry(List.of(def), def);
    }

    @Test
    void matchingRecordsProduceNoAnomalies() {
        Map<String, List<LogRecord>> data = Map.of(
            "prod@2026-07-13T00:00:00Z", List.of(rec("prod", "t1", "same", "2026-07-13T00:01:00Z")),
            "shadow@2026-07-13T00:00:00Z", List.of(rec("shadow", "t1", "same", "2026-07-13T00:02:00Z")));
        CapturingWriter writer = new CapturingWriter();
        JobState state = new JobState("job1", "task-a", "demo");

        new ComparisonEngine(new FakeFetcher(data), registry(), writer)
            .run("job1", "task-a", config(), state);

        assertThat(writer.captured).isEmpty();
        assertThat(state.getMatched()).isEqualTo(1);
        assertThat(state.getTotalWindows()).isEqualTo(2);
    }

    @Test
    void mismatchProducesMismatchAnomalyWithBodies() {
        Map<String, List<LogRecord>> data = Map.of(
            "prod@2026-07-13T00:00:00Z", List.of(rec("prod", "t1", "prod-body", "2026-07-13T00:01:00Z")),
            "shadow@2026-07-13T00:00:00Z", List.of(rec("shadow", "t1", "shadow-body", "2026-07-13T00:02:00Z")));
        CapturingWriter writer = new CapturingWriter();
        JobState state = new JobState("job1", "task-a", "demo");

        new ComparisonEngine(new FakeFetcher(data), registry(), writer)
            .run("job1", "task-a", config(), state);

        assertThat(writer.captured).hasSize(1);
        Anomaly a = writer.captured.get(0);
        assertThat(a.type()).isEqualTo(Anomaly.Type.MISMATCH);
        assertThat(a.prodBody()).isEqualTo("prod-body");
        assertThat(a.shadowBody()).isEqualTo("shadow-body");
        assertThat(a.diff()).contains("+ shadow-body");
        assertThat(state.getMismatch()).isEqualTo(1);
    }

    @Test
    void crossWindowPairMatches() {
        Map<String, List<LogRecord>> data = Map.of(
            "prod@2026-07-13T00:00:00Z", List.of(rec("prod", "t1", "same", "2026-07-13T00:01:00Z")),
            "shadow@2026-07-13T00:10:00Z", List.of(rec("shadow", "t1", "same", "2026-07-13T00:11:00Z")));
        CapturingWriter writer = new CapturingWriter();
        JobState state = new JobState("job1", "task-a", "demo");

        new ComparisonEngine(new FakeFetcher(data), registry(), writer)
            .run("job1", "task-a", config(), state);

        assertThat(writer.captured).isEmpty();
        assertThat(state.getMatched()).isEqualTo(1);
    }

    @Test
    void leftoverRecordsBecomeUnmatchedAnomalies() {
        Map<String, List<LogRecord>> data = Map.of(
            "prod@2026-07-13T00:00:00Z", List.of(rec("prod", "only-prod", "p", "2026-07-13T00:01:00Z")),
            "shadow@2026-07-13T00:10:00Z", List.of(rec("shadow", "only-shadow", "s", "2026-07-13T00:11:00Z")));
        CapturingWriter writer = new CapturingWriter();
        JobState state = new JobState("job1", "task-a", "demo");

        new ComparisonEngine(new FakeFetcher(data), registry(), writer)
            .run("job1", "task-a", config(), state);

        assertThat(writer.captured).extracting(Anomaly::type)
            .containsExactlyInAnyOrder(Anomaly.Type.UNMATCHED_PROD, Anomaly.Type.UNMATCHED_SHADOW);
        assertThat(state.getUnmatched()).isEqualTo(2);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn -q test -Dtest=ComparisonEngineTest`
Expected: FAIL — `ComparisonEngine` does not exist.

- [ ] **Step 4: Create `ComparisonEngine`**

```java
package com.example.shadowtest.engine;

import com.example.shadowtest.anomaly.Anomaly;
import com.example.shadowtest.anomaly.AnomalyWriter;
import com.example.shadowtest.anomaly.DiffUtil;
import com.example.shadowtest.comparator.ComparatorRegistry;
import com.example.shadowtest.comparator.ResponseComparator;
import com.example.shadowtest.comparator.Signature;
import com.example.shadowtest.config.ComparisonTaskConfig;
import com.example.shadowtest.engine.MatchBuffer.Pair;
import com.example.shadowtest.engine.MatchBuffer.Side;
import com.example.shadowtest.job.JobState;
import com.example.shadowtest.model.LogRecord;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Runs one comparison job: iterate windows, pair by traceId, compare, emit anomalies. */
@Component
public class ComparisonEngine {

    private final EsLogFetcher fetcher;
    private final ComparatorRegistry registry;
    private final AnomalyWriter writer;

    public ComparisonEngine(EsLogFetcher fetcher, ComparatorRegistry registry, AnomalyWriter writer) {
        this.fetcher = fetcher;
        this.registry = registry;
        this.writer = writer;
    }

    public void run(String jobId, String taskId, ComparisonTaskConfig cfg, JobState state) {
        ResponseComparator comparator = registry.forProduct(cfg.getProduct());
        MatchBuffer buffer = new MatchBuffer();

        Instant start = Instant.parse(cfg.getStartTime());
        Instant end = Instant.parse(cfg.getEndTime());
        Duration window = Duration.ofMinutes(cfg.getWindowMinutes());

        TimeWindowIterator windows = new TimeWindowIterator(start, end, window);
        state.setTotalWindows(windows.totalWindows());

        while (windows.hasNext()) {
            TimeWindow tw = windows.next();
            List<Anomaly> anomalies = new ArrayList<>();

            List<LogRecord> prod = fetcher.fetch(cfg.getProdIndex(), cfg, tw);
            for (LogRecord r : prod) {
                offer(buffer, Side.PROD, r, comparator, cfg, taskId, jobId, tw, state, anomalies);
            }
            List<LogRecord> shadow = fetcher.fetch(cfg.getShadowIndex(), cfg, tw);
            for (LogRecord r : shadow) {
                offer(buffer, Side.SHADOW, r, comparator, cfg, taskId, jobId, tw, state, anomalies);
            }

            writer.write(anomalies);
            state.incCurrentWindow();
        }

        // Whatever remains after the whole range is unmatched.
        List<Anomaly> leftovers = new ArrayList<>();
        Instant now = Instant.now();
        for (MatchBuffer.Entry e : buffer.remainingProd()) {
            leftovers.add(unmatched(Anomaly.Type.UNMATCHED_PROD, e.record(), jobId, taskId, cfg, now));
        }
        for (MatchBuffer.Entry e : buffer.remainingShadow()) {
            leftovers.add(unmatched(Anomaly.Type.UNMATCHED_SHADOW, e.record(), jobId, taskId, cfg, now));
        }
        state.addUnmatched(leftovers.size());
        writer.write(leftovers);
    }

    private void offer(MatchBuffer buffer, Side side, LogRecord record, ResponseComparator comparator,
                       ComparisonTaskConfig cfg, String taskId, String jobId, TimeWindow tw,
                       JobState state, List<Anomaly> anomalies) {
        Signature sig = comparator.signature(record);
        buffer.offer(side, record, sig).ifPresent(pair -> {
            if (comparator.matches(pair.prod().signature(), pair.shadow().signature())) {
                state.incMatched();
            } else {
                anomalies.add(mismatch(pair, jobId, taskId, cfg, tw));
                state.incMismatch();
            }
        });
    }

    private Anomaly mismatch(Pair pair, String jobId, String taskId, ComparisonTaskConfig cfg, TimeWindow tw) {
        String prodBody = pair.prod().record().rawBody();
        String shadowBody = pair.shadow().record().rawBody();
        return new Anomaly(jobId, taskId, cfg.getProduct(), Anomaly.Type.MISMATCH,
            pair.prod().record().traceId(), pair.prod().record().host(),
            tw.start(), tw.end(),
            prodBody, shadowBody, DiffUtil.lineDiff(prodBody, shadowBody), Instant.now());
    }

    private Anomaly unmatched(Anomaly.Type type, LogRecord r, String jobId, String taskId,
                              ComparisonTaskConfig cfg, Instant now) {
        boolean isProd = type == Anomaly.Type.UNMATCHED_PROD;
        return new Anomaly(jobId, taskId, cfg.getProduct(), type,
            r.traceId(), r.host(), null, null,
            isProd ? r.rawBody() : null,
            isProd ? null : r.rawBody(),
            null, now);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn -q test -Dtest=ComparisonEngineTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/shadowtest/engine/ComparisonEngine.java src/main/java/com/example/shadowtest/config/ComparisonTaskConfig.java src/test/java/com/example/shadowtest/engine/ComparisonEngineTest.java
git commit -m "feat: add ComparisonEngine wiring fetch, pairing, comparison, anomalies"
```

---

### Task 13: `ComparisonController` + DTOs

**Files:**
- Create: `src/main/java/com/example/shadowtest/api/TriggerRequest.java`
- Create: `src/main/java/com/example/shadowtest/api/JobStatusResponse.java`
- Create: `src/main/java/com/example/shadowtest/api/ComparisonController.java`
- Create: `src/main/java/com/example/shadowtest/api/ApiExceptionHandler.java`
- Test: `src/test/java/com/example/shadowtest/api/ComparisonControllerTest.java`

**Interfaces:**
- Consumes: `ComparisonJobManager`, `JobState`.
- Produces:
  - `POST /comparisons` body `{"id":"<taskId>"}` → `202` `{"jobId":"..."}`; unknown id → `400`; duplicate → `409`.
  - `GET /comparisons/{jobId}` → `200` `JobStatusResponse`; unknown jobId → `404`.

- [ ] **Step 1: Create DTOs**

`TriggerRequest.java`:
```java
package com.example.shadowtest.api;

import jakarta.validation.constraints.NotBlank;

public record TriggerRequest(@NotBlank String id) {}
```

`JobStatusResponse.java`:
```java
package com.example.shadowtest.api;

import com.example.shadowtest.job.JobState;

public record JobStatusResponse(
        String jobId,
        String taskId,
        String product,
        String status,
        int currentWindow,
        int totalWindows,
        int matched,
        int mismatch,
        int unmatched,
        String error) {

    public static JobStatusResponse from(JobState s) {
        return new JobStatusResponse(
            s.getJobId(), s.getTaskId(), s.getProduct(), s.getStatus().name(),
            s.getCurrentWindow(), s.getTotalWindows(),
            s.getMatched(), s.getMismatch(), s.getUnmatched(), s.getError());
    }
}
```

- [ ] **Step 2: Write the failing test**

```java
package com.example.shadowtest.api;

import com.example.shadowtest.job.ComparisonJobManager;
import com.example.shadowtest.job.DuplicateTaskException;
import com.example.shadowtest.job.JobState;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ComparisonController.class)
class ComparisonControllerTest {

    @Autowired MockMvc mvc;
    @MockBean ComparisonJobManager manager;

    @Test
    void triggerReturns202WithJobId() throws Exception {
        Mockito.when(manager.trigger("task-a")).thenReturn("job-123");
        mvc.perform(post("/comparisons").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"task-a\"}"))
           .andExpect(status().isAccepted())
           .andExpect(jsonPath("$.jobId").value("job-123"));
    }

    @Test
    void unknownTaskReturns400() throws Exception {
        Mockito.when(manager.trigger("nope")).thenThrow(new IllegalArgumentException("unknown"));
        mvc.perform(post("/comparisons").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"nope\"}"))
           .andExpect(status().isBadRequest());
    }

    @Test
    void duplicateReturns409() throws Exception {
        Mockito.when(manager.trigger("task-a")).thenThrow(new DuplicateTaskException("task-a"));
        mvc.perform(post("/comparisons").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"task-a\"}"))
           .andExpect(status().isConflict());
    }

    @Test
    void statusReturns200() throws Exception {
        JobState state = new JobState("job-123", "task-a", "alpha");
        Mockito.when(manager.getJob("job-123")).thenReturn(Optional.of(state));
        mvc.perform(get("/comparisons/job-123"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.jobId").value("job-123"))
           .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void unknownJobReturns404() throws Exception {
        Mockito.when(manager.getJob("missing")).thenReturn(Optional.empty());
        mvc.perform(get("/comparisons/missing")).andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn -q test -Dtest=ComparisonControllerTest`
Expected: FAIL — controller does not exist.

- [ ] **Step 4: Create `ComparisonController`**

```java
package com.example.shadowtest.api;

import com.example.shadowtest.job.ComparisonJobManager;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/comparisons")
public class ComparisonController {

    private final ComparisonJobManager manager;

    public ComparisonController(ComparisonJobManager manager) {
        this.manager = manager;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> trigger(@Valid @RequestBody TriggerRequest request) {
        String jobId = manager.trigger(request.id());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("jobId", jobId));
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<JobStatusResponse> status(@PathVariable String jobId) {
        return manager.getJob(jobId)
            .map(JobStatusResponse::from)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
```

- [ ] **Step 5: Create `ApiExceptionHandler`**

```java
package com.example.shadowtest.api;

import com.example.shadowtest.job.DuplicateTaskException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(DuplicateTaskException.class)
    public ResponseEntity<Map<String, String>> conflict(DuplicateTaskException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn -q test -Dtest=ComparisonControllerTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/example/shadowtest/api/ src/test/java/com/example/shadowtest/api/ComparisonControllerTest.java
git commit -m "feat: add ComparisonController with trigger and status endpoints"
```

---

### Task 14: End-to-end integration test (Testcontainers)

**Files:**
- Test: `src/test/java/com/example/shadowtest/EndToEndIT.java`
- Test resource: `src/test/resources/application-e2e.yml`

**Interfaces:**
- Consumes: full app (`ComparisonJobManager`, `EsLogFetcher`, `ComparisonEngine`, `AnomalyWriter`) against a Testcontainers ES.
- Produces: proof the whole flow works — seeds prod/shadow indexes, triggers a job, waits for completion, asserts anomaly index contents.

- [ ] **Step 1: Write the end-to-end test**

```java
package com.example.shadowtest;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import com.example.shadowtest.job.ComparisonJobManager;
import com.example.shadowtest.job.JobStatus;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
class EndToEndIT {

    @Container
    static ElasticsearchContainer es = new ElasticsearchContainer(
            DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.15.0"))
            .withEnv("xpack.security.enabled", "false")
            .withEnv("discovery.type", "single-node");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        es.start();
        String[] hostPort = es.getHttpHostAddress().split(":");
        r.add("elasticsearch.host", () -> hostPort[0]);
        r.add("elasticsearch.port", () -> hostPort[1]);
        r.add("elasticsearch.scheme", () -> "http");
        // task config
        r.add("comparison.tasks.e2e.product", () -> "demo");
        r.add("comparison.tasks.e2e.prod-index", () -> "prod-e2e");
        r.add("comparison.tasks.e2e.shadow-index", () -> "shadow-e2e");
        r.add("comparison.tasks.e2e.host", () -> "api.example.com");
        r.add("comparison.tasks.e2e.host-field", () -> "host");
        r.add("comparison.tasks.e2e.time-field", () -> "@timestamp");
        r.add("comparison.tasks.e2e.trace-id-field", () -> "traceId");
        r.add("comparison.tasks.e2e.body-field", () -> "responseBody");
        r.add("comparison.tasks.e2e.window-minutes", () -> "10");
        r.add("comparison.tasks.e2e.page-size", () -> "500");
        r.add("comparison.tasks.e2e.start-time", () -> "2026-07-13T00:00:00Z");
        r.add("comparison.tasks.e2e.end-time", () -> "2026-07-13T00:30:00Z");
        r.add("anomaly.index-name", () -> "e2e-anomalies");
    }

    @Autowired ElasticsearchClient client;
    @Autowired ComparisonJobManager manager;

    void doc(String index, String traceId, String ts, String body) throws Exception {
        client.index(i -> i.index(index).refresh(Refresh.True).document(Map.of(
            "traceId", traceId, "host", "api.example.com", "@timestamp", ts, "responseBody", body)));
    }

    @Test
    void fullRunProducesExpectedAnomalies() throws Exception {
        // matching pair (same window)
        doc("prod-e2e", "match", "2026-07-13T00:01:00Z", "same");
        doc("shadow-e2e", "match", "2026-07-13T00:02:00Z", "same");
        // mismatch pair
        doc("prod-e2e", "diff", "2026-07-13T00:03:00Z", "prod-body");
        doc("shadow-e2e", "diff", "2026-07-13T00:04:00Z", "shadow-body");
        // cross-window match (prod window 1, shadow window 2)
        doc("prod-e2e", "cross", "2026-07-13T00:05:00Z", "x");
        doc("shadow-e2e", "cross", "2026-07-13T00:15:00Z", "x");
        // unmatched on each side
        doc("prod-e2e", "only-prod", "2026-07-13T00:06:00Z", "p");
        doc("shadow-e2e", "only-shadow", "2026-07-13T00:16:00Z", "s");
        client.indices().refresh(rq -> rq.index("prod-e2e", "shadow-e2e"));

        String jobId = manager.trigger("e2e");

        await().atMost(Duration.ofSeconds(30)).until(() ->
            manager.getJob(jobId).get().getStatus() == JobStatus.COMPLETED);

        client.indices().refresh(rq -> rq.index("e2e-anomalies"));
        long total = client.count(c -> c.index("e2e-anomalies")).count();
        // 1 mismatch + 2 unmatched = 3
        assertThat(total).isEqualTo(3L);

        var state = manager.getJob(jobId).get();
        assertThat(state.getMatched()).isEqualTo(2);   // "match" + "cross"
        assertThat(state.getMismatch()).isEqualTo(1);
        assertThat(state.getUnmatched()).isEqualTo(2);
    }
}
```

- [ ] **Step 2: Add Awaitility test dependency**

Add to `pom.xml` `<dependencies>`:
```xml
        <dependency>
            <groupId>org.awaitility</groupId>
            <artifactId>awaitility</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 3: Run test to verify it passes**

Run: `mvn -q test -Dtest=EndToEndIT`
Expected: PASS (Docker required).

- [ ] **Step 4: Run the full suite**

Run: `mvn -q verify`
Expected: all unit + IT tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/example/shadowtest/EndToEndIT.java pom.xml
git commit -m "test: add end-to-end integration test for full comparison flow"
```

---

## Manual Verification

1. Start a local ES 8.x (or point at an existing one) and set `elasticsearch.*` in `application.yml`.
2. Define a real task under `comparison.tasks.<id>` with the correct index names, host, field names, and `start-time`/`end-time`.
3. `mvn spring-boot:run`
4. Trigger: `curl -s -X POST localhost:8080/comparisons -H 'Content-Type: application/json' -d '{"id":"<id>"}'` → note the `jobId`.
5. Poll: `curl -s localhost:8080/comparisons/<jobId>` until `status` is `COMPLETED`; check `matched`/`mismatch`/`unmatched`.
6. Inspect anomalies: `curl -s 'localhost:9200/shadow-test-anomalies/_search?pretty'`.

## Adding a Custom Per-Product Comparator (extension point)

Create a Spring bean implementing `ResponseComparator`, returning your product from `product()`. It is auto-registered by `ComparatorRegistry`. Example — ignore a volatile `timestamp` JSON field before hashing:

```java
@Component
public class OrdersComparator implements ResponseComparator {
    @Override public String product() { return "orders"; }
    @Override public Signature signature(LogRecord r) {
        String normalized = r.rawBody().replaceAll("\"timestamp\":\"[^\"]*\"", "\"timestamp\":\"\"");
        return new Signature(DigestUtils.sha256Hex(normalized)); // or reuse a shared hash helper
    }
}
```

---

## Self-Review Notes

- **Spec coverage:** trigger API + id→config (Task 8/11/13); async + jobId + status (Task 11/13); ES 8 fetch by host+time (Task 9); 10-min windows (Task 5/12); traceId pairing incl. cross-window (Task 6/12); default hash + pluggable per-product (Task 3/4); mismatch with full body/diff (Task 7/12); unmatched-at-end anomalies (Task 12); write-back to anomaly index with product/jobId (Task 10/12); per-job isolation + duplicate guard (Task 6/11). All covered.
- **Type consistency:** `ComparisonEngine.run(String, String, ComparisonTaskConfig, JobState)` matches the mock in Task 11 and the call in Task 12. `MatchBuffer.offer` returns `Optional<Pair>` used consistently. `ResponseComparator.DEFAULT_PRODUCT` used in Tasks 3/4.
- **Known version-sensitivity:** the ES 8.x Java client DSL (range query builder, `_shard_doc` sort, `searchAfter`) can vary across 8.x minors; Task 9 notes fallbacks. Verify against the exact `elasticsearch-java` version pinned in `pom.xml`.
