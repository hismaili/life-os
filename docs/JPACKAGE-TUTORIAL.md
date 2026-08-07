# Tutorial: Packaging a Java CLI with jpackage

`jpackage` (bundled with the JDK since 14) turns a jar into a native, double-clickable
installer — `.dmg`/`.pkg` on macOS, `.msi`/`.exe` on Windows, `.deb`/`.rpm` on Linux — with a
JRE baked in. Users don't install Java; they install your app.

This isn't a copy of the official docs. It's a walkthrough built from actually running every
command below against a real example app, reproducing the exact failures a first attempt hits,
and fixing them. Where I say "this fails with," that's the literal error text jpackage printed,
not a paraphrase.

**A note on visuals**: this was written in an environment where OS-level screenshots weren't
available, so instead of pictures of Finder windows, every step shows the actual terminal
transcript and file-tree output. For a CLI packaging tool that's arguably more useful anyway —
you can diff it against your own output.

**Verified on**: macOS, JDK 23 (`jpackage --version` → `23.0.2`). Everything here applies from
JDK 14 onward; jpackage's core behavior hasn't changed since it left incubator status. The
Windows/Linux sections are accurate to the documented behavior but I could only verify the macOS
path hands-on — say so explicitly where it matters.

## The example app

A minimal CLI, no framework, no dependencies — so nothing here is specific to Spring, Maven
Shade, or any particular stack. It does two things deliberately: it takes an optional argument,
and if you give it none, it drops into an interactive prompt. That second mode is what breaks
in a very specific way once packaged, which section 4 covers.

```
greeter/
├── pom.xml
└── src/main/java/com/example/greeter/Main.java
```

`Main.java`:

```java
package com.example.greeter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class Main {
    public static void main(String[] args) throws IOException {
        if (args.length > 0) {
            System.out.println("Hello, " + String.join(" ", args) + "!");
            return;
        }
        System.out.println("greeter " + Main.class.getPackage().getImplementationVersion());
        System.out.println("Type a name and press enter (or 'exit' to quit):");
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        String line;
        System.out.print("> ");
        System.out.flush();
        while ((line = reader.readLine()) != null) {
            if (line.equalsIgnoreCase("exit")) break;
            System.out.println("Hello, " + line + "!");
            System.out.print("> ");
            System.out.flush();
        }
    }
}
```

`pom.xml` — the only part that matters for packaging is the `maven-jar-plugin` block setting
`Main-Class` in the manifest (jpackage needs a runnable jar, same as `java -jar` does):

```xml
<build>
  <finalName>greeter</finalName>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-jar-plugin</artifactId>
      <configuration>
        <archive>
          <manifest>
            <mainClass>com.example.greeter.Main</mainClass>
            <addDefaultImplementationEntries>true</addDefaultImplementationEntries>
          </manifest>
        </archive>
      </configuration>
    </plugin>
  </plugins>
</build>
```

```
$ mvn -q clean package
$ java -jar target/greeter.jar World
Hello, World!
```

Works as a plain jar. Now package it.

## 1. The simplest possible invocation — and what's wrong with it

```
$ jpackage --type app-image --name Greeter --input target --main-jar greeter.jar --dest dist
```

