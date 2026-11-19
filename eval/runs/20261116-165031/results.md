# Eval results

| # | type | mode | facts | valid | words | invented | cost | time | calls | note |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | lookup | baseline | 4/4 | yes | 256 | 0 | $0.0353 | 18.6 s | 1 |  |
| 2 | lookup | baseline | 4/4 | yes | 300 | 0 | $0.0235 | 15.2 s | 1 |  |
| 3 | lookup | baseline | 0/4 | NO | 0 | 0 | $0.0133 | 21.5 s | 1 | no valid report |
| 4 | lookup | baseline | 2/2 | yes | 234 | 0 | $0.0232 | 9.1 s | 1 |  |
| 5 | lookup | baseline | 3/3 | yes | 387 | 0 | $0.0243 | 19.0 s | 1 |  |
| 6 | lookup | baseline | 4/4 | yes | 310 | 0 | $0.0232 | 10.7 s | 1 |  |
| 7 | decision | baseline | 3/3 | yes | 326 | 0 | $0.0344 | 14.7 s | 1 |  |
| 8 | comparison | baseline | 2/4 | NO | 262 | 1 | $0.0462 | 19.0 s | 1 | violations: [R1 violated: invented URL (not in search results): https://www.1000ps.com/en-us/model/7490/yamaha-mt-09/2026] |
| 9 | trap | baseline | 1/1 | yes | 266 | 0 | $0.0343 | 14.0 s | 1 |  |
| 10 | honesty | baseline | 2/2 | NO | 253 | 0 | $0.0343 | 15.0 s | 1 | violations: [Empty brief: the title or the summary is blank] |

## Per mode

| mode | runs | avg facts | valid | avg words | avg cost | avg time |
|---|---|---|---|---|---|---|
| baseline | 10 | 85% | 7/10 | 259 | $0.0292 | 15.7 s |

Total spent (estimated): $0.2920
