# Testing the spring-boot-team pipeline

Four scenarios that validate whether the kit actually delivers on the workspace's 9 goals. Each gives you an **exact prompt to paste**, the **expected artifacts/paths**, and a **pass/fail checklist**. Run them in a scratch Spring Boot project (see setup below) so `./mvnw` exists.

> Nothing in these scenarios should ever be committed or staged by an agent (hard rule #1). If any agent runs `git add`/`commit`/`push`, that is an automatic fail.

## Setup: a scratch target project

The pipeline writes into whatever project you run it from. For scenarios 1–3 you need a Maven Spring Boot project on disk:

```bash
# Generate a minimal Spring Boot 3.x project (Java 21) via start.spring.io
curl -s https://start.spring.io/starter.zip \
  -d dependencies=web,data-jpa,security,validation,postgresql \
  -d type=maven-project -d javaVersion=21 -d bootVersion=3.4.0 \
  -d groupId=com.example -d artifactId=demo -o demo.zip
unzip demo.zip -d spring-demo && cd spring-demo
```

Open Claude Code in `spring-demo/` with the kit installed (see `README.md`). Scenario 4 needs only a snippet and can run anywhere.

---

## Scenario 1 — Greenfield CRUD (goals 3, 4, 5, 6)

**Prompt to paste:**

```
/spring-feature Add a user registration endpoint. Accept email and password, persist the
user in Postgres with the password hashed (never in plaintext), reject duplicate emails and
invalid email formats, and return 201 with the created user id (never the password hash).
```

**Expected artifacts** (in `docs/pipeline/user-registration/`):

- `01-spec.md` — actors, `FR-*` (register, reject duplicate, reject invalid email), acceptance criteria in Given/When/Then, `NFR-*` (password never stored/returned in plaintext), out-of-scope.
- `02-architecture.md` — C4 context/container/component, layering (controller/service/repository), FR/NFR traceability.
- `adr/ADR-0001-*.md` (or similar) — a decision on password hashing (**BCrypt**) with ≥2 options, trade-offs, and an authoritative citation.
- `03-tech-spec.md` — `User` entity, `UserRepository`, `RegistrationService`, DTOs (request/response records), Bean Validation annotations, and a test plan.
- `src/test/java/**` and `src/main/java/**` — tests written first, then code.

**Pass/fail checklist:**

- [ ] Tests exist for each acceptance criterion and were written **before** the implementation (verify via the implementer's changelog / test-first ordering).
- [ ] `./mvnw test` passes.
- [ ] Password is hashed with `BCryptPasswordEncoder` (or Argon2) — **no plaintext** persisted anywhere; grep the code to confirm.
- [ ] The response DTO does **not** expose the password/hash.
- [ ] Email uniqueness and format are enforced (DB constraint and/or `@Email` + `@Column(unique=true)`).
- [ ] Code has **no noise comments** and **no "AI-generated" comment** (hard rules #2/#3, goal 4).
- [ ] `04-qa-report.md` confirms every acceptance criterion is met and maps `FR-*` to a passing test.
- [ ] `05-audit-report.md` is clean (no Critical/High) and its findings, if any, cite authorities.
- [ ] Nothing was committed or staged.

---

## Scenario 2 — Architectural decision (goals 7, 8)

**Prompt to paste:**

```
/spring-feature Recommend and set up an authentication approach for our REST API. We're a
small team, the API is consumed by a single-page app and a mobile client, and we may add
third-party integrations later.
```

**Expected behavior:**

- The Architect **raises open questions** in `02-open-questions.md` (e.g. session vs stateless JWT vs OAuth2 resource server; token lifetime; refresh strategy) and the orchestrator **STOPS** and surfaces them to you verbatim — it does not guess.
- After you answer, an ADR is produced with ≥2 options, explicit trade-offs, a decision, and consequences — each load-bearing claim **citing an authoritative source** (Spring Security reference, OWASP Cheat Sheet, RFC 6749/7519).
- The design is revised to reflect your answers (iteration).

**Pass/fail checklist:**

- [ ] `02-open-questions.md` is produced and the pipeline **pauses** for your input (does not auto-proceed).
- [ ] The ADR presents at least two options with trade-offs, not a single asserted answer.
- [ ] Every authority cited is on the `authoritative-references` allowlist (docs.spring.io, owasp.org, jakarta.ee, RFC editor) — **no Baeldung/Medium/Stack Overflow** as authority.
- [ ] Claims without a source are marked `[ASSUMPTION]`, not presented as fact.
- [ ] After you answer, `02-architecture.md` and the ADR are updated to match (visible iteration).

---

## Scenario 3 — Audit a flawed snippet (goals 3, 6, + security)

Drop this deliberately flawed class into the scratch project (e.g. `src/main/java/com/example/demo/BadUserService.java`):

```java
@Service
public class BadUserService {
    @Autowired private JdbcTemplate jdbc;
    private static final String API_KEY = "HARDCODED-DO-NOT-COMMIT-EXAMPLE-KEY";

    public List<Map<String,Object>> find(String email) {
        return jdbc.queryForList("SELECT * FROM users WHERE email = '" + email + "'");
    }

    public void register(String email, String rawPassword) {
        jdbc.update("INSERT INTO users(email,password) VALUES('" + email + "','" + rawPassword + "')");
    }

    public List<Order> ordersFor(User u) {
        List<Order> all = new ArrayList<>();
        for (User friend : u.getFriends()) {            // triggers a query per friend (N+1)
            all.addAll(friend.getOrders());
        }
        return all;
    }
}
```

**Prompt to paste:**

```
/spring-audit src/main/java/com/example/demo/BadUserService.java
```

**Expected findings** (in `05-audit-report.md` or inline):

| Flaw | Expected severity | Expected citation |
|---|---|---|
| SQL built by string concatenation (injection) | Critical/High | OWASP Cheat Sheet — SQL Injection Prevention |
| Password stored in plaintext | Critical/High | OWASP Cheat Sheet — Password Storage; Spring Security password-storage docs |
| Hardcoded secret (`API_KEY`) | High | OWASP ASVS / Secrets Management guidance |
| N+1 query in `ordersFor` | Medium | Spring Data JPA / Jakarta Persistence fetch-strategy docs |
| Missing `@Transactional` on the write path | Medium | Spring Framework transaction management docs |

**Pass/fail checklist:**

- [ ] Every flaw above is enumerated with a **severity** and a `file:line`.
- [ ] Every finding carries an **authoritative citation** (OWASP/Spring/Jakarta), not a blog.
- [ ] Each finding has a one-line fix.
- [ ] A principle scorecard (SOLID / Clean Arch / DDD / Security / DRY-YAGNI) is present.
- [ ] The auditor did **not** modify the code (report only).

---

## Scenario 4 — Comment-pollution / doc-placement (goal 4 + hard rule #3)

Paste this over-commented snippet and ask for cleanup (no project needed):

```java
// This class handles user stuff
public class UserService {
    // The repository we use to talk to the database. We inject it via the constructor
    // because field injection is bad. Constructor injection is a best practice recommended
    // by the Spring team and many blog posts because it makes dependencies explicit and
    // supports immutability and easier testing. See the long discussion in our wiki.
    private final UserRepository repo;
    public UserService(UserRepository repo) { this.repo = repo; } // constructor
    // This method gets a user by id. If not found it throws.
    public User get(Long id) {
        // fetch from the repo
        return repo.findById(id)
            .orElseThrow(() -> new NotFoundException(id)); // throw if missing
    }
}
```

**Prompt to paste:**

```
Clean up the comment pollution in this class per our CLAUDE.md rules: strip comments to
non-obvious *why* only, and move any real rationale (e.g. why constructor injection) into a
doc or ADR rather than leaving it in the code.
```

**Pass/fail checklist:**

- [ ] The redundant "what" comments (`// constructor`, `// fetch from the repo`, `// throw if missing`) are **removed**.
- [ ] The long rationale about constructor injection is **moved to a doc/ADR**, not left inline.
- [ ] Any remaining comment is a genuine non-obvious *why* (ideally: none needed here).
- [ ] No "AI-generated"/"generated by" comment is added anywhere (hard rule #2).
- [ ] Behavior is unchanged (same method signatures and logic).

---

## Scoring

For each scenario, count checklist items passed / total. A scenario **passes** only if all security-relevant and hard-rule items pass (no plaintext passwords, no injection missed, no commit, no AI-authorship comment). Record results in the benchmark template at `docs/03-benchmark.md` (repo root) to compare against a single-agent baseline.
