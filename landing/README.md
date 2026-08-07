# landing/

The marketing site for LifeOS. Deliberately isolated from `backend/` — plain HTML/CSS/JS, no
build step, no dependency on the Java project. It's copy and static assets only.

## Preview locally

```bash
cd landing
python3 -m http.server 8765
# open http://localhost:8765
```

Any static file server works — there's nothing to compile.

## Files

- `index.html` — the page. Copy is grounded in the actual domain model and CLI (`backend/README.md`,
  `docs/architecture/`) — update it there first if either changes, so the page doesn't drift into
  claiming things the product doesn't do yet.
- `styles.css` — one committed visual world (paper-ledger / card-catalog treatment), no light/dark
  toggle by design. See the file's header comment.
- `script.js` — a single scroll-reveal effect on the feature cards, gated behind
  `prefers-reduced-motion`. No framework, no build step.
- `favicon.svg`

## Publishing

`.github/workflows/pages.yml` deploys this folder to GitHub Pages on every push to `main` that
touches `landing/**`, plus manual dispatch. **One-time setup required** before the first deploy
will succeed: in the repo's GitHub settings, go to **Settings → Pages** and set **Source** to
**GitHub Actions**. Nobody but a repo admin can flip that switch — it can't be done from a
workflow file.
