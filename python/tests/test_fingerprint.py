"""Input fingerprinting: catching the 'I changed the prompt and nothing happened' bug."""

from __future__ import annotations

import pytest

from duragent import InputChanged, Journal, connect


def test_the_same_input_replays_normally():
    conn = connect()
    journal = Journal(conn, "run-fp")
    calls = []

    def fn(state):
        calls.append(1)
        return {"answer": state["q"].upper()}

    node = journal.durable("plan", fn, fingerprint=True)

    assert node({"q": "a"}) == {"answer": "A"}
    assert node({"q": "a"}) == {"answer": "A"}
    assert len(calls) == 1


def test_a_changed_input_raises_instead_of_returning_stale_work():
    conn = connect()
    journal = Journal(conn, "run-fp")
    node = journal.durable("plan", lambda s: {"answer": s["q"].upper()}, fingerprint=True)

    node({"q": "a"})

    with pytest.raises(InputChanged) as caught:
        node({"q": "b"})

    assert caught.value.step_name == "plan"


def test_key_order_does_not_count_as_a_change():
    conn = connect()
    journal = Journal(conn, "run-fp")
    node = journal.durable("plan", lambda s: {"ok": True}, fingerprint=True)

    node({"a": 1, "b": 2})

    assert node({"b": 2, "a": 1}) == {"ok": True}, "a dict with the same content is the same input"


def test_without_a_fingerprint_the_old_behaviour_is_kept():
    conn = connect()
    journal = Journal(conn, "run-fp")
    node = journal.durable("plan", lambda s: {"answer": s["q"].upper()})

    node({"q": "a"})

    assert node({"q": "b"}) == {"answer": "A"}, "documented sharp edge, unchanged"


def test_two_runs_with_different_inputs_both_execute():
    conn = connect()
    calls = []

    def fn(state):
        calls.append(state["q"])
        return {"answer": state["q"]}

    Journal(conn, "run-1").durable("plan", fn, fingerprint=True)({"q": "a"})
    Journal(conn, "run-2").durable("plan", fn, fingerprint=True)({"q": "b"})

    assert calls == ["a", "b"], "a new run id is the way to ask for new work"
