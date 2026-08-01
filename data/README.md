# CFPB Complaint Training Dataset

`cfpb_complaints.csv` is the compact, balanced training dataset used for the
AEGIS complaint classifier. It contains 8,800 labelled complaint narratives:
800 examples for each of the 11 CFPB product categories supported by the
classifier.

## Schema

| Column | Description |
| --- | --- |
| `text` | Consumer complaint narrative. |
| `label` | CFPB product category used as the classification target. |

The labels align with the categories in `classifier/main.py` and include
checking and savings accounts, credit cards, credit reporting, debt
collection, debt management, money transfer, mortgages, personal loans,
prepaid cards, student loans, and vehicle loans.

## Source and intended use

This snapshot is derived from the [CFPB Consumer Complaint Database](https://www.consumerfinance.gov/data-research/consumer-complaints/).
It was filtered to records with complaint narratives, consolidated across
historically renamed CFPB product categories, and balanced for classifier
training and evaluation.

Use it for development, experiments, and reproducible demonstrations of this
project. It is not a source of regulatory advice or a production complaint
store. Although the CFPB publishes redacted narratives, treat the text as
potentially sensitive: do not attempt to re-identify consumers or combine it
with private customer data.

The multi-gigabyte raw CFPB export is deliberately not included in this
repository. This 12 MB curated snapshot keeps the repository practical to
clone while retaining a useful, representative dataset.