No output on success (jpackage is silent unless something's wrong or you pass `--verbose`), and
it produces `dist/Greeter.app`. It runs:

```
$ dist/Greeter.app/Contents/MacOS/Greeter World
Hello, World!
```

Looks done. It isn't — look inside the bundle:

```
$ find dist/Greeter.app/Contents/app -maxdepth 1
dist/Greeter.app/Contents/app
dist/Greeter.app/Contents/app/.jpackage.xml
dist/Greeter.app/Contents/app/Greeter.cfg
dist/Greeter.app/Contents/app/classes
dist/Greeter.app/Contents/app/generated-sources
dist/Greeter.app/Contents/app/greeter.jar
dist/Greeter.app/Contents/app/maven-archiver
dist/Greeter.app/Contents/app/maven-status
```

`classes/`, `generated-sources/`, `maven-archiver/`, `maven-status/` — every intermediate Maven
build artifact is in there, shipped to your users. This isn't a bug, it's exactly what
`--input` is documented to do:

> `--input` — Path of the input directory that contains the files to be packaged. **All files in
> the input directory will be packaged into the application image.**

`--input` isn't "point me at your project," it's "package literally everything in this
directory." Point it at `target/` (or any Maven/Gradle build output directory) and you get the
whole build directory, not just the jar.

**Fix**: stage only what should ship.

```
$ mkdir staging && cp target/greeter.jar staging/
$ jpackage --type app-image --name Greeter --input staging --main-jar greeter.jar --dest dist
$ find dist/Greeter.app/Contents/app -maxdepth 1
dist/Greeter.app/Contents/app
dist/Greeter.app/Contents/app/.jpackage.xml
dist/Greeter.app/Contents/app/Greeter.cfg
dist/Greeter.app/Contents/app/greeter.jar
```

If your jar has external dependencies (not a fat jar), stage the jar *and* its `lib/`
directory the same way — `--input` doesn't care what's in the directory, it packages the
directory.

## 2. jpackage does not read your project's version

The Maven project above is version `1.2.0`. Check what actually shipped:

```
$ /usr/libexec/PlistBuddy -c "Print :CFBundleShortVersionString" dist/Greeter.app/Contents/Info.plist
1.0
```

`1.0` — jpackage's fallback default. It does not read `pom.xml`, and despite
`addDefaultImplementationEntries` putting `Implementation-Version: 1.2.0` in the jar's own
manifest, jpackage doesn't read that either. `Main-Class`, yes. `Implementation-Version`, no.
You have to say the version explicitly:

```
$ jpackage --type app-image --name Greeter --app-version 1.2.0 --vendor "Acme Corp" \
    --input staging --main-jar greeter.jar --dest dist
$ /usr/libexec/PlistBuddy -c "Print :CFBundleShortVersionString" dist/Greeter.app/Contents/Info.plist
1.2.0
```

`--app-version` is also pickier than Maven's versioning about what it accepts — both of these
are real errors, not hypothetical:

```
$ jpackage --type app-image --name Greeter --app-version 0.1.0 \
    --input staging --main-jar greeter.jar --dest dist
Bundler Mac Application Image skipped because of a configuration problem: The first number in an app-version cannot be zero or negative.
Advice to fix: Set a compatible 'app-version' value. Valid versions are one to three integers separated by dots.
```

```
$ jpackage --type app-image --name Greeter --app-version "1.2.0-SNAPSHOT" \
    --input staging --main-jar greeter.jar --dest dist
Bundler Mac Application Image skipped because of a configuration problem: "Version [1.2.0-SNAPSHOT] contains invalid component [.0-SNAPSHOT]"
Advice to fix: Set a compatible 'app-version' value. Valid versions are one to three integers separated by dots.
```

If your build uses Maven's `X.Y.Z-SNAPSHOT` convention (most do), you cannot pass that straight
through — strip the suffix, and if the first component can be `0` (early `0.x` projects), bump
it or version the desktop package independently of the library/service version. Both are
legitimate; just don't assume the string that works in `pom.xml` works here unmodified.

## 3. Building an actual installer

`--type app-image` is a directory, not something you'd hand to a user. For that:

```
$ jpackage --type dmg --name Greeter --app-version 1.2.0 --vendor "Acme Corp" \
    --input staging --main-jar greeter.jar --dest dist-dmg
$ ls dist-dmg
Greeter-1.2.0.dmg
```

Naming is always `<name>-<app-version>.<ext>`. Mounting it:

```
$ hdiutil attach dist-dmg/Greeter-1.2.0.dmg -nobrowse
$ ls -a /Volumes/Greeter
.	..	.DS_Store	.VolumeIcon.icns	.background	Greeter.app
```

Worth knowing before you're surprised by it: **there's no "drag to Applications" symlink** —
jpackage's built-in DMG is just the `.app`, a custom background, and a volume icon. If you want
the classic drag-and-drop installer look, that's a separate step on top (a plain `ln -s
/Applications Applications` inside the mounted volume before re-compressing, or a tool like
`create-dmg`), not something `--type dmg` gives you for free.

`--type pkg` is the other macOS option (an installer wizard instead of a mounted volume — better
for apps needing elevated install steps, like a launchd daemon).

**Windows** (`--type msi` or `exe`) and **Linux** (`--type deb` or `rpm`) follow the same
`--input`/`--main-jar`/`--app-version` contract — I didn't have Windows/Linux hardware to verify
these hands-on, so treat this paragraph as "documented, not battle-tested here." Two
platform-specific prerequisites that trip people up on CI runners that don't have them
preinstalled:

- **Windows `--type msi`** needs the **WiX Toolset v3** CLI on `PATH`. jpackage (through at
  least JDK 21) targets WiX 3's command structure — WiX 4 changed its CLI and isn't a drop-in
  replacement. If a CI job installing `wixtoolset` via a package manager suddenly breaks, check
  whether it silently moved to v4.
- **Linux `--type deb`** needs `fakeroot` (present by default on Debian/Ubuntu images);
  `--type rpm` needs `rpmbuild`, which usually isn't preinstalled and needs an explicit
  `apt install rpm` or equivalent.

## 4. The interactive-CLI problem

This is the one that isn't in the jpackage docs, because it isn't jpackage's problem to solve —
it's inherent to what "double-click an app" means on any desktop OS.

Double-click (or `open`) launches a process with no attached terminal: no visible stdout, and
stdin is empty/closed rather than "waiting for you to type." For a program that prints a menu
and exits, that's invisible but harmless. For an *interactive* CLI — anything that reads from
stdin in a loop — watch what actually happens:

```
$ dist/Greeter.app/Contents/MacOS/Greeter < /dev/null
greeter 1.2.0
Type a name and press enter (or 'exit' to quit):
>
```

It doesn't hang. `readLine()` hits EOF immediately (there's nothing on stdin to wait for) and
the loop exits on its first iteration. Launched via Finder with no visible console, a user sees
literally nothing: no window, no error, no crash dialog — the app just opens and is immediately
gone. That's a worse failure mode than a hang; a hang at least *looks* like something is
happening.

**Fix on macOS**: give the app a real terminal to run in. The clean way to do that without a
permission prompt is macOS's own `.command` file type — a shell script that Terminal.app opens
directly via `open`, no AppleScript/Apple Events involved (scripting Terminal via `osascript`
works too, but the first time it runs, macOS shows a one-time "allow this app to control
Terminal" Automation consent dialog — worse UX, and something that can't be dismissed from an
unattended/CI context at all).

```
$ cd dist/Greeter.app/Contents/MacOS

# rename the real, jpackage-built launcher out of the way
$ mv Greeter Greeter-bin

# jpackage's native launcher finds its config by its own argv[0] basename —
# the .cfg file has to move in lockstep with the rename
$ mv ../app/Greeter.cfg ../app/Greeter-bin.cfg

# a .command file just execs the real binary
$ cat > Greeter.command <<'EOF'
#!/bin/bash
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$DIR/Greeter-bin"
EOF
$ chmod +x Greeter.command

# Greeter (the name Finder/Info.plist expects as CFBundleExecutable)
# becomes a launcher that just opens the .command file
$ cat > Greeter <<'EOF'
#!/bin/bash
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
open "$DIR/Greeter.command"
EOF
$ chmod +x Greeter
```

Verified this actually opens a terminal and attaches a real TTY (note the `s004` terminal
column below — a headless launch shows `??` there instead):

```
$ open dist/Greeter.app
$ ps aux | grep Greeter-bin
hismaili  51555  ...  s004  S+  ...  dist/Greeter.app/Contents/MacOS/Greeter-bin
```

Wrap this rename-and-wrap sequence into your packaging script — do it every build, right after
the `jpackage` call, not by hand.

**Windows**: none of this is needed — pass `--win-console` to jpackage and the generated `.exe`
is a console application; double-clicking it natively opens a console window and runs it there,
the same behavior you get running it from `cmd.exe`.

**Linux**: `--linux-shortcut` generates a `.desktop` launcher entry, but jpackage has no flag to
mark it as terminal-based — the generated entry defaults to launching without a terminal, same
problem as macOS's default. There's no built-in `.command`-equivalent; the practical fix is a
post-build step that sets `Terminal=true` in the generated `.desktop` file (open the `.deb` with
`dpkg-deb -R`, patch the file, repack with `dpkg-deb -b`). I haven't verified this end-to-end on
real Linux hardware — flagging it as the least battle-tested part of this tutorial.

## 5. Useful flags you'll actually reach for

`--java-options` bakes JVM flags into the launcher config — useful for heap sizing, system
properties, `--add-opens`, etc., without the user ever typing a `java` command:

```
$ jpackage --type app-image --name Greeter --app-version 1.2.0 \
    --input staging --main-jar greeter.jar \
    --java-options "-Xmx256m" --java-options "-Dgreeter.mode=prod" \
    --dest dist
$ cat dist/Greeter.app/Contents/app/Greeter.cfg
[Application]
app.mainjar=$APPDIR/greeter.jar

[JavaOptions]
java-options=-Djpackage.app-version=1.2.0
java-options=-Xmx256m
java-options=-Dgreeter.mode=prod
```

Notice `-Djpackage.app-version=1.2.0` is injected automatically — jpackage sets that system
property for you, so the running app can read its own packaged version at runtime
(`System.getProperty("jpackage.app-version")`) without duplicating it anywhere in the jar.

Other flags worth knowing about:

| Flag | What it's for |
|---|---|
| `--icon <path>` | App icon — `.icns` (macOS), `.ico` (Windows), `.png` (Linux). Platform-specific, no cross-format conversion. |
| `--description`, `--copyright`, `--vendor` | Metadata surfaced in the OS's app info / installer UI. |
| `--add-launcher <name>=<props-file>` | Build additional launcher binaries from the same app image (e.g. a second entry point with different `--java-options` or `--arguments`), defined via a Java properties file. |
| `--app-content <path>` | Bundle extra files/directories alongside the app payload (config templates, licenses) that aren't part of `--input`. |
| `--mac-package-identifier` | macOS bundle identifier; defaults to your main class name if you don't set one — set it explicitly for anything you intend to distribute. |
| `--runtime-image` | Supply a pre-built (e.g. custom `jlink`-trimmed) JRE instead of letting jpackage run `jlink` itself with its defaults (`--strip-debug --no-header-files --no-man-pages --strip-native-commands`). |

## 6. Automating it in CI

The pattern that generalizes across projects: build the jar with your normal build tool, stage
just the jar (never the raw build-output directory — section 1), then invoke jpackage per
target OS in a matrix job, since each platform's installer type only builds natively on that
platform (you can't cross-compile a `.msi` from macOS, for example).

```yaml
strategy:
  matrix:
    include:
      - os: macos-latest
        type: dmg
      - os: windows-latest
        type: msi
      - os: ubuntu-latest
        type: deb
runs-on: ${{ matrix.os }}
steps:
  - uses: actions/checkout@v4
  - uses: actions/setup-java@v4
    with:
      distribution: temurin
      java-version: "21"
  - run: mvn -B clean package
  # stage just the jar — see section 1
  - run: |
      mkdir staging
      cp target/*.jar staging/
    shell: bash
  - run: >
      jpackage --type ${{ matrix.type }} --name MyApp --app-version 1.0.0
      --input staging --main-jar myapp.jar --dest dist
    shell: bash
  - uses: actions/upload-artifact@v4
    with:
      name: MyApp-${{ matrix.os }}
      path: dist
```

Remember the platform-specific prerequisites from section 3 (WiX v3 on the Windows runner,
`rpmbuild` on Linux if you're building `.rpm`) — install them as an explicit step before the
`jpackage` call; they're not on the default runner images.

## Troubleshooting quick reference

| Symptom | Cause | Fix |
|---|---|---|
| Packaged app contains build-tool cruft (`classes/`, `generated-sources/`, etc.) | `--input` packages the entire directory, not just the jar | Stage only the jar (+ `lib/` if not a fat jar) into its own directory first |
| App version shows as `1.0` regardless of your project version | jpackage ignores `pom.xml`/manifest `Implementation-Version` | Pass `--app-version` explicitly |
| `The first number in an app-version cannot be zero or negative` | `--app-version` starting with `0` | Use a non-zero leading component, or version the package independently |
| `Version [...] contains invalid component [...-SNAPSHOT]` | Maven-style `-SNAPSHOT`/qualifier suffix | Strip suffixes; `--app-version` only accepts 1–3 dot-separated integers |
| Double-clicked app does nothing, no window, no error | No TTY attached to a GUI-launched process; an interactive CLI's `readLine()` hits instant EOF | Wrap the launcher (macOS: `.command` file trick, section 4; Windows: `--win-console`; Linux: patch `Terminal=true` into the generated `.desktop` file) |
| Renamed the native launcher binary and the app won't start | jpackage's `.cfg` filename is derived from the launcher's own executable name | Rename the matching `Contents/app/<Name>.cfg` in lockstep |
| `.msi` build fails on a fresh Windows CI runner | WiX Toolset not preinstalled, or a default package resolved to WiX v4 (jpackage needs v3's CLI) | Explicitly install WiX Toolset v3 as a CI step |
| `.deb`/`.rpm` build fails on a fresh Linux CI runner | Missing `fakeroot` (deb) or `rpmbuild` (rpm) | Install the missing tool before the jpackage step |

## Further reading

The [official jpackage documentation](https://docs.oracle.com/en/java/javase/21/jpackage/) is
the authoritative reference for the full flag list and platform-specific bundler options — this
tutorial is meant to get you past the first hour of surprises, not replace it.
