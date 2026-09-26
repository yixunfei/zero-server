# zero-server — Project Notes

## Build / toolchain (Windows + Git Bash)

- Project requires **JDK 21**. Machine default `java` is JDK 17 — not sufficient.
  Working JDK: `D:\env\jdk21`.
- **The Maven wrapper (`./mvnw`) is broken under Git Bash.** Passing a POSIX-style
  `JAVA_HOME` (`/d/env/jdk21`) to the Windows JVM causes
  `ClassNotFoundException: org.codehaus.plexus.classworlds.launcher.Launcher`.
- Workaround that works: invoke the JVM directly with **Windows-style paths**.
  The classworlds launcher needs **three** properties — dropping any one fails
  with a misleading, unrelated-looking error (all three observed on 2026-09-17):

  ```
  D:\env\jdk21\bin\java.exe \
    -classpath "D:\env\apache-maven-3.9.8\boot\plexus-classworlds-2.8.0.jar" \
    -Dmaven.home="D:\env\apache-maven-3.9.8" \
    -Dclassworlds.conf="D:\env\apache-maven-3.9.8\bin\m2.conf" \
    -Dmaven.multiModuleProjectDirectory=L:\zero-server \
    org.codehaus.plexus.classworlds.launcher.Launcher \
    -B -T 1C test
  ```

  Failure modes if a property is missing:
  - no `-Dclassworlds.conf` → `Exception: classworlds configuration not specified nor found in the classpath`
  - `-Dclassworlds.conf` but no `-Dmaven.home` → `ConfigurationException: No such property: maven.home`
  - `-Dmaven.home` + `-Dclassworlds.conf` but no `-classpath` → `ClassNotFoundException: ...classworlds.launcher.Launcher`

- `./mvnw` **and** the `mvn` on PATH both fail under Git Bash with
  `ClassNotFoundException: ...classworlds.launcher.Launcher`, even when
  `JAVA_HOME="D:\env\jdk21"` is already Windows-style. Do not rely on them —
  use the direct-JVM form above.

- Maven console output is **GBK-encoded**. Decode compile errors with
  `iconv -f GBK -t UTF-8` when reading captured output.
- Baseline: full `test` → **BUILD SUCCESS, 54/54 modules, 0 failures, ~48s**
  (measured 2026-09-17 on the working tree; the figure drifts as the tree moves,
  so re-measure rather than quoting ~42s).

## Repo state as of 2026-09-17

- Long-lived branch `codex/documentation-cleanup`, with a **large uncommitted
  working set**: ~136 changes (63 modified, 39 deleted, 34 untracked; 8 untracked
  are real `.java` sources). The diff is 1080 insertions vs **9254 deletions** —
  it is mostly a documentation reorg (`docs/` → `docs/reference/`), *not* a
  feature implementation. `tasks/active/bug-audit-20260916/RISK.md` already
  records "当前工作树已有用户变更".
- Consequence: **do not assume uncommitted work is yours to finish or commit.**
  Check `git status`/`git log` first and ask before building on top of it.


## Conventions observed

- 50+ Maven modules, groupId `group.zn.zero`, package prefix `group.zn.zero`.
- Canonical repo guidance lives in `.codex/AGENTS.md` (not the root `AGENTS.md`,
  which is only a marker). Read `.codex/AGENTS.md` before changing:
  thread models, cache strategy, persistence flow, protocol wire format,
  scheduling, or error semantics.
- §18: swallowing exceptions is forbidden; outward-facing errors must carry an
  `ErrorCode`. §14: on persistence failure keep the cache and preserve the site.
  §10: event bus must support priority / interceptor / retry / dead-letter /
  idempotency. §12: RPC `timeoutAt` must be enforced.
- `.codex/AGENTS.md` §6 "high-risk pause rule": **pause before changing thread
  model / cache strategy / persistence flow / protocol format / scheduling** —
  require explicit user confirmation.

## Verification standard adopted for this repo

Claiming a bug requires a **compiled + executed reproduction**. Static analysis
and sub-agent reports alone produced 6+ false positives here (presence-bitmap
symmetry, actor lane serialization, cross-lane blocking, local-actor message
loss, NPC skip double-count were all believed-to-be-bugs but did NOT reproduce).
Discard unverified findings or record them explicitly as "suspected".
