"""AWS Lambda entry point. Configured as the handler in the Lambda function
definition (e.g. `scraper.index.lambda_handler`) — see pipeline.py for the
actual discovery/enrichment/submission logic.
"""

from .pipeline import lambda_handler

__all__ = ["lambda_handler"]
