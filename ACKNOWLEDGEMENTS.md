# Acknowledgements

This project is a port of **[karakeep-app/karakeep](https://github.com/karakeep-app/karakeep)**.

## The licence

karakeep is under the **GNU Affero General Public License, version 3**
(`karakeep-src/LICENSE`), read from the file rather than from a badge. The copyright is
karakeep's contributors'; the licence text itself is the Free Software Foundation's.

**What that means for this rebuild.** The AGPL is a copyleft licence, and it reaches
network use: a derived work made available over a network has to offer its source. This
port is derived from karakeep — its behaviour was read from karakeep's code and checked by
running karakeep — so **`karakeep-akka` is AGPL-3.0 whatever the scaffold's licence said,
and it stays private until somebody decides otherwise.** Making it public means publishing
it under AGPL-3.0 with karakeep's copyright notice intact. That is a decision, not a side
effect of backing work up.

## What was copied

`python toolkit/copied_strings.py karakeep --source karakeep-src` pulls every string
literal of ten characters or more out of the rebuild and names the ones that also occur in
the clone. It found 162 literals, 25 of them shared. **No file, function or block of
karakeep's code was copied into this port.** Every shared literal is one of five kinds,
and here is a sentence about each kind.

**1. Field and route names this port answers to, because the two systems describe the same
thing.** `attachedBy`, `autoTaggingEnabled`, `taggingStatus`, `description`,
`/bookmarks`, `/{bookmarkId}`, `/{bookmarkId}/tag`, `/{bookmarkId}/tags`,
`/public/lists`, `/public/lists/`. These are the vocabulary of the capability, and a port
that renamed them would be harder to compare, not more original. `taggingStatus` in
particular is the field SPEC-001 R19 and R20 are about, and its three values —
`pending`, `success`, `failure` — are karakeep's.

**2. The chat-completions request path**, `/chat/completions` and the header name
`Content-Type`. Both systems speak to a language model over the same OpenAI-shaped API,
so both name the same endpoint. Neither took it from the other; it is the vendor's.

**3. Two error-message fragments**, `' was not found'` and `' already exists'`. The first
is deliberate: SPEC-001 R15 is a rule about karakeep raising `bookmark with id <id> was
not found`, and question-log row 19 recorded that message by running the source. The port
raises the same sentence so the two agree on a message the spec governs, and the
review pass considered changing it and did not (`docs/review-findings.md`, I2). The
second, `' already exists'`, is an ordinary English phrase that both codebases arrived at
independently; nothing was read to write it.

**4. Prompt scaffolding**, `'\nDescription: '` and `'\nContent: '`. These two are copied,
and knowingly. SPEC-001 R3 says a link bookmark's prompt carries its URL, title and
description, and question-log row 18 established the layout by running karakeep's
`buildPrompt` and reading what reached the model. The port lays those four fields out the
same way so that the same bookmark produces a comparable prompt. The four lines, exactly
as both systems write them:

```
URL: <the link>
Title: <its title, or empty>
Description: <its description, or empty>
Content: <the crawled text, or empty>
```
 **The instruction around
them is this port's own wording, not karakeep's** — `TaggingDecision.prompt` writes its
own sentence asking for a JSON object, and karakeep's prompt text
(`packages/shared/prompts.ts`) was not copied.

**5. Test and screen fixtures**: `'Machine Learning'`, `'machine-learning'`,
`'a description'`, `' BOOKMARKS'`, `'interrupted'`, `'repetitions'`, `'still running'`.
The first two are the worked example from question-log row 9 — karakeep's own normaliser
turns `machine-learning` into a match for `Machine Learning`, and the port's tests use the
same pair so that a reader comparing the two sees the same case. `' BOOKMARKS'` is the
count label on karakeep's public list page, reproduced on the port's screen because
`gui/manifest.json` declares the two screens as showing the same thing; the rest are
ordinary words that happen to appear in both codebases.

## Behaviour is derived, and that is the point

Every rule in `specs/SPEC-001-karakeep.md` was read from karakeep's code and then checked
by running karakeep — `probes/source_probe.ts` and `probes/source_failure_probe.ts` drive
its real `runTagging`, its real queue and its real database. The rebuild is an independent
implementation of behaviour that karakeep defines. It is a derived work and says so.

## Also used

- Akka — the platform this port is built on.
- Jackson, for reading a model's reply and writing the benchmark's files.
