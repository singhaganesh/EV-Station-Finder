# EV Station Finder — Improvement Roadmap

This document outlines concrete improvements organized by priority and impact. Start with **Security** (blocking production), then **Correctness**, then **Production Readiness**, then **Code Hygiene**.

---

## Security Issues (BLOCKING — Fix before shipping)

### 1. Public destructive endpoints

**Problem:** `POST /api/import/trigger` and `POST /api/stations/cleanup-duplicates` are unauthenticated, unlogged, and expensive/destructive.

**Impact:** Anyone who discovers your server (via decompiled APK, network sniffing, or a leak) can trigger expensive OCM imports (hammering an external API and your DB) or delete "duplicate" stations arbitrarily, corrupting your data.

**Fix:**
- Add authentication to the entire backend (see the "Authentication" section of the main report for the recommended approach).
- Make these endpoints **admin-only** (require a role beyond a regular authenticated user).
- OR remove them from the public API entirely and provide an admin panel / CLI tool instead.
- Add access logging (who called it, when, what happened).

**Effort:** Medium (couples to your auth choice; ~1–2 days if you pick Firebase Auth).

---

### 2. Spoofable reviews

**Problem:** `POST /api/stations/{id}/reviews` accepts any `reviewerName` from the client, so anyone can post reviews under any name. Ratings are untrusted.

**Impact:** The review system is a vector for vandalism or fake campaigns ("Best Charger in India 10/10!!!"). Users can't trust the ratings.

**Fix:**
- Require authentication on the review endpoints.
- Derive `reviewerName` from the authenticated user, not the request body (or allow them to set a display name in their profile).
- Prevent duplicate reviews from the same user (check `station_id` + `user_id` uniqueness).
- *Optional*: add a moderation flag (`approved` boolean) and only show approved reviews by default.

**Effort:** Low (1 day once auth is in place).

---

### 3. Cleartext traffic on client

**Problem:** `android:usesCleartextTraffic="true"` allows HTTP in release builds. The default `BASE_URL` is `http://10.0.2.2:8081/` (HTTP, not HTTPS).

**Impact:** In production, HTTP is not encrypted; credentials (eventually auth tokens) and station data transit in the clear. Vulnerable to MITM.

**Fix:**
- Set `usesCleartextTraffic="false"` in `AndroidManifest.xml` (or remove the line — it defaults to false).
- Ensure `BASE_URL` in release builds is `https://your-api-domain.com:8081/` (or port 443).
- Use DNS-over-HTTPS or certificate pinning for extra hardening if data sensitivity is high.

**Effort:** Minimal (1 hour).

---

### 4. Request/response body logging in release

**Problem:** OkHttp's `HttpLoggingInterceptor` is set to `Level.BODY` unconditionally in `RetrofitClient.kt`. In a release APK, this logs full request/response bodies (including future auth tokens) to Logcat (readable by any other app on the device, or via `adb logcat`).

**Impact:** Auth tokens, personal station data, and reviews leak to device logs.

**Fix:**
```java
// In RetrofitClient.kt, before returning the retrofit instance:
HttpLoggingInterceptor.Level logLevel = BuildConfig.DEBUG 
    ? HttpLoggingInterceptor.Level.BODY 
    : HttpLoggingInterceptor.Level.NONE;

HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
logging.setLevel(logLevel);
```

Alternatively, remove logging entirely in release builds.

**Effort:** Minimal (15 minutes).

---

## Correctness Issues (High-priority bugs)

### 5. Transactional batch import is fragile

**Problem:** In `StationImportService.importFromOCM`, the method is `@Transactional`. It loops over OCM nodes and calls `transformAndSave(node)` in a try/catch. 

However, **`transformAndSave` is also `@Transactional`, but it's called via `this.transformAndSave`** (self-invocation). Spring's AOP proxies don't intercept self-calls, so the `@Transactional` annotation on `transformAndSave` is **ignored**. All row saves happen inside the caller's single transaction.

If one row throws a deferred constraint violation (e.g., the unique `(name, latitude, longitude)` index), the whole transaction becomes rollback-only. Subsequent saves fail at commit with `UnexpectedRollbackException`, rolling back the entire batch — even though the per-row `try/catch` looks like it isolated the failure.

**Impact:** A single duplicate or constraint violation can cause the entire import to fail, leaving the DB inconsistent. The try/catch gives a false sense of isolation.

**Fix — Option A (Recommended):**
Move `transformAndSave` to a separate `@Component` bean (e.g., `StationTransformer`) so Spring can proxy its `@Transactional(REQUIRES_NEW)` call:

