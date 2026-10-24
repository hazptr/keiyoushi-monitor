# keiyoushi extension status

Every 1.6 extension on [keiyoushi](https://github.com/keiyoushi/extensions-source) main, checked
every day. Not affiliated with keiyoushi. Deployed via GitHub Pages from whichever fork runs this
pipeline — see the top-level README for how to stand up your own.

Each extension runs on a JVM harness, no phone. It loads popular, opens a manga and its chapter
list, loads the newest chapter, then downloads and decodes two pages through the extension’s own
client. Anything that fails gets one retry before it counts.

- **Working**: all of that went through
- **Partly working**: you can still read, but something else (search, latest updates) fails
- **Not working**: a step on the way to reading failed; the page says which and why
- **Can’t test**: we couldn’t get a result, usually a Cloudflare challenge the harness can’t solve.
  The app handles most of those, so check there
- **Not checked yet**: today’s run hasn’t reached it; the last result is shown
- **Not supported**: a 1.4 extension, which uses the older extension API this checker doesn’t run

Results can be wrong: sites change, block automated checks, go down for a bit, or act differently
depending on where you’re browsing from. It runs off main, so it can be ahead of what’s published.
If something shows as failing here, check it in the app before opening an issue.

The data is `status.json` on the `gh-pages` branch, replaced on every run.
