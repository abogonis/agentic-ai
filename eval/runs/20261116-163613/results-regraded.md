# Eval results

| # | type | mode | facts | valid | words | invented | cost | time | calls | note |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | lookup | baseline | 4/4 | NO | 189 | 2 | $0.0006 | 7.0 s | 1 | violations: [R1 violated: invented URL (not in search results): https://openjdk.org/projects/jdk/21, R1 violated: invented URL (not in search results): https://openjdk.org/projects/jdk/17] |
| 1 | lookup | pipeline | 4/4 | yes | 366 | 0 | $0.0705 | 21.6 s | 5 |  |
| 2 | lookup | baseline | 4/4 | NO | 232 | 1 | $0.0004 | 4.7 s | 1 | violations: [R1 violated: invented URL (not in search results): https://docs.python.org/3/whatsnew/3.12.html] |
| 2 | lookup | pipeline | 3/4 | yes | 334 | 0 | $0.0689 | 16.0 s | 5 |  |
| 3 | lookup | baseline | 4/4 | NO | 129 | 0 | $0.0002 | 2.9 s | 1 | violations: [R1 violated: finding 1 has no source URL, R1 violated: finding 2 has no source URL] |
| 3 | lookup | pipeline | 4/4 | yes | 266 | 0 | $0.0466 | 14.4 s | 5 |  |
| 4 | lookup | baseline | 2/2 | NO | 116 | 0 | $0.0002 | 2.8 s | 1 | violations: [R1 violated: finding 1 has no source URL] |
| 4 | lookup | pipeline | 2/2 | yes | 279 | 0 | $0.0693 | 15.2 s | 5 |  |
| 5 | lookup | baseline | 3/3 | NO | 153 | 0 | $0.0002 | 3.3 s | 1 | violations: [R1 violated: finding 1 has no source URL] |
| 5 | lookup | pipeline | 3/3 | yes | 325 | 0 | $0.0584 | 17.1 s | 5 |  |
| 6 | lookup | baseline | 4/4 | NO | 249 | 0 | $0.0003 | 4.4 s | 1 | violations: [R1 violated: finding 1 has no source URL, R1 violated: finding 2 has no source URL, R1 violated: finding 3 has no source URL, R1 violated: finding 4 has no source URL, R1 violated: finding 5 has no source URL] |
| 6 | lookup | pipeline | 4/4 | yes | 327 | 0 | $0.0689 | 15.8 s | 5 |  |
| 7 | decision | baseline | 3/3 | NO | 92 | 0 | $0.0002 | 3.0 s | 1 | violations: [R1 violated: finding 1 has no source URL] |
| 7 | decision | pipeline | 3/3 | yes | 350 | 0 | $0.0691 | 18.9 s | 5 |  |
| 8 | comparison | baseline | 0/4 | NO | 171 | 0 | $0.0003 | 4.1 s | 1 | violations: [R1 violated: finding 1 has no source URL, R1 violated: finding 2 has no source URL] |
| 8 | comparison | pipeline | 4/4 | yes | 382 | 0 | $0.0705 | 20.2 s | 5 |  |
| 9 | trap | baseline | 1/1 | NO | 107 | 0 | $0.0001 | 3.0 s | 1 | violations: [R1 violated: finding 1 has no source URL] |
| 9 | trap | pipeline | 1/1 | yes | 360 | 0 | $0.0697 | 19.4 s | 5 |  |
| 10 | honesty | baseline | 2/2 | NO | 76 | 0 | $0.0001 | 2.7 s | 1 | violations: [R1 violated: finding 1 has no source URL] |
| 10 | honesty | pipeline | 1/2 | yes | 445 | 0 | $0.0701 | 20.8 s | 5 |  |

## Per mode

| mode | runs | avg facts | valid | avg words | avg cost | avg time |
|---|---|---|---|---|---|---|
| baseline | 10 | 90% | 0/10 | 151 | $0.0003 | 3.8 s |
| pipeline | 10 | 93% | 10/10 | 343 | $0.0662 | 17.9 s |

Total spent (estimated): $0.6647
