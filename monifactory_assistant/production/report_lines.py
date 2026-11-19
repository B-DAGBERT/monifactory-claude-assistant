"""Text lines for the resource section of a calculated Production plan."""

from collections.abc import Iterator

from .planner import Allocation
from .recipes import Amount


def format_amount(amount: Amount) -> str:
    return f"{amount.quantity} {amount.resource.unit} {amount.resource.id}"


def allocation_lines(allocations: tuple[Allocation, ...]) -> list[str]:
    labels = {"native:2-coffre": "À récupérer au coffre", "native:1-curseur": "Sur le curseur"}
    return [
        labels.get(entry.source_id, "Disponible attesté sur le joueur")
        + ": "
        + format_amount(Amount(entry.resource, entry.quantity))
        for entry in allocations[:40]
        if entry.source_kind == "physical"
    ]


def missing_and_waiting_lines(
    missing: tuple[Amount, ...], waiting: tuple[Amount, ...]
) -> Iterator[str]:
    for amount in missing[:50]:
        yield "À obtenir selon les relevés : " + format_amount(amount)
    for amount in waiting[:30]:
        yield "À attendre : " + format_amount(amount)