```java
@Component
public class StationTransformer {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean transformAndSave(JsonNode ocmNode) {
        // ... existing logic
    }
}

@Service
public class StationImportService {
    @Autowired private StationTransformer transformer;
    
    @Transactional
    public int importFromOCM(...) {
        // ...
        for (JsonNode node : root) {
            try {
                if (transformer.transformAndSave(node)) {
                    imported++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                log.warn("Failed to import OCM station: {}", e.getMessage());
                skipped++;
            }
        }
        // ...
    }
}
```

Now each row gets its own transaction; a single failure doesn't roll back the whole batch.

**Fix — Option B (Simpler but less resilient):**
Replace the per-row try/catch with a batch commit strategy:

```java
@Transactional
public int importFromOCM(...) {
    List<Station> toSave = new ArrayList<>();
    // Collect all transformed stations
    for (JsonNode node : root) {
        Station s = transform(node); // no save yet
        if (s != null) toSave.add(s);
    }
    // Single bulk save
    stationRepository.saveAll(toSave);
    return toSave.size();
}
```

This is simpler and faster, but if one row fails, the whole batch rolls back. Choose based on your tolerance for partial failures.

**Effort:** Low (1–2 hours). Option A is more robust; Option B is simpler.

---

### 6. N+1 on the primary list endpoints

**Problem:** `getNearbyStations` and `searchStations` call `enrichWithScore(station, lat, lng)`, which calls `chargerSlotRepository.findByStationId(station.getId())` per station. For `/api/stations/nearby` (the main List-tab load), that's up to ~20 extra queries.

The route path already solves this correctly with `findByStationIdIn(stationIds)` batch query; list endpoints should do the same.

**Impact:** Slow `/nearby` and `/search` responses; extra DB load and latency.

**Fix:**
Batch the slot queries. In `StationService`:

```java
public List<StationWithScore> getNearbyStations(double lat, double lng, double radiusKm, int limit) {
    // ... existing bounding-box logic to get stations ...
    
    // Batch load slots instead of per-station
    List<Long> stationIds = stations.stream().map(Station::getId).collect(Collectors.toList());
    Map<Long, List<ChargerSlot>> slotsByStation = 
        chargerSlotRepository.findByStationIdIn(stationIds).stream()
            .collect(Collectors.groupingBy(cs -> cs.getStation().getId()));
    
    // Now enrich using the preloaded slots
    return stations.stream()
        .map(s -> enrichWithScore(s, lat, lng, slotsByStation.getOrDefault(s.getId(), new ArrayList<>())))
        .filter(s -> s.getDistance() <= radiusKm)
        .sorted(Comparator.comparingDouble(StationWithScore::getDistance))
        .limit(limit)
        .collect(Collectors.toList());
}
```

Refactor `enrichWithScore` to accept pre-loaded slots:

```java
private StationWithScore enrichWithScore(Station station, double distance, List<ChargerSlot> slots) {
    // Use the passed-in slots instead of querying
    // ... existing logic
}
```

Do the same for `searchStations`.

**Effort:** Low (2–3 hours, mostly testing).

---

## Production-Readiness Issues

### 7. Dependence on public demo routing/geocoding services

**Problem:** The backend calls `nominatim.openstreetmap.org` (OpenStreetMap) and `router.project-osrm.org` (OSRM public demo) for geocoding and routing. Both are:
- **Rate-limited** — Nominatim has strict per-second limits; OSRM's public server can throttle.
- **No SLA** — maintained by volunteers; can be slow or unavailable.
- **Against ToS** — OSRM explicitly says the public server is "not for production"; Nominatim requires proper User-Agent and attribution.
- **Not designed for production traffic.**

**Impact:** Under real load or during peak hours, routing/geocoding will fail or be very slow. The fallbacks are weak (straight-line route, geocoding errors).

**Fix — Pick one approach:**

**Option A: Self-hosted**
- Host your own **Nominatim** instance (PostGIS + Nominatim, ~5–10 GB download, can run on a decent VPS).
- Host your own **OSRM** instance (lighter; compiled routing engine).
- Update the URLs in `StationService`:
  ```java
  // In application.properties
  nominatim.url=https://your-nominatim.example.com
  osrm.url=https://your-osrm.example.com
  ```

**Option B: Paid SaaS**
- Use **Mapbox Directions** (routing) + **Mapbox Geocoding** (geocoding). Pricing scales with usage; free tier is generous.
- Use **Google Maps Directions API** + **Geocoding API**. More expensive, but battle-tested.
- Use **GraphHopper** (all-in-one). Good pricing; good for India.

