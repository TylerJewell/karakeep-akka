# karakeep-akka

Saves a bookmark, asks a language model what to call it, and stores the tags it gets back.

A port of [karakeep-app/karakeep](https://github.com/karakeep-app/karakeep) onto **Akka**,
built with **Akka Specify**.

---

## Where it came from

karakeep is a bookmark manager that files links, notes and images and tags them
automatically. It was ported to derive a specification format precise enough to regenerate
a system on a different stack — the port is the vehicle, the specification is the
deliverable.

The specifications the port was generated from are in
[TylerJewell/akka-specify-harness](https://github.com/TylerJewell/akka-specify-harness)
under `karakeep-port/`.

---

## karakeep → this port

📉 492 TypeScript lines → **568 Java lines**<br>
📁 2 files → **10 files**<br>
⚡ 8.736 → **1.212** milliseconds to tag one bookmark<br>
⚡ 3.786 → **1.368** milliseconds to decide not to<br>
🎯 209 compared answers, 208 → **208** agreeing<br>
🧪 not measured → **49** tests<br>
🖥️ 1 → **1** process

Full method and the numbers that did *not* make this list:
[`bench/REPORT.md`](https://github.com/TylerJewell/akka-specify-harness/blob/main/karakeep-port/bench/REPORT.md).

---

## What it took to build

⏱️ **2.3 hours** from the first command to the published repository, **2.0** of them active<br>
💬 **582** exchanges with the model<br>
✍️ **399,569** tokens written by the model, **192,737,597** counting everything sent and re-sent<br>
🙋 **0** questions to a human<br>
🧪 **49** tests

The record of every question, and where the time went, is in the harness repository under
`port-log/`.

---

## What it does

- **A bookmark whose owner has turned tagging off never reaches the model.** Its existing
  tags are left exactly as they were, and the job reports that it succeeded.
- **A link with nothing to read is skipped, not failed.** A saved link that has a title but
  no description and no page text yet gives the model nothing to go on, so it is left
  alone.
- **Three shapes of reply give the same tags.** Plain JSON, JSON inside a fenced block, and
  JSON buried in a sentence are all read; a fenced block is read before a loose one, so a
  reply with a stray brace in its preamble still works.
- **A reply that cannot be read is an error, and writes nothing.** So is a reply that is
  valid but the wrong shape. The bookmark keeps whatever tags it had.
- **Tags are matched by a squashed name.** Lowercase, with spaces, hyphens and underscores
  removed, so an inferred `machine-learning` finds an existing `Machine Learning` instead
  of making a second one.
- **A new run replaces the last run's tags and leaves the owner's alone.** A tag the owner
  attached by hand stays theirs, even when the model asks for the same one.
- **An empty answer changes nothing.** It is not read as "this bookmark has no tags"; the
  previous run's tags stay.
- **A failing job tries four times.** Between the attempts the bookmark reads as still
  waiting, and it reads as failed only once the fourth attempt is spent.

---

## Design decisions

**One job per bookmark, saved as it goes.** A tagging job can fail halfway and has to be
able to pick up where it left off without forgetting how many tries it has used. Restarting
the service in the middle of one loses nothing, and a reader watching the bookmark sees the
same thing they would have seen if nothing had restarted.

**The count of tries is written down, not left to the machinery.** The rule this port is
built around is about what somebody sees *between* attempts, so the attempts have to be
visible rather than handled out of sight. That makes "it still says waiting after the third
try" a thing a test can check instead of a thing to hope for.

**Names are kept in their owner's own list.** Two people who both save something about the
same subject should not end up sharing a label, and one person's spelling should win over
their own bookmarks. Looking up a name only ever searches one person's list, so it stays
fast as the number of people grows.

**Deciding is separated from storing.** Working out which tags a bookmark should end up
with is arithmetic over two lists and nothing else, so it can be run and checked on its own
without a database anywhere near it. The awkward cases — an empty answer, a name the owner
already used — became tests that run in milliseconds.

**One request to the model per try, and no more.** A library that quietly retries turns
four tries into twelve requests and a bill nobody predicted. What the caller asked for and
what the model was asked are the same number here.

---

## Running it — the short path

You do not need Java, Maven, or the Akka command-line tool installed. Akka Specify installs
them for you.

**1. Install Akka Specify** in Claude Code:

```
/plugin marketplace add akka/ai-marketplace
/plugin install akka@akka-ai-marketplace
```

Restart Claude Code when it asks.

**2. Give it this prompt:**

> Clone https://github.com/TylerJewell/karakeep-akka into a new directory and open it.
> Then run /akka:setup to install everything this project needs, and /akka:build to
> compile it, run the tests, and start it locally.

**3. Open** http://localhost:9084/public/lists/your-name.

---

## Running it — the developer path

### Requirements

- Java 21 or newer
- Maven 3.9 or newer
- An Akka download token — run `akka code token` once
- Somewhere to send the model requests: any server that answers the OpenAI chat shape,
  including a local one

### Start the service

```bash
mvn compile
akka local run
```

The service starts on **port 9084**.

### Save something and have it tagged

```bash
curl -X POST localhost:9084/bookmarks/text -H 'Content-Type: application/json' \
  -d '{"bookmarkId":"n1","ownerId":"ada","title":"A note","text":"Write-ahead logging."}'

curl -X POST localhost:9084/bookmarks/n1/tag

curl localhost:9084/bookmarks/n1
```

The page at `/public/lists/ada` shows everything Ada has saved and what it was tagged,
and updates itself as tagging finishes.

---

## Configuration

| Variable | Default | Notes |
|---|---|---|
| `KARAKEEP_INFERENCE_BASE_URL` | `http://127.0.0.1:11434/v1` | Where the model requests go. Any server answering the OpenAI chat shape. |
| `KARAKEEP_INFERENCE_MODEL` | `gpt-4o-mini` | The name sent with each request. |
| `karakeep.tagging.max-attempts` | `4` | How many times a failing job tries before giving up. Set in `application.conf`. |
| `karakeep.inference.timeout` | `30s` | How long to wait for the model. Set in `application.conf`. |

---

## Where it differs from karakeep

Everything not listed here behaves the same way on purpose, including the parts that look
like mistakes.

- **What a failure is called.** karakeep has one way of reporting that a job went wrong, so
  a bookmark with nothing in it to read and a model that would not answer look the same
  from outside. This port tells them apart and says which happened, because the two need
  different responses from whoever is watching. Everything else about the two cases is
  identical on both sides: four attempts, no tags written, and a bookmark that ends up
  marked as failed.
- **How many requests reach the model when a job keeps failing.** karakeep makes twelve —
  four attempts, and the library it uses tries three times inside each one. This port makes
  four, one per attempt, because a caller who asked for four tries should not be billed for
  twelve.
- **The wait between attempts.** karakeep's queue is given a number of retries and no
  instruction about spacing them, so what happens between two attempts is whatever the
  queue does and was not measured. This port makes them one after another with no wait,
  which is what the queue was observed doing, and puts the number of attempts in
  configuration so it can be changed without changing code.
- **What happens to a job for a bookmark that is not there.** karakeep tries four times and
  gives up. This port does the same now; before the two were run side by side it retried
  for as long as anyone waited and never gave an answer at all.
- **The screen.** karakeep's own pages are part of a web application that reads its
  database directly, with no request in between that could be pointed somewhere else, so
  they cannot be run against this port. The page here shows the same things karakeep's
  public list page shows — the list's name and description, a card per bookmark with its
  title, its text or link, and the tags this port gave it — and differs in the rest:
  karakeep prints the date each bookmark was saved where this one prints whether tagging
  finished, karakeep shows the owner's picture and a feed link that this port has no
  equivalent of, and the two order the cards differently. The two screens side by side are
  in the harness repository under `karakeep-port/gui/`.
- **How the screen gets its data.** karakeep builds its page on the server and sends it
  once; asking for a change means asking for the page again. This port holds one connection
  open and pushes each change down it. That is a real difference in what a reader sees: a
  page that is sent once cannot miss anything, and a connection that drops misses whatever
  happened while it was down. This port answers that by sending the whole list on every
  change rather than a description of what changed, so a page that reconnects is correct
  immediately without replaying anything — measured at 3 seconds to notice and recover.
- **Who can call it.** karakeep requires an account or an API key for everything but a list
  its owner has made public. This port has no accounts and its endpoints are open to
  anyone who can reach them, because the part of karakeep that was rebuilt does not include
  its sign-in, and inventing one would be a behaviour neither system could be compared on.
- **Images and PDFs.** karakeep tags those too, using a different prompt and, for images, a
  model that can see. This port handles saved notes and saved links only.
- **Tags borrowed from similar bookmarks.** karakeep can look up bookmarks like this one and
  offer their tags to the model as suggestions, when it has been given a place to store
  what it needs for that. Neither this port nor the runs it was compared against had one,
  so both took the same path — the suggestions are never fetched. `not checked` for what
  karakeep does when it has one.
- **Custom prompts and pinned tag lists.** karakeep lets an owner add their own instructions
  to the prompt and restrict the model to a chosen set of tags. This port has neither.
- **What happens after tagging.** karakeep calls out to any webhooks the owner set up and
  updates its search index. This port does neither; nothing was rebuilt for it to call.
- **How long one job takes.** Both are measured in `bench/REPORT.md`. `not checked` for how
  either behaves with many jobs at once: every figure there is one job at a time.

---

## Licence

karakeep is under the GNU Affero General Public License, version 3, © its contributors.
This port is a derived work: its behaviour was read from karakeep's code and checked by
running karakeep, so it carries the same licence. No file, function or block of karakeep's
code was copied into it; the strings the two share are listed one by one in
[`ACKNOWLEDGEMENTS.md`](ACKNOWLEDGEMENTS.md).
