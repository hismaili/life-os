# Tutorial: Diagnosing a `git push` 403 caused by the wrong cached GitHub account

This walks through a real debugging session where `git push` failed with a
`403` even though `git config user.name` / `user.email` were set correctly.
The point of writing it up is the *reasoning*, not just the commands — the
fix only takes a minute once you know where git actually gets its
credentials from.

## Symptom

```
$ git push --set-upstream origin main
remote: Permission to hismaili/life-os.git denied to hicham-ismaili-ADM_loreal.
fatal: unable to access 'https://github.com/hismaili/life-os.git/': The requested URL returned error: 403
```

The account GitHub complains about (`hicham-ismaili-ADM_loreal`) is not the
account that owns the repo, and doesn't match anything the user
consciously configured.

## Key insight: `git config user.*` is not authentication

The first (wrong) instinct is to assume `git config user.name` /
`user.email` control who you push as. They don't — they only stamp
**commit authorship** (who shows up in `git log`). HTTPS push
**authentication** is a completely separate mechanism, handled by git's
**credential helper**, which caches a username/token pair per host and
replays it on every request to that host — regardless of what
`user.name`/`user.email` say.

So the first move is to stop looking at `user.*` and go look at the
credential helper instead.

## Step 1 — Confirm the remote and rule out `user.*`

```bash
git remote -v
git config --local  user.name; git config --local  user.email
git config --global user.name; git config --global user.email
```

Result: the remote was the expected `https://github.com/hismaili/life-os.git`,
and both local and global `user.name`/`user.email` were already correct
(`hismaili` / the personal email). This confirms the 403 has nothing to do
with commit authorship — it's an auth problem, which points at the
credential helper.

## Step 2 — Find which credential helper is active

```bash
git config --show-origin --get-all credential.helper
```

```
file:/Applications/Xcode.app/Contents/Developer/usr/share/git-core/gitconfig	osxkeychain
```

On macOS, Xcode's git install wires up `osxkeychain` as the credential
helper by default. That means every HTTPS username/token git has ever used
successfully gets written into the macOS login keychain, keyed by host —
and replayed automatically next time, with no prompt. If a token for the
wrong account was ever stored there (e.g. from cloning a work repo, or
from Xcode/another tool authenticating to `github.com` under a work SSO),
git will silently reuse it for *any* `github.com` repo, including a
personal one.

## Step 3 — Ask the credential helper directly what it has stored

Rather than digging through Keychain Access.app, you can query the helper
the same way git itself does — by speaking its stdin protocol:

```bash
printf "protocol=https\nhost=github.com\n\n" | git credential-osxkeychain get
```

```
password=ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
username=hicham.ismaili-ADM_loreal
```

This is the smoking gun: a Personal Access Token stored under the
`hicham.ismaili-ADM_loreal` account (a work identity), returned for *any*
request to `host=github.com` — regardless of which repo you're pushing to.
That's exactly the identity GitHub reported in the 403.

You can cross-check the same thing via `security` (the underlying macOS
keychain CLI), which is useful when `git credential-osxkeychain` itself
seems stuck:

```bash
security find-internet-password -s github.com
```

## Step 4 — Why `security delete-generic-password` / manual erase can fail

The user had already tried clearing the keychain entry and reported the
erase "failed." A plain
`git credential-osxkeychain erase` can silently no-op or hit an ACL/lock
issue depending on which app last wrote the entry (Xcode-managed
keychain items sometimes carry stricter ACLs). Two independent removal
paths were combined to make sure the entry was actually gone, and the
result was verified rather than assumed:

```bash
# Path 1: ask the helper to erase what it owns
printf "protocol=https\nhost=github.com\n\n" | git credential-osxkeychain erase

# Path 2: go straight to the keychain and delete matching items directly,
# looping in case there are duplicate/legacy entries
while security delete-internet-password -s github.com >/dev/null 2>&1; do :; done
while security delete-generic-password  -s "github.com" >/dev/null 2>&1; do :; done

# Verify: a `get` for github.com should now return nothing
printf "protocol=https\nhost=github.com\n\n" | git credential-osxkeychain get
```

The looped `while` deletes are deliberate: `security delete-*-password`
only removes one matching item per call and exits non-zero once nothing
is left, so looping until it fails is a simple way to sweep all
duplicates instead of guessing how many there are.

Empty output from the final `get` confirms the cache is clear — the next
`git push` has nothing to fall back on and *must* prompt for fresh
credentials, rather than silently reusing the wrong ones again.

## Step 5 — Re-authenticate as the correct account

Since GitHub no longer accepts account passwords over HTTPS, the
replacement credential has to be a Personal Access Token generated **while
logged into the correct account** (Settings → Developer settings →
Personal access tokens):

```bash
git push --set-upstream origin main
# prompts:
#   Username: hismaili
#   Password: <personal access token for hismaili, NOT the account password>
```

Optionally, pin the username into the remote URL so the credential prompt
can't default to some other cached identity again:

```bash
git remote set-url origin https://hismaili@github.com/hismaili/life-os.git
```

## Follow-up: rotate the exposed token

`git credential-osxkeychain get` printed the work account's token in
plaintext to inspect it. Treat any token that has been displayed/logged
this way as compromised and revoke it at its source (GitHub →
Developer settings → Tokens, or the issuing org's SSO/PAT management),
even though it belonged to a different account than the one being fixed.

## Takeaways

- `git config user.name/email` = **authorship**. Credential helper
  (`osxkeychain`, `manager`, `store`, …) = **authentication**. A 403 with
  an unfamiliar username almost always means the credential helper, not
  `git config`.
- Credential helpers cache **per host**, not per repo — a token that
  worked for one `github.com` repo will be silently reused for every other
  `github.com` repo over HTTPS until it's cleared or superseded.
- Query the helper directly (`git credential-<helper> get`) instead of
  guessing from error messages — it tells you exactly which identity git
  will send.
- When a delete "fails" silently, verify with a second, independent tool
  (`security` CLI vs. `git credential-osxkeychain`) rather than assuming
  the first attempt worked or didn't.
- Always verify a cache-clear by re-running the same `get` you used to
  diagnose the problem — empty output is the actual proof, not just
  "the erase command didn't error."
