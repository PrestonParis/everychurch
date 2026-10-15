from unittest.mock import MagicMock, patch

from scraper.config import BulkImportConfig
from scraper.denomination_fill import build_fills, main, run


def make_config():
    return BulkImportConfig(bulk_import_api_key="bulk-secret", server_base_url="https://api.example.com")


def church(id, name, denomination=None, claimed=False):
    return {"id": id, "name": name, "denomination": denomination, "claimed": claimed}


def test_build_fills_labels_blank_denominations_from_the_name():
    fills, stats = build_fills([
        church(1, "BETHEL AME CHURCH"),
        church(2, "BELLS CHAPEL CHURCH OF THE NAZARENE", denomination=" "),
        church(3, "COMMUNITY CHURCH"),
    ])

    assert fills == [{"id": 1, "denomination": "Methodist"}, {"id": 2, "denomination": "Evangelical"}]
    assert stats == {
        "blankDenomination": 3,
        "churchesToFill": 2,
        "byLabel": {"Methodist": 1, "Evangelical": 1},
    }


def test_build_fills_never_touches_a_labelled_or_claimed_church():
    fills, stats = build_fills([
        church(1, "FIRST BAPTIST CHURCH", denomination="Other"),
        church(2, "FIRST BAPTIST CHURCH", claimed=True),
    ])

    assert fills == []
    assert stats["blankDenomination"] == 0


def _patched_run(fills, **kwargs):
    with patch("scraper.denomination_fill.fetch_all_existing_churches", return_value=[church(1, "A")]), \
            patch("scraper.denomination_fill.build_fills", return_value=(fills, {"churchesToFill": len(fills)})), \
            patch("scraper.denomination_fill.submit_fills", side_effect=lambda c, s, batch, source: len(batch)) as submit:
        sleep = MagicMock()
        result = run(make_config(), MagicMock(), sleep=sleep, **kwargs)
    return result, submit, sleep


def test_run_sends_fills_in_batches_tagged_name():
    result, submit, sleep = _patched_run([{"id": i, "denomination": "Baptist"} for i in range(3)], batch_size=2)

    assert result == {"churchesToFill": 3, "updated": 3}
    assert submit.call_count == 2
    assert submit.call_args.kwargs == {"source": "name"}
    assert sleep.call_count == 1


def test_run_dry_run_sends_nothing():
    result, submit, _ = _patched_run([{"id": 1, "denomination": "Baptist"}], dry_run=True)

    assert result == {"churchesToFill": 1, "updated": 0}
    submit.assert_not_called()


def test_main_parses_arguments():
    with patch("scraper.denomination_fill.run", return_value={}) as run_mock:
        main(["--dry-run"])
        main([])

    assert run_mock.call_args_list[0].kwargs == {"dry_run": True}
    assert run_mock.call_args_list[1].kwargs == {"dry_run": False}
