# Capstone as a LangGraph graph

A side-by-side illustration of `org.example.capstone.ResearchPipeline` in Python/LangGraph.
It is a sketch, not a replacement. The Java code is untouched.

```
START -> plan --(Send x3)--> research (x3, parallel) -> filter_notes --(none usable)--> END
                                                            |
                                                            v
                                              +-------> synthesize -> validate --(ok / 2 tries)--> END
                                              |                          |
                                              +------(violations)--------+
```

## Setup and run

```
cd langgraph-version
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
export ANTHROPIC_API_KEY=...
python research_graph.py "What changed in Java between 17 and 21?"
```

## What maps to what

| Java | LangGraph |
|---|---|
| `ResearchPipeline.run()` with local variables | `StateGraph(State)`; the variables become fields of `State` |
| `CompletableFuture.supplyAsync` x3 + `join()` | `Send("research", ...)` per sub-question; the reducer `Annotated[list, operator.add]` merges the results |
| `if (usable == 0) return ...` | `add_conditional_edges("filter_notes", after_filter)` |
| "retry the synthesizer once" | edge `validate -> synthesize` with an `attempts` counter |
| `exceptionally(...)` (R5) | `try/except` inside the `research` node |
| `Notes.withoutUnknownUrls` (R1) | node `filter_notes` |
| `ValidationService` (R1, R3) | node `validate` |
| `Budget` | `spent_usd` in state, checked in `synthesize` |

## Honest differences (what this sketch does NOT do)

- **Not run.** I had no API key in my shell, so the code is untested against the live API.
  Check the web-search block parsing in `research()` first; it is the most likely thing to need a tweak.
- **Budget is much simpler.** Java's `Budget.plan()` shrinks `maxTokens` and searches to fit the remaining money before
  each call and refuses when nothing fits. Here the cost is only estimated after each call (the prices are my
  estimate, not `Pricing.java`) and checked once. Rebuilding that would be a helper, not a LangGraph feature.
- **Researcher makes two calls** (search, then structured output). In Java one call does both through `outputConfig`.
  The LangChain wrapper does not combine a server-side search tool with structured output as directly.
- **Missing:** prompt caching, the thinking/effort settings, URL snapping by edit distance, per-stage timeouts,
  the INCOMPLETE brief, the run summary, markdown saving, logs.
- **What LangGraph adds that Java has not:** `app.compile(checkpointer=...)` gives pause/resume and replay;
  `interrupt()` gives human approval before synthesis; `app.get_graph().draw_mermaid()` draws the diagram.
  None of these is used here.

## Takeaway

The graph does not shorten the code much (Java's `StageRunner` and `Budget` are the bulk and have no equivalent here).
What it changes is that the control flow, which is now `if`/`for`/`join` inside one 175-line method, becomes
explicit edges you can see, draw, checkpoint and extend (for example a replanning loop = one more edge).