Whichever you pick, **update the User-Agent** and follow the ToS. In `StationImportService`, the User-Agent already includes a contact email — make sure that's real.

**Effort:** Medium (1–2 days for self-hosted setup; 1 day to swap to a paid provider + credential management).

---

### 8. Schema migrations are unversioned

**Problem:** `spring.jpa.hibernate.ddl-auto=update` lets Hibernate mutate your schema at runtime. This is convenient in dev but risky in prod: if a bad migration runs, rolling back is manual and error-prone.

**Impact:** A deploy with a schema bug can lock you out of the DB or corrupt data, with no easy rollback.

**Fix:**
Adopt **Flyway** (simpler, SQL-based) or **Liquibase** (more powerful, XML/YAML/SQL):

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
```

```properties
# application.properties
spring.jpa.hibernate.ddl-auto=validate  # Don't auto-migrate
spring.flyway.locations=classpath:db/migration
```

Create versioned migration files:
```
src/main/resources/db/migration/
  V1__Initial_schema.sql
  V2__Add_reviews.sql
  V3__Add_user_accounts.sql  (you'll need this for auth)
```

Flyway/Liquibase track what's been run and refuse to re-run; you can inspect/rollback if needed.

**Effort:** Low (4–6 hours, mostly one-time setup). Saves you from chaos later.

---

### 9. Error responses leak internals

**Problem:** Controllers return HTTP 500 with raw exception messages:
```java
catch (Exception e) {
    return ResponseEntity.internalServerError()
            .body(ApiResponse.error("Error: " + e.getMessage()));
}
```

A stack trace or a database error message tells an attacker about your tech stack and possible vulnerabilities.

**Impact:** Information disclosure; aids reconnaissance.

**Fix:**
Add a global exception handler:

```java
@ControllerAdvice
public class GlobalExceptionHandler {
    
    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiResponse<?>> handleNotFound(EntityNotFoundException e) {
        return ResponseEntity.status(404)
            .body(ApiResponse.error("Resource not found"));
    }
    
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleGeneric(Exception e) {
        log.error("Unexpected error", e);  // Log the real error internally
        return ResponseEntity.status(500)
            .body(ApiResponse.error("Internal server error"));  // Generic response
    }
}
```

Now errors are sanitized; the real stack trace is logged (for debugging) but never sent to clients.

**Effort:** Low (2–3 hours).

---

### 10. No automated tests

**Problem:** Test dependencies are in the `pom.xml` (JUnit, Mockito), but there are no test files in the tree. The Docker build skips tests with `-DskipTests`.

**Impact:** Regressions slip through; refactoring is risky; integration bugs between layers are caught late.

**Fix:**
Start a thin test layer targeting the riskiest paths:

```java
// src/test/java/com/ganesh/finder/service/StationServiceTest.java
@SpringBootTest
public class StationServiceTest {
    
    @Autowired private StationService service;
    @MockBean private StationRepository stationRepo;
    
    @Test
    public void testGetStationsInViewportOptimized() {
        // Arrange: mock a few stations
        // Act: call the service
        // Assert: verify distance sorting, capping at 200, caching
    }
}
```

Aim for:
- **Controllers:** happy path + error cases (bad input, 404).
- **Service business logic:** scoring, dedup, route corridor selection.
- **Repositories:** custom JPQL queries (viewport, dedup).

Don't aim for 100% coverage (it's a trap); focus on the paths that break often.

**Effort:** Medium (1–2 weeks for a reasonable baseline). Start small; add tests as you touch code.

---

## Code Cleanup (Nice to have)

### 11. Dead / overlapping code

**Problem:** Several methods in `StationViewModel` appear unused:
- `fetchNearbyStationsDebounced`
- `fetchNearbyStationsForZoom`
- `fetchCarouselStations`
- `fetchStationsAlongRoute`

There's also a non-optimized `getStationsInViewport(neLat, ...)` overload in `StationRepository` that no controller calls (only the optimized one is used).

`ocm.sync.country-id=101` is set in properties but `importForCountry` uses `countrycode=IN` (string); the property is unused.

**Impact:** Code smell; confuses future developers; makes refactoring risky.

**Fix:**
- Do a call-site audit: determine if each overlapping method is truly unused, or if there's a bug (it *should* be called but isn't).
- Delete unused overloads; consolidate duplicate logic.
- Remove unused properties.

**Effort:** Low (2–4 hours).

---

### 12. Operator name parsing is fragile

**Problem:** In the client's `OCMModels.kt`:

```kotlin
val operatorName: String
    get() {
        if (meta.isNullOrEmpty()) return "Independent Operator"
        val match = Regex("\"ocm_operator\":\"([^\"]+)\"").find(meta)
        return match?.groupValues?.get(1) ?: "Independent Operator"
    }
```

Regex-parsing JSON is brittle. If `meta` format changes or contains edge cases (escaped quotes, etc.), this breaks silently.

**Impact:** Operators show as "Independent Operator" unexpectedly; minor UX bug.

**Fix:**
Expose `operatorName` as a first-class field in the backend's `StationWithScore` DTO:

```java
// In StationService.enrichWithScore
String operatorName = extractOperatorFromMeta(station.getMeta());
// ...
return StationWithScore.builder()
    .operatorName(operatorName)
    // ...
    .build();
```

Parse the `meta` JSON once server-side (using `ObjectMapper`); send a clean field to the client.

**Effort:** Low (1 hour).

---

### 13. Hardcoded placeholder UI in RoutePlannerScreen

**Problem:** When route distance/duration are 0, the UI shows:

```kotlin
val formattedDistance = if (routeDistanceKm > 0.0) "${routeDistanceKm} km" else "145 km"
val formattedDuration = if (routeDurationSec > 0.0) { ... } else "3h 15m"
```

These hardcoded values can mislead users (they look real).

**Fix:**
Replace with dashes or hide:

```kotlin
val formattedDistance = if (routeDistanceKm > 0.0) "${routeDistanceKm} km" else "—"
val formattedDuration = if (routeDurationSec > 0.0) { ... } else "—"
```

Or hide the whole panel until a route is available.

**Effort:** Minimal (30 minutes).

---

### 14. Two `@SpringBootApplication` classes in the same package

**Problem:** Both `FinderApplication` and `StationImporterApp` are annotated `@SpringBootApplication` in `com.ganesh.finder`. When `FinderApplication` is the main entry, its component scan might accidentally pick up the importer's beans.

Currently it works because you invoke the importer deliberately, but it's a smell and a future trap.

**Impact:** Unexpected beans registered; confusing debug output; risk of breaking if someone refactors component scan.

**Fix:**
- Move the importer to a separate package: `com.ganesh.finder.importer.StationImporterApp`.
- OR remove the importer from the same jar and make it a separate `<module>` in Maven.
- OR guard the importer's `@SpringBootApplication` behind a condition:

```java
@ConditionalOnProperty(name = "app.mode", havingValue = "importer")
@SpringBootApplication
public class StationImporterApp { ... }
```

**Effort:** Low (1 hour).

---

### 15. Zoom-to-radius mapping could be tuned

**Problem:** In `StationViewModel`:

```kotlin
private fun calculateRadiusFromZoom(zoom: Float): Double {
    return when {
        zoom >= 15f -> 5.0
        zoom >= 12f -> 15.0
        zoom >= 10f -> 30.0
        zoom >= 8f -> 100.0
        zoom >= 6f -> 300.0
        zoom >= 4f -> 1000.0
        else -> 3000.0
    }
}
```

These thresholds are somewhat arbitrary. Observe real user zoom levels and adjust to minimize API calls while keeping the map data fresh.

**Impact:** Inefficiency; extra API calls on zoom, or stale data.

**Fix:**
A/B test different thresholds and adjust based on click-through / zoom patterns.

**Effort:** Medium (1–2 days, requires metrics/logging + experimentation).

---

## Summary: Implementation Order

For the fastest path to a shippable, secure, correct system:

1. **Week 1: Security lockdown**
   - Add Firebase Auth (or your chosen auth).
   - Lock `/import` and `/cleanup-duplicates` to admin-only.
   - Require auth on reviews; derive reviewer from identity.
   - Turn off cleartext; disable body logging in release.

2. **Week 2: Correctness**
   - Fix the transactional import batch (move to separate bean).
   - Batch the slot queries on `/nearby` and `/search`.

3. **Week 3: Production readiness**
   - Replace Nominatim/OSRM with self-hosted or a paid provider.
   - Adopt Flyway migrations.
   - Add a global exception handler.

4. **Ongoing: Code & tests**
   - Add a baseline test suite (controllers, service, repos).
   - Prune dead code.
   - Improve error messages.

If you have a small team and shipping speed matters, do Security + the two Correctness bugs, then ship. Add Production Readiness and Code Cleanup as post-launch follow-ups.

---

*End of improvement roadmap.*
