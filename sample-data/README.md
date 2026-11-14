# Sample data

Original, **fictional** documents written for DocQuery: free to publish, and used by the demo and by
the [RAG evaluation](../evaluation/README.md). Any resemblance to real organisations or people is
coincidental.

| File | Format | Content |
|---|---|---|
| `brightwell-employee-handbook.pdf` | PDF, 5 pages | Brightwell Analytics: working hours, leave, remote work, expenses, probation, security |
| `lumen-hub-300-manual.pdf` | PDF, 4 pages | Corvid Devices' Lumen Hub 300 smart-home controller: specs, setup, error codes, warranty |
| `data-retention-policy.md` | Markdown | Brightwell's information classification and data retention policy |
| `cv-samira-benali.pdf` | PDF, 2 pages | CV of a junior software engineer (tests attribution: CV facts vs company policy) |

The PDFs are generated from [`source/`](source) (`<<<PAGE>>>` separates pages), so they stay plain,
text-only and reproducible. To regenerate after editing a source:

```bash
cd ingestion-worker && ./mvnw test -Dtest=EvaluationFixtureTests -Devaluation.update=true
```
