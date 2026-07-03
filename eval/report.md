# AEGIS Evaluation Report

Cases: **18** · API: `http://localhost:8080`

| Metric | Value |
|---|---|
| Classification accuracy | 100% (18/18) |
| Escalation precision | 88% |
| Escalation recall | 100% |
| Escalation F1 | 0.93 |
| Retrieval hit-rate (top-4) | 94% (17/18) |
| Urgency distribution | {'CRITICAL': 7, 'NORMAL': 10, 'PRIORITIZED': 1} |

| Expected | Predicted | ✓ | exp.esc | got.esc | retr✓ |
|---|---|:-:|:-:|:-:|:-:|
| Credit card | Credit card | ✓ | True | True | ✓ |
| Credit card | Credit card | ✓ | False | False | ✓ |
| Credit card | Credit card | ✓ | True | True | ✓ |
| Mortgage | Mortgage | ✓ | False | False | ✓ |
| Mortgage | Mortgage | ✓ | True | True | ✗ |
| Debt collection | Debt collection | ✓ | True | True | ✓ |
| Debt collection | Debt collection | ✓ | False | True | ✓ |
| Checking or savings account | Checking or savings account | ✓ | False | False | ✓ |
| Checking or savings account | Checking or savings account | ✓ | True | True | ✓ |
| Credit reporting or other personal consumer reports | Credit reporting or other personal consumer reports | ✓ | False | False | ✓ |
| Student loan | Student loan | ✓ | False | False | ✓ |
| Money transfer, virtual currency, or money service | Money transfer, virtual currency, or money service | ✓ | False | False | ✓ |
| Prepaid card | Prepaid card | ✓ | False | False | ✓ |
| Vehicle loan or lease | Vehicle loan or lease | ✓ | True | True | ✓ |
| Payday loan, title loan, personal loan, or advance loan | Payday loan, title loan, personal loan, or advance loan | ✓ | False | False | ✓ |
| Mortgage | Mortgage | ✓ | False | False | ✓ |
| Debt or credit management | Debt or credit management | ✓ | False | False | ✓ |
| Credit reporting or other personal consumer reports | Credit reporting or other personal consumer reports | ✓ | True | True | ✓ |
